package app.onym.android.naming

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import java.util.concurrent.TimeUnit

/** Concrete BSN HTTP profile; naming annotations never become routing or membership input. */
class BsnNamingClient(
    private val http: OkHttpClient = OkHttpClient.Builder()
        .followRedirects(false).followSslRedirects(false)
        .callTimeout(12, TimeUnit.SECONDS).build(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val trustKey: String = TRUST_KEY,
) {
    data class Name(val record: JsonObject, val validUntil: Long) {
        val displayName get() = record.text("displayName")
        val account get() = record.text("stellarAccount")
        val label get() = "$displayName @$NAMESPACE · ${account.take(6)}…${account.takeLast(6)}"
    }
    companion object {
        const val BASE = "https://atlas.predhit.com/bsn-np/"
        const val NAMESPACE = "bsn.atlas.predhit.com"
        const val REGISTRY = "onym:registry:atlas-bsn"
        const val TRUST_KEY = "82693a8632f81e2e26b777a34b9d82adb5a60138530cc840fc9eae9d2344806e"
        private val json = Json { }
        fun canonical(value: JsonElement): String = when (value) {
            is JsonObject -> value.keys.sorted().joinToString(",", "{", "}") { "${JsonPrimitive(it)}:${canonical(value.getValue(it))}" }
            is JsonArray -> value.joinToString(",", "[", "]", transform = ::canonical)
            else -> value.toString()
        }
        fun bytes(kind: String, value: JsonObject): ByteArray =
            ("onym-bsn-np-v1:$kind\n" + canonical(JsonObject(value - "signature"))).toByteArray(Charsets.UTF_8)
        fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
        fun digest(value: JsonObject) = "sha256:" + hex(MessageDigest.getInstance("SHA-256").digest(canonical(value).toByteArray(Charsets.UTF_8)))
        private fun unhex(s: String): ByteArray {
            require(s.matches(Regex("[a-f0-9]{64}"))) { "Invalid signing key" }
            return s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        }
        fun verify(kind: String, value: JsonObject, key: String) {
            val signature = Base64.getDecoder().decode(value.text("signature"))
            require(signature.size == 64) { "Invalid signature size" }
            val message = bytes(kind, value)
            require(Ed25519Signer().apply {
                init(false, Ed25519PublicKeyParameters(unhex(key), 0))
                update(message, 0, message.size)
            }.verifySignature(signature)) { "Invalid naming signature" }
        }
        private fun date(value: JsonObject, field: String) = Instant.parse(value.text(field)).toEpochMilli()
        private fun requireSubject(s: String) = require(s.matches(Regex("onym:key:[a-f0-9]{64}"))) { "Invalid naming subject" }
    }
    private suspend fun call(path: String, body: JsonObject? = null): JsonObject = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(BASE + path).apply {
            if (body != null) post(body.toString().toRequestBody("application/json".toMediaType()))
        }.build()
        http.newCall(request).execute().use { response ->
            val stream = requireNotNull(response.body).byteStream()
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            while (output.size() <= 131072) {
                val count = stream.read(buffer)
                if (count < 0) break
                output.write(buffer, 0, count)
            }
            val data = output.toByteArray()
            require(data.size <= 131072) { "Naming response too large" }
            val result = json.parseToJsonElement(data.toString(Charsets.UTF_8)).jsonObject
            require(response.isSuccessful) { "BSN: ${result["error"]?.jsonPrimitive?.content ?: response.code}" }
            result
        }
    }
    suspend fun manifest(): JsonObject {
        val m = call("manifest.json")
        verify("manifest", m, trustKey)
        require(m.text("registry") == REGISTRY && m.text("namespace") == NAMESPACE &&
            m["trustRoot"]!!.jsonObject.text("publicKey") == trustKey &&
            m.text("implementationProfileId") == "onym:naming-implementation:bsn-stellar-http-v1") { "Naming provider changed" }
        return m
    }
    /** Also used by fixture tests. The caller chooses a pinned key, never the response. */
    fun verifyResolution(response: JsonObject, expectedSubject: String, policy: String): List<Name> {
        requireSubject(expectedSubject)
        verify("resolution", response, trustKey)
        val now = clock()
        val checked = date(response, "checkedAt")
        val until = date(response, "expiresAt")
        require(response.number("version") == 1L && response.text("registry") == REGISTRY &&
            response.text("query") == expectedSubject && checked <= now + 5000 && now - checked <= 60000 &&
            until > now && until - checked in 1..60000) { "Stale or unrelated naming response" }
        val rows = response["records"]!!.jsonArray
        require(rows.size <= 20)
        return rows.mapNotNull { element ->
            val row = element.jsonObject
            val record = row["record"]!!.jsonObject
            verify("record", record, trustKey)
            require(record.number("recordVersion") == 1L && record.text("registry") == REGISTRY &&
                record.text("namespace") == NAMESPACE && record.text("subject") == expectedSubject &&
                record.text("policy") == policy && record.text("network") == "public" &&
                record.text("name") == record.text("stellarAccount") &&
                record.text("name").matches(Regex("G[A-Z2-7]{55}")) &&
                record["scope"] == buildJsonArray { add("display-name") } &&
                record.number("sequence") > 0 && date(record, "issuedAt") <= now + 5000) { "Invalid name record" }
            val name = record.text("displayName")
            require(name.isNotBlank() && name.toByteArray(Charsets.UTF_8).size <= 64 &&
                name.codePoints().noneMatch { Character.getType(it) in listOf(Character.CONTROL.toInt(), Character.FORMAT.toInt(), Character.SURROGATE.toInt()) }) { "Unsafe display name" }
            val status = row.text("status")
            require(status in setOf("active", "unaccepted", "expired", "superseded", "revoked", "disavowed"))
            if (status != "active") return@mapNotNull null
            val accepted = row["acceptance"]!!.jsonObject
            verify("acceptance", accepted, expectedSubject.removePrefix("onym:key:"))
            require(accepted.number("acceptanceVersion") == 1L && accepted.text("subject") == expectedSubject &&
                accepted.text("record") == digest(record) && accepted["publish"] == JsonPrimitive(true) &&
                date(accepted, "acceptedAt") <= now + 5000 && date(accepted, "expiresAt") <= date(record, "expiresAt") &&
                date(accepted, "expiresAt") > now && date(record, "expiresAt") > now &&
                row["disavowal"] == JsonNull && row["supersededBy"] == JsonNull) { "Name lacks current holder acceptance" }
            Name(record, minOf(until, date(record, "expiresAt"), date(accepted, "expiresAt")))
        }
    }
    suspend fun resolve(subject: String): Name? {
        val m = manifest()
        return verifyResolution(call("v1/resolve-subject", buildJsonObject { put("subject", subject) }), subject, m.text("policy"))
            .maxByOrNull { it.record.number("sequence") }
    }
    private suspend fun signed(kind: String, body: JsonObject, signer: suspend (ByteArray) -> ByteArray): JsonObject =
        JsonObject(body + ("signature" to JsonPrimitive(Base64.getEncoder().encodeToString(signer(bytes(kind, body))))))
    private suspend fun request(operation: String, subject: String, fields: JsonObject, signer: suspend (ByteArray) -> ByteArray): JsonObject {
        requireSubject(subject)
        val now = clock()
        val body = buildJsonObject {
            fields.forEach { (k, v) -> put(k, v) }
            put("requestVersion", 1); put("operation", operation); put("audience", BASE); put("subject", subject)
            put("issuedAt", now); put("expiresAt", now + 120000)
            put("nonce", hex(ByteArray(16).also { SecureRandom().nextBytes(it) }))
        }
        return call("v1/$operation", signed("request", body, signer))
    }
    suspend fun offer(account: String, subject: String, signer: suspend (ByteArray) -> ByteArray): JsonObject {
        require(account.matches(Regex("G[A-Z2-7]{55}"))) { "Enter a Stellar account" }
        val m = manifest()
        val r = request("request-issuance", subject, buildJsonObject { put("stellarAccount", account) }, signer)
        verifyResolution(r, subject, m.text("policy"))
        val row = r["records"]!!.jsonArray.single().jsonObject
        val record = row["record"]!!.jsonObject
        require(row.text("status") == "unaccepted" && record.text("stellarAccount") == account && date(record, "expiresAt") > clock())
        return record
    }
    suspend fun accept(record: JsonObject, subject: String, signer: suspend (ByteArray) -> ByteArray): Name {
        verify("record", record, trustKey)
        require(record.text("subject") == subject)
        val acceptance = signed("acceptance", buildJsonObject {
            put("acceptanceVersion", 1); put("record", digest(record)); put("subject", subject); put("publish", true)
            put("acceptedAt", Instant.ofEpochMilli(clock()).toString()); put("expiresAt", record.text("expiresAt"))
        }, signer)
        val m = manifest()
        val r = request("accept-record", subject, buildJsonObject { put("record", digest(record)); put("acceptance", acceptance) }, signer)
        return verifyResolution(r, subject, m.text("policy")).single { digest(it.record) == digest(record) }
    }
    suspend fun disavow(record: JsonObject, subject: String, signer: suspend (ByteArray) -> ByteArray) {
        val disavowal = signed("disavowal", buildJsonObject {
            put("disavowalVersion", 1); put("record", digest(record)); put("subject", subject)
            put("effectiveFrom", Instant.ofEpochMilli(clock()).toString())
        }, signer)
        val m = manifest()
        val r = request("disavow-record", subject, buildJsonObject { put("record", digest(record)); put("disavowal", disavowal) }, signer)
        verifyResolution(r, subject, m.text("policy"))
        require(r["records"]!!.jsonArray.any { it.jsonObject.text("status") == "disavowed" && digest(it.jsonObject["record"]!!.jsonObject) == digest(record) })
    }
}
internal fun JsonObject.text(key: String): String = getValue(key).jsonPrimitive.let { require(it.isString); it.content }
internal fun JsonObject.number(key: String): Long = getValue(key).jsonPrimitive.let { require(!it.isString); it.long }
