package app.onym.android.backup

import app.onym.android.backup.BackupFormat.toLowercaseHex
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64

private val JSON_MEDIA_TYPE = "application/json".toMediaType()
private val OCTET_STREAM_MEDIA_TYPE = "application/octet-stream".toMediaType()

/** Bounds this client enforces on every response, regardless of what
 *  the operator declares — see `backup/UI-Backup-Object-HTTP.md` §13.
 *  An operator's own unsigned figures (e.g.
 *  `maximumRetainedSnapshots`) are clamped before use in any size
 *  arithmetic, never trusted directly. */
object ObjectHttpLimits {
    const val MAX_JSON_RESPONSE_BYTES = 4L * 1024 * 1024
    const val MAX_UPLOAD_CHUNK_BYTES = 64L * 1024 * 1024
    const val MAX_LISTED_SNAPSHOTS = 100_000L
    const val MAX_LIST_RESPONSE_BYTES = 32L * 1024 * 1024
}

/**
 * The object-HTTP wire adapter, per onym-system
 * `backup/UI-Backup-Object-HTTP.md` §9. Signs every `/v1/` request
 * with [BackupAccessProof], never follows redirects, bounds every
 * response, and normalizes operator responses into [BackupError] /
 * [BackupOutcome] without ever leaking a wire-specific object across
 * [BackupPort].
 *
 * [entitlementProvider] is read **fresh on every request** — never
 * cached — so a paid operator doesn't keep refusing after a
 * successful purchase just because a stale `null` was captured at
 * construction time.
 *
 * Mirrors `URLSessionBackupClient` in onym-ios OnymBackup.
 */
class ObjectHttpBackupClient(
    baseUrl: String,
    httpClient: OkHttpClient,
    private val signingKey: Ed25519PrivateKeyParameters,
    private val holderReference: String,
    /** Base64-encoded `SeatEntitlement` bytes, or `null` if none is
     *  currently held. Read per-request. */
    private val entitlementProvider: suspend () -> String?,
) : BackupPort {

    private val baseUrl: HttpUrl = baseUrl.toHttpUrl().also {
        require(it.scheme == "https") { "backup operator endpoint must be HTTPS: $baseUrl" }
    }

    // Never follow redirects: a signed request covers exactly one
    // path/origin. A debug base-URL override, if any, is supplied by
    // the caller building [baseUrl] — never read from an unconditional
    // Release code path (guarded at every call site upstream of this
    // class, not here).
    private val http: OkHttpClient = httpClient.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    // Every override below runs its OkHttp `.execute()` (a blocking
    // socket call) and, for download/export, subsequent file I/O
    // under `Dispatchers.IO` — callers invoke these from Main-bound
    // scopes (Compose's `rememberCoroutineScope`/`LaunchedEffect`),
    // and `.execute()` on Main throws `NetworkOnMainThreadException`.
    // Matches `OkHttpBlossomClient`'s posture.

    override suspend fun connect(): BackupConnection = withContext(Dispatchers.IO) {
        val response = signedGet("/profile.json")
        val body = response.boundedJsonBody(ObjectHttpLimits.MAX_JSON_RESPONSE_BYTES)
        // Readiness only — the terms fetch/verify happens where the
        // manifest is available (composition root), not here.
        BackupConnection(acceptedTermsId = (body["acceptedTermsId"] as? JsonPrimitive)?.content)
    }

    override suspend fun preflight(snapshot: SealedSnapshot): BackupPreflight = withContext(Dispatchers.IO) {
        val request = PreflightRequest(
            operationId = snapshot.operationId,
            snapshotReference = snapshot.snapshotReference.toWire(),
            acceptedTermsId = snapshot.acceptedTermsId,
            supersedes = snapshot.supersedes?.digest,
        )
        val bodyBytes = Json.encodeToString(PreflightRequest.serializer(), request).toByteArray(Charsets.UTF_8)
        val response = signedPost("/v1/preflight", bodyBytes)

        if (response.code == 200) {
            val body = response.boundedJsonBody(ObjectHttpLimits.MAX_JSON_RESPONSE_BYTES)
            if (body["uploadId"] != null) {
                val chunkBytes = (body["chunkBytes"] as JsonPrimitive).long
                val chunkCount = (body["chunkCount"] as JsonPrimitive).long
                if (chunkBytes <= 0 || chunkBytes > ObjectHttpLimits.MAX_UPLOAD_CHUNK_BYTES) {
                    throw BackupError.LocalFailure(LocalFailureReason.InvalidUploadGrant)
                }
                // The grant must be consistent with the sealed file's
                // ACTUAL size before any byte moves — a corrupt or
                // hostile grant that under/over-states chunking must
                // never drive the upload loop. A conforming grant
                // covers the file with the last chunk possibly
                // partial: chunkCount == ceil(actualSize / chunkBytes),
                // i.e. expectedTotal in [actualSize, actualSize + chunkBytes).
                // (A symmetric ±chunkBytes window here would admit a
                // grant that under-counts by one whole chunk, which
                // then drives the upload loop into sending a
                // last-chunk body larger than chunkBytes.)
                val actualSize = snapshot.sealedBytesFile.length()
                val expectedTotal = chunkCount * chunkBytes
                if (expectedTotal < actualSize || expectedTotal >= actualSize + chunkBytes) {
                    throw BackupError.LocalFailure(LocalFailureReason.InvalidUploadGrant)
                }
                return@withContext BackupPreflight.Grant(
                    BackupUploadGrant(
                        uploadId = (body["uploadId"] as JsonPrimitive).content,
                        chunkBytes = chunkBytes,
                        chunkCount = chunkCount,
                        expiresAt = (body["expiresAt"] as JsonPrimitive).content.let(BackupFormat::instantFromRfc3339)
                            ?: Instant.now(),
                    ),
                )
            }
            // 200 without an uploadId is an already-resolved outcome
            // (e.g. already_retained).
            return@withContext BackupPreflight.Resolved(decodeOutcome(body, snapshot.operationId, snapshot.snapshotReference))
        }
        throw response.toBackupError()
    }

    override suspend fun uploadSnapshot(snapshot: SealedSnapshot, grant: BackupUploadGrant): BackupOutcome = withContext(Dispatchers.IO) {
        snapshot.sealedBytesFile.inputStream().use { input ->
            for (index in 0 until grant.chunkCount) {
                val isLast = index == grant.chunkCount - 1
                val size = if (isLast) {
                    (snapshot.sealedBytesFile.length() - grant.chunkBytes * index).coerceAtLeast(0)
                } else {
                    grant.chunkBytes
                }
                val chunk = ByteArray(size.toInt())
                var offset = 0
                while (offset < chunk.size) {
                    val read = input.read(chunk, offset, chunk.size - offset)
                    if (read < 0) throw BackupError.LocalFailure(LocalFailureReason.SealedBytesUnavailable)
                    offset += read
                }
                val response = signedPut("/v1/uploads/${grant.uploadId}/chunks/$index", chunk, OCTET_STREAM_MEDIA_TYPE)
                if (response.code != 200) throw response.toBackupError()
            }
        }
        val commitResponse = signedPost("/v1/uploads/${grant.uploadId}/commit", ByteArray(0))
        if (commitResponse.code != 200) throw commitResponse.toBackupError()
        val body = commitResponse.boundedJsonBody(ObjectHttpLimits.MAX_JSON_RESPONSE_BYTES)
        decodeOutcome(body, snapshot.operationId, snapshot.snapshotReference)
    }

    override suspend fun listSnapshots(): List<RetainedSnapshot> = withContext(Dispatchers.IO) {
        val response = signedGet("/v1/snapshots")
        if (response.code != 200) throw response.toBackupError()
        val bytes = response.boundedBytes(ObjectHttpLimits.MAX_LIST_RESPONSE_BYTES)
        val array = Json.parseToJsonElement(String(bytes, Charsets.UTF_8)).let {
            (it as? kotlinx.serialization.json.JsonArray) ?: kotlinx.serialization.json.JsonArray(emptyList())
        }
        array.take(ObjectHttpLimits.MAX_LISTED_SNAPSHOTS.toInt()).mapNotNull { element ->
            val obj = element.jsonObject
            val refObj = obj["snapshotReference"]?.jsonObject ?: return@mapNotNull null
            RetainedSnapshot(
                snapshotReference = refObj.toReference() ?: return@mapNotNull null,
                acceptedTermsId = (obj["acceptedTermsId"] as? JsonPrimitive)?.content ?: return@mapNotNull null,
                retainedAt = (obj["retainedAt"] as? JsonPrimitive)?.content?.let(BackupFormat::instantFromRfc3339)
                    ?: return@mapNotNull null,
                retainedUntil = (obj["retainedUntil"] as? JsonPrimitive)?.content?.let(BackupFormat::instantFromRfc3339),
                supersedes = obj["supersedes"]?.let { s -> (s as? JsonObject)?.toReference() },
                status = (obj["status"] as? JsonPrimitive)?.content ?: "unknown",
            )
        }
    }

    override suspend fun downloadSnapshot(reference: SnapshotReference, destination: File): Unit = withContext(Dispatchers.IO) {
        val response = signedGet("/v1/snapshots/${reference.digest.removePrefix("sha256:")}")
        if (response.code != 200 && response.code != 206) throw response.toBackupError()
        val body = response.body ?: throw BackupError.OperatorUnavailable
        var total = 0L
        try {
            FileOutputStream(destination).use { out ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        // Cut off exactly at the reference's own declared
                        // size — too long or too short are both
                        // IncompleteSnapshot; never trust a stream past
                        // what was pinned.
                        if (total > reference.sealedByteSize) throw BackupError.IncompleteSnapshot
                        out.write(buffer, 0, read)
                    }
                }
            }
            if (total != reference.sealedByteSize) throw BackupError.IncompleteSnapshot
        } catch (e: Exception) {
            destination.delete()
            throw e
        }
    }

    override suspend fun eraseSnapshot(scope: ErasureScope): ErasureReceipt = withContext(Dispatchers.IO) {
        val scopeValue = when (scope) {
            is ErasureScope.All -> "\"all\""
            is ErasureScope.Snapshot -> "\"${scope.reference.digest}\""
        }
        val bodyBytes = "{\"version\":1,\"scope\":$scopeValue}".toByteArray(Charsets.UTF_8)
        val response = signedPost("/v1/erasures", bodyBytes)
        if (response.code != 200) throw response.toBackupError()
        val body = response.boundedJsonBody(ObjectHttpLimits.MAX_JSON_RESPONSE_BYTES)
        ErasureReceipt(
            receiptId = body.str("receiptId"),
            operator = body.str("operator"),
            scope = body.str("scope"),
            acknowledgedAt = BackupFormat.instantFromRfc3339(body.str("acknowledgedAt")) ?: Instant.now(),
            completionCommittedBy = BackupFormat.instantFromRfc3339(body.str("completionCommittedBy")) ?: Instant.now(),
            coveredScope = body.str("coveredScope"),
            excludedScope = body.str("excludedScope"),
            termsId = body.str("termsId"),
            signature = body.str("signature"),
        )
    }

    override suspend fun exportSnapshots(directory: File): BackupExport = withContext(Dispatchers.IO) {
        // Export never consults entitlement state (spec §9.7) — this
        // path deliberately never reads [entitlementProvider].
        val response = signedGetUnauthenticatedEntitlement("/v1/exports")
        if (response.code != 200) throw response.toBackupError()
        val body = response.body ?: throw BackupError.OperatorUnavailable
        directory.mkdirs()
        val manifestFile = File(directory, "manifest.tar")
        FileOutputStream(manifestFile).use { out -> body.byteStream().use { it.copyTo(out) } }
        BackupExport(exportedAt = Instant.now(), directory = directory)
    }

    override suspend fun queryOutcome(operationId: String): BackupOutcome? = withContext(Dispatchers.IO) {
        val response = signedGet("/v1/operations/$operationId")
        if (response.code == 404) return@withContext null
        if (response.code != 200) throw response.toBackupError()
        val body = response.boundedJsonBody(ObjectHttpLimits.MAX_JSON_RESPONSE_BYTES)
        val refObj = body["snapshotReference"]?.jsonObject ?: return@withContext null
        val reference = refObj.toReference() ?: return@withContext null
        decodeOutcome(body, operationId, reference)
    }

    // -- wire plumbing --

    private fun decodeOutcome(body: JsonObject, operationId: String, reference: SnapshotReference): BackupOutcome {
        val outcomeObj = (body["outcome"] as? JsonObject) ?: body
        val status = BackupOutcomeStatus.fromWireValue((outcomeObj["status"] as? JsonPrimitive)?.content ?: "")
        return BackupOutcome(
            operationId = (body["operationId"] as? JsonPrimitive)?.content ?: operationId,
            snapshotReference = reference,
            status = status,
            locator = (outcomeObj["locator"] as? JsonPrimitive)?.content,
            retainedUntil = (outcomeObj["retainedUntil"] as? JsonPrimitive)?.content?.let(BackupFormat::instantFromRfc3339),
            termsId = (outcomeObj["termsId"] as? JsonPrimitive)?.content,
            evidence = (outcomeObj["evidence"] as? JsonPrimitive)?.content,
        )
    }

    private suspend fun signedGet(path: String): Response = signedRequest("GET", path, ByteArray(0), attachEntitlement = true)
    private suspend fun signedGetUnauthenticatedEntitlement(path: String): Response =
        signedRequest("GET", path, ByteArray(0), attachEntitlement = false)
    private suspend fun signedPost(path: String, body: ByteArray): Response =
        signedRequest("POST", path, body, attachEntitlement = true)
    private suspend fun signedPut(path: String, body: ByteArray, mediaType: okhttp3.MediaType): Response =
        signedRequest("PUT", path, body, attachEntitlement = true, mediaType = mediaType)

    private suspend fun signedRequest(
        method: String,
        path: String,
        body: ByteArray,
        attachEntitlement: Boolean,
        mediaType: okhttp3.MediaType = JSON_MEDIA_TYPE,
    ): Response {
        val url = baseUrl.newBuilder().addPathSegments(path.trimStart('/')).build()
        val headers = BackupAccessProof.headers(method, url.encodedPath, body, signingKey, holderReference)
        val builder = Request.Builder()
            .url(url)
            .header("X-Onym-Holder", headers.holder)
            .header("X-Onym-Timestamp", headers.timestamp)
            .header("X-Onym-Nonce", headers.nonce)
            .header("X-Onym-Signature", headers.signature)

        if (attachEntitlement) {
            entitlementProvider()?.let { builder.header("X-Onym-Entitlement", it) }
        }

        when (method) {
            "GET" -> builder.get()
            "POST" -> builder.post(body.toRequestBody(mediaType))
            "PUT" -> builder.put(body.toRequestBody(mediaType))
            else -> error("unsupported method $method")
        }

        return http.newCall(builder.build()).execute()
    }

    private fun Response.boundedBytes(maxBytes: Long): ByteArray {
        val body = this.body ?: return ByteArray(0)
        val contentLength = body.contentLength()
        if (contentLength in 0..maxBytes) return body.bytes()
        // Unknown or over-declared length: read up to the bound and
        // refuse if there's more.
        val bytes = body.byteStream().readNBytesCompat(maxBytes + 1)
        if (bytes.size.toLong() > maxBytes) throw BackupError.OperatorUnavailable
        return bytes
    }

    private fun Response.boundedJsonBody(maxBytes: Long): JsonObject {
        val bytes = boundedBytes(maxBytes)
        return try {
            Json.parseToJsonElement(String(bytes, Charsets.UTF_8)).jsonObject
        } catch (_: Exception) {
            throw BackupError.OperatorUnavailable
        }
    }

    private fun Response.toBackupError(): BackupError {
        val obj = try {
            boundedJsonBody(ObjectHttpLimits.MAX_JSON_RESPONSE_BYTES)
        } catch (_: Exception) {
            null
        }
        val code = (obj?.get("error") as? JsonPrimitive)?.content
        val message = (obj?.get("message") as? JsonPrimitive)?.content ?: ""
        return when (code) {
            "unsupported_profile" -> BackupError.UnsupportedProfile
            "invalid_reference" -> BackupError.InvalidReference
            "terms_changed" -> BackupError.TermsChanged((obj?.get("currentTermsId") as? JsonPrimitive)?.content ?: "")
            "signature_invalid" -> BackupError.AccessRefused
            "payment_required" -> {
                val pr = obj?.get("paymentRequired")?.jsonObject
                BackupError.PaymentRequired(
                    componentId = (pr?.get("componentId") as? JsonPrimitive)?.content ?: "",
                    offerIds = (pr?.get("offers") as? kotlinx.serialization.json.JsonArray)
                        ?.mapNotNull { (it as? JsonPrimitive)?.content } ?: emptyList(),
                    entitlementIssuers = (pr?.get("entitlementIssuers") as? kotlinx.serialization.json.JsonArray)
                        ?.mapNotNull { (it as? JsonPrimitive)?.content } ?: emptyList(),
                )
            }
            "invalid_entitlement" -> BackupError.InvalidEntitlement(
                entitlementIssuers = (obj?.get("entitlementIssuers") as? kotlinx.serialization.json.JsonArray)
                    ?.mapNotNull { (it as? JsonPrimitive)?.content } ?: emptyList(),
            )
            "snapshot_too_large" -> BackupError.SnapshotTooLarge(
                (obj?.get("maximumSealedSnapshotBytes") as? JsonPrimitive)?.long ?: 0L,
            )
            "quota_exceeded" -> BackupError.QuotaExceeded(
                retainedSnapshots = (obj?.get("retainedSnapshots") as? JsonPrimitive)?.long ?: 0L,
                maximumRetainedSnapshots = (obj?.get("maximumRetainedSnapshots") as? JsonPrimitive)?.long ?: 0L,
                retainedBytes = (obj?.get("retainedBytes") as? JsonPrimitive)?.long ?: 0L,
                limitBytes = (obj?.get("limitBytes") as? JsonPrimitive)?.long ?: 0L,
            )
            "digest_mismatch" -> BackupError.IncompleteSnapshot
            "retention_expired" -> BackupError.RetentionExpired
            "export_withheld" -> BackupError.ExportWithheld
            "operator_unavailable" -> BackupError.OperatorUnavailable
            else -> if (code != null) BackupError.Rejected(code, message) else BackupError.OperatorUnavailable
        }
    }

    private fun JsonObject.str(key: String): String = (this[key] as? JsonPrimitive)?.content ?: ""

    private fun JsonObject.toReference(): SnapshotReference? {
        val digest = (this["digest"] as? JsonPrimitive)?.content ?: return null
        val size = (this["sealedByteSize"] as? JsonPrimitive)?.long ?: return null
        return try {
            SnapshotReference(digest = digest, sealedByteSize = size)
        } catch (_: Exception) {
            null
        }
    }

    private fun SnapshotReference.toWire(): JsonObject = JsonObject(
        mapOf(
            "referenceVersion" to JsonPrimitive(referenceVersion),
            "algorithm" to JsonPrimitive(algorithm),
            "digest" to JsonPrimitive(digest),
            "sealedByteSize" to JsonPrimitive(sealedByteSize),
        ),
    )
}

@Serializable
private data class PreflightRequest(
    val version: Int = 1,
    val operationId: String,
    val snapshotReference: JsonObject,
    val acceptedTermsId: String,
    val supersedes: String? = null,
)

private fun java.io.InputStream.readNBytesCompat(n: Long): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(64 * 1024)
    var remaining = n
    while (remaining > 0) {
        val toRead = minOf(buffer.size.toLong(), remaining).toInt()
        val read = read(buffer, 0, toRead)
        if (read < 0) break
        out.write(buffer, 0, read)
        remaining -= read
    }
    return out.toByteArray()
}
