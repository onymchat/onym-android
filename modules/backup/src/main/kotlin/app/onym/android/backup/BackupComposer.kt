package app.onym.android.backup

import app.onym.android.backup.BackupFormat.toLowercaseHex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.security.SecureRandom
import java.time.Instant

/**
 * Composes one sealed snapshot from [source]. Holds **only** a
 * [BackupSourceProviding] — no identity repository, no key store — so
 * "seed material can never enter a snapshot" is a structural
 * guarantee, not a remembered rule.
 *
 * Mirrors `BackupComposer` in onym-ios OnymBackup.
 */
class BackupComposer(
    private val source: BackupSourceProviding,
    private val mediaPolicy: BackupMediaPolicy,
    private val workingDirectory: File,
) {
    suspend fun compose(
        keyMaterial: BackupKeyMaterial,
        acceptedTermsId: String,
        supersedes: SnapshotReference? = null,
        now: Instant = Instant.now(),
    ): SealedSnapshot = withContext(Dispatchers.IO) {
        val operationId = randomOperationId()
        val scratchFile = File(workingDirectory, "backup-scratch-$operationId.tmp")
        val plaintextFile = File(workingDirectory, "backup-plain-$operationId.tmp")
        val writer = BackupArchiveWriter(scratchFile)
        try {
            writer.setIdentityCount(source.identityCount())
            writer.setMediaPolicy(mediaPolicy)

            val groups = source.groups()
            writer.appendGroups(groups)

            val messages = mutableListOf<BackupMessageRecord>()
            for (group in groups) {
                messages += source.messages(group.groupId, group.ownerIdentityId)
            }
            writer.appendMessages(messages)

            writer.appendInvitations(source.invitations())
            writer.appendConsents(source.consents())

            if (mediaPolicy == BackupMediaPolicy.IncludeCiphertext) {
                for (address in collectReferencedBlobAddresses(messages)) {
                    when (val availability = source.blobCiphertext(address)) {
                        is BackupBlobAvailability.Gone -> Unit
                        is BackupBlobAvailability.Available -> {
                            // Re-verify against the claimed address before inclusion — the
                            // restorer double-checks again, but a mismatch here should never
                            // even leave the device.
                            if (BackupFormat.digest(availability.ciphertext) == "sha256:$address") {
                                writer.appendBlob(BackupBlobRecord(address, availability.ciphertext))
                            }
                        }
                    }
                }
            }

            writer.finish(plaintextFile, now)

            val sealedFile = File(workingDirectory, "backup-sealed-$operationId.seal")
            val reference = try {
                BackupSealer.seal(plaintextFile, sealedFile, keyMaterial.archiveRoot)
            } catch (e: Exception) {
                sealedFile.delete()
                throw e
            }

            return@withContext SealedSnapshot(
                operationId = operationId,
                snapshotReference = reference,
                sealedBytesFile = sealedFile,
                sealedAt = now,
                acceptedTermsId = acceptedTermsId,
                supersedes = supersedes,
            )
        } finally {
            // The plaintext archive (and its scratch predecessor) must
            // never survive past sealing, including on any throw.
            writer.abort()
            scratchFile.delete()
            plaintextFile.delete()
        }
    }

    private fun randomOperationId(): String = ByteArray(16).also { SecureRandom().nextBytes(it) }.toLowercaseHex()
}

/** Walks every message's attachment JSON **structurally** for
 *  `"sha256"` string values, rather than typed-decoding a known
 *  attachment shape — future-proof against a new attachment kind
 *  gaining its own content-addressed field. Shared by the composer
 *  (what to fetch) and the restorer (what's still unresolved). */
internal fun collectReferencedBlobAddresses(messages: List<BackupMessageRecord>): Set<String> {
    val addresses = mutableSetOf<String>()
    for (message in messages) {
        val json = message.attachmentJson ?: continue
        val element = try {
            Json.parseToJsonElement(json)
        } catch (_: Exception) {
            continue
        }
        collectSha256Values(element, addresses)
    }
    return addresses
}

private fun collectSha256Values(element: JsonElement, into: MutableSet<String>) {
    when (element) {
        is JsonObject -> for ((key, value) in element) {
            if (key == "sha256" && value is JsonPrimitive && value.isString) into += value.content
            collectSha256Values(value, into)
        }
        is JsonArray -> element.forEach { collectSha256Values(it, into) }
        else -> Unit
    }
}
