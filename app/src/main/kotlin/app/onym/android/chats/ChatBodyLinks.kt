package app.onym.android.chats

/**
 * One detected web link inside a message body: the exact character
 * range to style, and the URL to open (normalized — a scheme-less
 * `www.` link opens as https).
 */
data class BodyLink(
    val range: IntRange,
    val url: String,
)

/**
 * Web-link detection for chat message bodies. Pure Kotlin (no
 * `android.util.Patterns`) so the exact ranges are pinned by plain
 * JVM tests: a wrong range styles half a word as tappable, and an
 * over-eager pattern turns prose into links.
 *
 * Deliberately narrow: `http://`, `https://`, and bare `www.` hosts
 * only. No custom schemes (a chat message must not become an intent
 * launcher), no bare domains without either marker ("onym.app" in
 * prose stays prose — the cost of linking it is matching every
 * "file.txt"), no emails.
 */
object ChatBodyLinks {

    /** Trailing characters that are far more likely sentence
     * punctuation than part of the URL. A closing paren is kept only
     * when the URL body contains its opener (Wikipedia-style paths). */
    private const val TRAILING = ".,;:!?…'\"»”’"

    // Negative lookbehind instead of \b: a word boundary let `www.`
    // link from inside an email (`bob@www.example.com`) and from
    // inside a non-web scheme's authority (`intent://www.evil.com`) —
    // the guards the tests promise. No preceding @, /, or word char.
    private val CANDIDATE = Regex(
        """(?i)(?<![@/\w])((?:https?://|www\.)[^\s<>«»“”]+)""",
    )

    fun detect(body: String): List<BodyLink> =
        CANDIDATE.findAll(body).mapNotNull { match ->
            var text = match.value
            // Trim sentence punctuation, then unbalanced closers, and
            // repeat: "see https://x.y/z)." sheds both. Bracket
            // balances are counted ONCE and decremented — recounting
            // inside the loop made trimming O(n²) on a pathological
            // tail of closers.
            var openParens = text.count { it == '(' }
            var closeParens = text.count { it == ')' }
            var openBrackets = text.count { it == '[' }
            var closeBrackets = text.count { it == ']' }
            while (text.isNotEmpty()) {
                val last = text.last()
                when {
                    last in TRAILING -> text = text.dropLast(1)
                    last == ')' && openParens < closeParens -> {
                        text = text.dropLast(1)
                        closeParens -= 1
                    }
                    last == ']' && openBrackets < closeBrackets -> {
                        text = text.dropLast(1)
                        closeBrackets -= 1
                    }
                    else -> break
                }
            }
            // A bare marker with no host left is prose, not a link.
            // substringAfter, not removePrefix: the match is (?i), so
            // MiXeD-case schemes must strip too — and the www guard
            // must be ignoreCase for the same reason (a lone "WWW."
            // escaped the case-sensitive check as a dead link).
            val hostPart = text.substringAfter("://", text)
            if (hostPart.isEmpty() || !hostPart.contains('.') ||
                hostPart.startsWith("www.", ignoreCase = true) && hostPart.length <= 4
            ) {
                return@mapNotNull null
            }
            val start = match.range.first
            BodyLink(
                range = start until start + text.length,
                // The DISPLAY range keeps the author's spelling; the
                // URL normalizes the scheme to lowercase — Android
                // resolves intent filters case-sensitively against
                // lowercase schemes, so "HtTpS://…" would be a
                // tappable link that opens nothing.
                url = when {
                    text.startsWith("www.", ignoreCase = true) -> "https://$text"
                    else -> {
                        val scheme = text.substringBefore("://")
                        scheme.lowercase() + "://" + text.substringAfter("://")
                    }
                },
            )
        }.toList()
}
