package app.onym.android.discovery

import kotlinx.serialization.json.JsonElement
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.net.URI
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.Base64

/**
 * Trust failures of the static-snapshot / Ed25519 profile, carrying
 * the abstract contract's error codes (`Discovery.md` §12 as mapped
 * by `Discovery-Static-Ed25519.md` §9). Sealed so callers can match
 * exhaustively; [code] is the wire/UI-stable identifier.
 */
sealed class DiscoveryTrustError(val code: String, message: String) : Exception(message) {
    /** Signature/key-pin failure, unknown top-level field, bad
     *  version/profile/seat, expired `validUntil`, oversize, or URI
     *  violation on the provider manifest. */
    class ProviderManifestInvalid(reason: String) :
        DiscoveryTrustError("provider_manifest_invalid", reason)

    /** Signature failure, sequence gap/repeat/rollback,
     *  `previousDigest` mismatch, duplicate `componentId`, unknown
     *  top-level field, or oversize on a catalog snapshot. */
    class SnapshotInvalid(reason: String) :
        DiscoveryTrustError("snapshot_invalid", reason)

    /** `expiresAt` in the past — entries are stale history, never
     *  current recommendations. */
    class SnapshotExpired(reason: String) :
        DiscoveryTrustError("snapshot_expired", reason)

    /** Fetched destination-manifest bytes hash differently from the
     *  digest the catalog entry pinned. The entry is rejected — never
     *  "refreshed" by trusting newer bytes the provider did not review. */
    class EntryManifestMismatch(reason: String) :
        DiscoveryTrustError("entry_manifest_mismatch", reason)

    /** Destination manifest unreachable or over the §7 size bound. */
    class EntryManifestUnavailable(reason: String) :
        DiscoveryTrustError("entry_manifest_unavailable", reason)

    /** Destination seat signature/schema/expiry failure (raised by
     *  seat adapters, not by this module's digest check). */
    class EntryManifestInvalid(reason: String) :
        DiscoveryTrustError("entry_manifest_invalid", reason)

    /** Two configured sources bind the same `componentId` to
     *  different manifest digests. */
    class SourceConflict(reason: String) :
        DiscoveryTrustError("source_conflict", reason)
}

/** [DiscoveryTrust.verifyProviderManifest] result: the parsed
 *  manifest plus the operator key to pin (trust-on-first-use — the
 *  caller shows the fingerprint, then persists [operatorKeyHex]). */
data class VerifiedProviderManifest(
    val manifest: DiscoveryProviderManifest,
    /** 64-char lowercase hex of the 32-byte Ed25519 operator key. */
    val operatorKeyHex: String,
)

/** [DiscoveryTrust.verifySnapshot] result. */
data class VerifiedSnapshot(
    val snapshot: CatalogSnapshot,
    /** Entries that survived lossy decoding + per-entry validation,
     *  in the snapshot's (policy-ranked) order. */
    val entries: List<CatalogEntry>,
    /** Indexes into `snapshot.entries` that were skipped as malformed. */
    val skippedEntryIndexes: List<Int>,
    /** `sha256:` digest of the exact fetched bytes — retained by the
     *  caller for the next refresh's chain comparison (§8). */
    val digest: String,
)

/** Chain anchor for [DiscoveryTrust.verifySnapshot] when only the
 *  previously *accepted* digest + sequence were retained (§8) rather
 *  than the full previous bytes. */
data class AcceptedSnapshotRef(
    /** `sha256:` digest of the previously accepted snapshot's exact bytes. */
    val digest: String,
    val sequence: Long,
)

/**
 * Hard-enforced client verification for the Discovery seat
 * (`Discovery-Static-Ed25519.md` §6): provider manifests with TOFU
 * key pinning, snapshot chains, and destination-manifest digest
 * binding.
 *
 * Unlike :foundation's `TrustedAssetVerifier` (a soft gate behind
 * `ENFORCE_SIGNATURES`, deliberately untouched), every check here is
 * unconditional — a Discovery document that fails verification is
 * rejected, full stop. `now` is always injected so tests pin exact
 * clock behaviour.
 *
 * Mirrors the Rust reference (`onym-discovery` `src/verify.rs`);
 * both pin the same conformance fixtures.
 */
object DiscoveryTrust {

    // ─── provider manifest ────────────────────────────────────────

    /**
     * Verify a provider manifest's size, strict schema, field rules,
     * expiry, self-signature — and the key pin.
     *
     * @param raw exact fetched bytes.
     * @param pinnedOperatorKeyHex the key pinned at add time, or
     *        `null` on first use: the returned
     *        [VerifiedProviderManifest.operatorKeyHex] is what the
     *        caller pins after showing the user the fingerprint. A
     *        later manifest signed by a different key is
     *        [DiscoveryTrustError.ProviderManifestInvalid], never a
     *        silent rotation.
     * @param now injected clock for the `validUntil` check.
     */
    fun verifyProviderManifest(
        raw: ByteArray,
        pinnedOperatorKeyHex: String? = null,
        now: Instant,
    ): VerifiedProviderManifest {
        if (raw.size > DiscoveryProfile.MAX_MANIFEST_BYTES) {
            throw manifestInvalid("manifest exceeds ${DiscoveryProfile.MAX_MANIFEST_BYTES} bytes")
        }
        val manifest = try {
            DiscoveryJson.strict.decodeFromString(
                DiscoveryProviderManifest.serializer(),
                String(raw, Charsets.UTF_8),
            )
        } catch (e: Exception) {
            throw manifestInvalid("schema: ${e.message}")
        }

        if (manifest.version != 1) {
            throw manifestInvalid("unsupported version ${manifest.version}")
        }
        if (manifest.implementationProfile != DiscoveryProfile.IMPLEMENTATION_PROFILE) {
            throw manifestInvalid(
                "unsupported implementation profile ${manifest.implementationProfile}"
            )
        }
        if (manifest.seat != "discovery") {
            throw manifestInvalid("seat must be discovery, got ${manifest.seat}")
        }
        validateComponentId(manifest.providerId) { manifestInvalid(it) }
        val operatorKeyHex = parseOperatorKeyHex(manifest.operator) { manifestInvalid(it) }
        if (pinnedOperatorKeyHex != null && pinnedOperatorKeyHex != operatorKeyHex) {
            throw manifestInvalid(
                "operator key does not match the pinned key — re-keying requires re-adding the source"
            )
        }

        if (manifest.catalogs.isEmpty()) {
            throw manifestInvalid("manifest declares no catalogs")
        }
        val seenCatalogIds = mutableSetOf<String>()
        for (catalog in manifest.catalogs) {
            validateCatalogId(catalog.catalogId) { manifestInvalid(it) }
            if (!seenCatalogIds.add(catalog.catalogId)) {
                throw manifestInvalid("duplicate catalogId ${catalog.catalogId}")
            }
            validateUri(catalog.snapshot) { manifestInvalid(it) }
            validateDigest(catalog.policy) { manifestInvalid(it) }
            catalog.policyUri?.let { validateUri(it) { m -> manifestInvalid(m) } }
        }
        manifest.privacyProfileUri?.let { validateUri(it) { m -> manifestInvalid(m) } }
        manifest.privacyProfile?.let { validateDigest(it) { m -> manifestInvalid(m) } }

        val validUntil = parseTimestamp(manifest.validUntil) { manifestInvalid(it) }
        if (!validUntil.isAfter(now)) {
            throw manifestInvalid("manifest expired at ${manifest.validUntil}")
        }

        val signature = manifest.signature
            ?: throw manifestInvalid("manifest is unsigned")
        verifyEd25519(operatorKeyHex, raw, signature) { manifestInvalid(it) }

        return VerifiedProviderManifest(manifest, operatorKeyHex)
    }

    // ─── catalog snapshot ─────────────────────────────────────────

    /**
     * Verify a snapshot against its (already verified) provider
     * manifest and the exact bytes of the previously accepted
     * snapshot. Convenience over the [AcceptedSnapshotRef] variant —
     * checks additionally that the previous bytes are for the same
     * catalog.
     */
    fun verifySnapshot(
        raw: ByteArray,
        manifest: DiscoveryProviderManifest,
        previousRaw: ByteArray? = null,
        now: Instant,
    ): VerifiedSnapshot {
        val previous = previousRaw?.let { bytes ->
            val prev = try {
                DiscoveryJson.strict.decodeFromString(
                    CatalogSnapshot.serializer(),
                    String(bytes, Charsets.UTF_8),
                )
            } catch (e: Exception) {
                throw snapshotInvalid("previous snapshot: ${e.message}")
            }
            val currentCatalogId = peekCatalogId(raw)
            if (currentCatalogId != null && prev.catalogId != currentCatalogId) {
                throw snapshotInvalid("previous snapshot is for a different catalog")
            }
            AcceptedSnapshotRef(digest = sha256Digest(bytes), sequence = prev.sequence)
        }
        return verifySnapshot(raw, manifest, previous, now)
    }

    /**
     * Verify a snapshot against its (already verified) provider
     * manifest and the retained chain anchor (§8: digest + sequence of
     * the last accepted snapshot). A sequence that does not increase
     * by exactly 1, or a `previousDigest` that does not match the
     * retained bytes, is evidence of rollback or equivocation —
     * [DiscoveryTrustError.SnapshotInvalid], never a silent accept of
     * either fork.
     */
    fun verifySnapshot(
        raw: ByteArray,
        manifest: DiscoveryProviderManifest,
        previous: AcceptedSnapshotRef?,
        now: Instant,
    ): VerifiedSnapshot {
        if (raw.size > DiscoveryProfile.MAX_SNAPSHOT_BYTES) {
            throw snapshotInvalid("snapshot exceeds ${DiscoveryProfile.MAX_SNAPSHOT_BYTES} bytes")
        }
        val snapshot = try {
            DiscoveryJson.strict.decodeFromString(
                CatalogSnapshot.serializer(),
                String(raw, Charsets.UTF_8),
            )
        } catch (e: Exception) {
            throw snapshotInvalid("schema: ${e.message}")
        }

        if (snapshot.version != 1) {
            throw snapshotInvalid("unsupported version ${snapshot.version}")
        }
        if (snapshot.implementationProfile != DiscoveryProfile.IMPLEMENTATION_PROFILE) {
            throw snapshotInvalid("unsupported implementation profile")
        }
        if (snapshot.providerId != manifest.providerId) {
            throw snapshotInvalid(
                "providerId ${snapshot.providerId} does not match manifest ${manifest.providerId}"
            )
        }
        val descriptor = manifest.catalogs.firstOrNull { it.catalogId == snapshot.catalogId }
            ?: throw snapshotInvalid("catalogId ${snapshot.catalogId} not declared by manifest")
        validateDigest(snapshot.policyDigest) { snapshotInvalid(it) }
        if (snapshot.policyDigest != descriptor.policy) {
            throw snapshotInvalid("policyDigest does not match manifest's pinned policy")
        }

        // Chain rules (§4.2).
        if (snapshot.sequence < 1) {
            throw snapshotInvalid("sequence starts at 1")
        }
        if (snapshot.sequence == 1L) {
            if (snapshot.previousDigest != null) {
                throw snapshotInvalid("sequence 1 must not carry previousDigest")
            }
        } else {
            val previousDigest = snapshot.previousDigest
                ?: throw snapshotInvalid("sequence > 1 requires previousDigest")
            validateDigest(previousDigest) { snapshotInvalid(it) }
        }
        if (previous != null) {
            if (snapshot.sequence != previous.sequence + 1) {
                throw snapshotInvalid(
                    "sequence must increase by 1: previous ${previous.sequence} → ${snapshot.sequence}"
                )
            }
            if (snapshot.previousDigest != previous.digest) {
                throw snapshotInvalid("previousDigest does not match previous snapshot bytes")
            }
        }

        // Freshness.
        val generatedAt = parseTimestamp(snapshot.generatedAt) { snapshotInvalid(it) }
        val expiresAt = parseTimestamp(snapshot.expiresAt) { snapshotInvalid(it) }
        if (!expiresAt.isAfter(generatedAt)) {
            throw snapshotInvalid("expiresAt must be after generatedAt")
        }
        if (Duration.between(generatedAt, expiresAt) >
            Duration.ofDays(DiscoveryProfile.MAX_EXPIRY_WINDOW_DAYS)
        ) {
            throw snapshotInvalid("expiry window exceeds ${DiscoveryProfile.MAX_EXPIRY_WINDOW_DAYS} days")
        }
        if (!expiresAt.isAfter(now)) {
            throw DiscoveryTrustError.SnapshotExpired("expired at ${snapshot.expiresAt}")
        }

        // Signature over the exact fetched bytes' canonical form, by
        // the manifest's operator key.
        val operatorKeyHex = parseOperatorKeyHex(manifest.operator) { snapshotInvalid(it) }
        val signature = snapshot.signature
            ?: throw snapshotInvalid("snapshot is unsigned")
        verifyEd25519(operatorKeyHex, raw, signature) { snapshotInvalid(it) }

        // Entries: lossy per-element decode, then strict per-entry
        // field validation; duplicates among survivors are fatal.
        if (snapshot.entries.size > DiscoveryProfile.MAX_ENTRIES) {
            throw snapshotInvalid(
                "${snapshot.entries.size} entries exceeds ${DiscoveryProfile.MAX_ENTRIES}"
            )
        }
        val entries = mutableListOf<CatalogEntry>()
        val skipped = mutableListOf<Int>()
        snapshot.entries.forEachIndexed { index, element ->
            val entry = decodeEntry(element)
            if (entry != null) entries.add(entry) else skipped.add(index)
        }
        val seenComponentIds = mutableSetOf<String>()
        for (entry in entries) {
            if (!seenComponentIds.add(entry.componentId)) {
                throw snapshotInvalid("duplicate componentId ${entry.componentId}")
            }
        }

        return VerifiedSnapshot(
            snapshot = snapshot,
            entries = entries,
            skippedEntryIndexes = skipped,
            digest = sha256Digest(raw),
        )
    }

    // ─── destination manifest ─────────────────────────────────────

    /**
     * Verify fetched destination-manifest bytes against the digest
     * the catalog entry pinned. Discovery's authority stops here —
     * whether the manifest satisfies the destination *seat's* rules is
     * that seat's contract, applied by the client afterwards.
     */
    fun verifyDestination(manifestBytes: ByteArray, pinnedDigest: String) {
        if (manifestBytes.size > DiscoveryProfile.MAX_DESTINATION_MANIFEST_BYTES) {
            throw DiscoveryTrustError.EntryManifestUnavailable(
                "destination manifest exceeds ${DiscoveryProfile.MAX_DESTINATION_MANIFEST_BYTES} bytes"
            )
        }
        val actual = sha256Digest(manifestBytes)
        if (actual != pinnedDigest) {
            throw DiscoveryTrustError.EntryManifestMismatch(
                "pinned $pinnedDigest, fetched bytes hash to $actual"
            )
        }
    }

    // ─── shared helpers ───────────────────────────────────────────

    /** `sha256:` + 64 lowercase hex over [bytes] — always the exact
     *  published bytes, never a canonical form (§2). */
    fun sha256Digest(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return "sha256:" + digest.joinToString("") { "%02x".format(it) }
    }

    /**
     * Lossy entry decode: strict within the entry (unknown entry
     * field, bad syntax, unknown relationship, §7 URI violation →
     * `null`, the caller skips + counts). Never throws — one bad
     * entry must not take the snapshot down.
     */
    private fun decodeEntry(element: JsonElement): CatalogEntry? {
        val raw = try {
            DiscoveryJson.strict.decodeFromJsonElement(RawCatalogEntry.serializer(), element)
        } catch (_: Exception) {
            return null
        }
        return try {
            validateComponentId(raw.componentId) { entrySkip(it) }
            if (raw.seatType.isEmpty()) throw entrySkip("empty seatType")
            validateUri(raw.manifest.uri) { entrySkip(it) }
            validateDigest(raw.manifest.digest) { entrySkip(it) }
            parseOperatorKeyHex(raw.operator) { entrySkip(it) }
            parseTimestamp(raw.listedAt) { entrySkip(it) }
            raw.reviewedAt?.let { parseTimestamp(it) { m -> entrySkip(m) } }
            // Unknown relationship → skip, never defaulted to `none`
            // (advertising must not pass as organic via a new tag).
            val relationship = EntryRelationship.fromRaw(raw.relationship)
                ?: throw entrySkip("unknown relationship ${raw.relationship}")
            if (raw.placement.isEmpty()) throw entrySkip("empty placement")
            for (evidence in raw.evidence) {
                validateUri(evidence.uri) { entrySkip(it) }
                validateDigest(evidence.digest) { entrySkip(it) }
            }
            CatalogEntry(
                componentId = raw.componentId,
                seatType = raw.seatType,
                manifest = raw.manifest,
                operator = raw.operator,
                profiles = raw.profiles,
                evidence = raw.evidence,
                listedAt = raw.listedAt,
                reviewedAt = raw.reviewedAt,
                relationship = relationship,
                placement = raw.placement,
            )
        } catch (_: EntrySkipException) {
            null
        }
    }

    /** Internal control-flow marker for per-entry validation failures. */
    private class EntrySkipException(message: String) : Exception(message)

    private fun entrySkip(message: String) = EntrySkipException(message)

    private fun manifestInvalid(reason: String) =
        DiscoveryTrustError.ProviderManifestInvalid(reason)

    private fun snapshotInvalid(reason: String) =
        DiscoveryTrustError.SnapshotInvalid(reason)

    /** Cheap catalogId peek of the fetched bytes for the previous-
     *  bytes overload's same-catalog check; `null` if unparseable
     *  (the main strict decode reports the real error). */
    private fun peekCatalogId(raw: ByteArray): String? = try {
        val element = kotlinx.serialization.json.Json
            .parseToJsonElement(String(raw, Charsets.UTF_8))
        (element as? kotlinx.serialization.json.JsonObject)
            ?.get("catalogId")
            ?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull() }
    } catch (_: Exception) {
        null
    }

    private fun kotlinx.serialization.json.JsonPrimitive.contentOrNull(): String? =
        if (isString) content else null

    /** `onym:component:<token>` with token in `[a-z0-9-]{1,64}` (§2). */
    private inline fun validateComponentId(value: String, error: (String) -> Exception) {
        val token = value.removePrefixOrNull("onym:component:")
            ?: throw error("component id must start with onym:component: — got $value")
        if (token.isEmpty() || token.length > 64 ||
            !token.all { it in 'a'..'z' || it in '0'..'9' || it == '-' }
        ) {
            throw error("invalid component token: $token")
        }
    }

    /** `[a-z0-9-]{1,64}` — catalog ids (§4.1). */
    private inline fun validateCatalogId(value: String, error: (String) -> Exception) {
        if (value.isEmpty() || value.length > 64 ||
            !value.all { it in 'a'..'z' || it in '0'..'9' || it == '-' }
        ) {
            throw error("invalid catalog id: $value")
        }
    }

    /** `sha256:` + 64 lowercase hex (§2). */
    private inline fun validateDigest(value: String, error: (String) -> Exception) {
        val hexPart = value.removePrefixOrNull("sha256:")
            ?: throw error("digest must start with sha256: — got $value")
        if (hexPart.length != 64 || !hexPart.all { it in '0'..'9' || it in 'a'..'f' }) {
            throw error("digest must be 64 lowercase hex characters")
        }
    }

    /** `onym:key:` + 64 lowercase hex → the hex part (§2). */
    private inline fun parseOperatorKeyHex(value: String, error: (String) -> Exception): String {
        val hexPart = value.removePrefixOrNull("onym:key:")
            ?: throw error("operator key must start with onym:key: — got $value")
        if (hexPart.length != 64 || !hexPart.all { it in '0'..'9' || it in 'a'..'f' }) {
            throw error("operator key must be 64 lowercase hex characters")
        }
        return hexPart
    }

    /**
     * Profile §7 URI rules: `https` only, DNS host (no IPv4/IPv6
     * literals — closes the SSRF-to-local-network path), no userinfo,
     * no query, no fragment, default port only (443, omitted).
     */
    private inline fun validateUri(value: String, error: (String) -> Exception) {
        val uri = try {
            URI(value)
        } catch (e: Exception) {
            throw error("$value: ${e.message}")
        }
        if (uri.scheme != "https") throw error("$value: scheme must be https")
        val host = uri.host ?: throw error("$value: host must be a DNS name")
        if (host.startsWith("[") || host.contains(':') || IPV4_LITERAL.matches(host)) {
            throw error("$value: host must be a DNS name, not an IP literal")
        }
        if (uri.userInfo != null) throw error("$value: userinfo is not allowed")
        if (uri.rawQuery != null) throw error("$value: query is not allowed")
        if (uri.rawFragment != null) throw error("$value: fragment is not allowed")
        if (uri.port != -1) throw error("$value: explicit port is not allowed")
    }

    private val IPV4_LITERAL = Regex("""\d{1,3}(\.\d{1,3}){3}""")

    /** RFC 3339 UTC with `Z` suffix (§2). */
    private inline fun parseTimestamp(value: String, error: (String) -> Exception): Instant =
        try {
            Instant.parse(value)
        } catch (e: DateTimeParseException) {
            throw error("invalid timestamp $value: ${e.message}")
        }

    /**
     * Ed25519 (BouncyCastle) over [raw]'s canonical signing bytes.
     * [signatureBase64] accepts padded or unpadded standard base64
     * (§3).
     */
    private inline fun verifyEd25519(
        operatorKeyHex: String,
        raw: ByteArray,
        signatureBase64: String,
        error: (String) -> Exception,
    ) {
        val signingBytes = try {
            DiscoveryCanonical.signingBytes(raw)
        } catch (e: Exception) {
            throw error("canonicalization: ${e.message}")
        }
        val signature = try {
            Base64.getDecoder().decode(signatureBase64)
        } catch (e: IllegalArgumentException) {
            throw error("signature is not valid base64: ${e.message}")
        }
        if (signature.size != 64) throw error("signature must be 64 bytes")
        val publicKey = operatorKeyHex.chunked(2)
            .map { it.toInt(16).toByte() }
            .toByteArray()
        val verified = try {
            val verifier = Ed25519Signer().apply {
                init(false, Ed25519PublicKeyParameters(publicKey, 0))
            }
            verifier.update(signingBytes, 0, signingBytes.size)
            verifier.verifySignature(signature)
        } catch (_: Exception) {
            false
        }
        if (!verified) throw error("Ed25519 signature did not verify")
    }

    private fun String.removePrefixOrNull(prefix: String): String? =
        if (startsWith(prefix)) removePrefix(prefix) else null
}
