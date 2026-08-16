package app.onym.android.moderation

import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class PolicyDocumentFetcherTest {

    private fun client(status: Int, body: String, contentType: String = "text/markdown") =
        OkHttpPolicyDocumentFetcher(
            OkHttpClient.Builder()
                .addInterceptor(
                    Interceptor { chain ->
                        Response.Builder()
                            .request(chain.request())
                            .protocol(Protocol.HTTP_1_1)
                            .code(status)
                            .message("canned")
                            .body(body.toResponseBody(contentType.toMediaType()))
                            .build()
                    },
                )
                .build(),
        )

    @Test
    fun `markdown comes back as a document`() = runTest {
        val document = client(200, "# Title\n\nBody.")
            .fetch("https://authority.example/policy/csam")
        assertEquals(PolicyDocument.Markdown("# Title\n\nBody."), document)
    }

    /** An HTML answer is a web page whatever the header says — the
     * viewer hands off to the browser instead of pretending. */
    @Test
    fun `html answers are web pages by body or by header`() = runTest {
        assertEquals(
            PolicyDocument.WebPage,
            client(200, "<!DOCTYPE html><html>…</html>", contentType = "text/markdown")
                .fetch("https://a.example/p"),
        )
        assertEquals(
            PolicyDocument.WebPage,
            client(200, "surprisingly not markdown", contentType = "text/html; charset=utf-8")
                .fetch("https://a.example/p"),
        )
    }

    @Test
    fun `a non-2xx refuses`() = runTest {
        try {
            client(404, "gone").fetch("https://a.example/p")
            fail("404 must throw")
        } catch (e: ModerationConsentException) {
            assertTrue(e.message!!.contains("404"))
        }
    }

    @Test
    fun `plain http refuses before any request`() = runTest {
        try {
            client(200, "x").fetch("http://a.example/p")
            fail("http must refuse")
        } catch (e: ModerationConsentException) {
            assertTrue(e.message!!.contains("https"))
        }
    }

    @Test
    fun `oversized documents refuse`() = runTest {
        try {
            client(200, "x".repeat(600 * 1024)).fetch("https://a.example/p")
            fail("oversize must throw")
        } catch (e: ModerationConsentException) {
            assertTrue(e.message!!.contains("size cap"))
        }
    }
}
