package app.onym.android.moderation

import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/** What a policy address served. */
sealed interface PolicyDocument {
    /** Markdown (or at least plain text) — renderable in-app. */
    data class Markdown(val text: String) : PolicyDocument

    /** The address answered a web page, not a document: the caller
     * hands off to the browser instead of pretending to render it. */
    data object WebPage : PolicyDocument
}

/**
 * Fetches an authority's policy document (a violation-class
 * definition, evidence rules, …) for the in-app markdown viewer.
 * Display-path only: nothing fetched here is consented to or pinned —
 * the manifest's `definition` VALUE (the address) is inside the
 * hashed bytes; what the address serves today is presentation.
 */
interface PolicyDocumentFetcher {
    /** Throws [ModerationConsentException]-style failures on
     * unreachable/oversized/non-2xx; answers [PolicyDocument.WebPage]
     * for an HTML response. */
    suspend fun fetch(url: String): PolicyDocument
}

class OkHttpPolicyDocumentFetcher(
    private val httpClient: OkHttpClient,
) : PolicyDocumentFetcher {
    override suspend fun fetch(url: String): PolicyDocument = withContext(Dispatchers.IO) {
        if (!url.startsWith("https://")) {
            throw ModerationConsentException("policy document URL must use https: $url")
        }
        val request = Request.Builder().url(url).get()
            .header("Accept", "text/markdown, text/plain;q=0.9, */*;q=0.1")
            .build()
        val (contentType, bytes) = try {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw ModerationConsentException(
                        "policy document fetch failed: HTTP ${response.code}",
                    )
                }
                val body = response.body
                    ?: throw ModerationConsentException("policy document fetch returned no body")
                if (body.contentLength() > MAX_DOCUMENT_BYTES) {
                    throw ModerationConsentException("policy document exceeds the size cap")
                }
                val raw = body.source().let { source ->
                    // contentLength can be -1 (chunked); enforce the
                    // cap on the actual bytes either way.
                    source.request(MAX_DOCUMENT_BYTES + 1)
                    if (source.buffer.size > MAX_DOCUMENT_BYTES) {
                        throw ModerationConsentException("policy document exceeds the size cap")
                    }
                    source.buffer.readByteArray()
                }
                // Header first, body's parsed MediaType as fallback —
                // they can disagree on which is populated.
                val declaredType = response.header("Content-Type")
                    ?: body.contentType()?.let { "${it.type}/${it.subtype}" }
                    ?: ""
                declaredType to raw
            }
        } catch (e: IOException) {
            throw ModerationConsentException("policy document unreachable", e)
        }

        val text = bytes.decodeToString()
        // An HTML answer is a web page whatever the header says, and a
        // text/html header is a web page whatever the body looks like.
        val looksLikeHtml = text.trimStart().let {
            it.startsWith("<!DOCTYPE", ignoreCase = true) ||
                it.startsWith("<html", ignoreCase = true)
        }
        if (looksLikeHtml || contentType.substringBefore(';').trim().equals("text/html", true)) {
            PolicyDocument.WebPage
        } else {
            PolicyDocument.Markdown(text)
        }
    }

    private companion object {
        /** Policy documents are a few KiB; half a MiB is generous. */
        const val MAX_DOCUMENT_BYTES = 512L * 1024
    }
}
