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
            // repeat: "see https://x.y/z)." sheds both.
            while (text.isNotEmpty()) {
                val last = text.last()
                text = when {
                    last in TRAILING -> text.dropLast(1)
                    last == ')' && text.count { it == '(' } < text.count { it == ')' } ->
                        text.dropLast(1)
                    last == ']' && text.count { it == '[' } < text.count { it == ']' } ->
                        text.dropLast(1)
                    else -> break
                }
            }
            // A bare marker with no host left is prose, not a link.
            // substringAfter, not removePrefix: the match is (?i), so
            // MiXeD-case schemes must strip too.
            val hostPart = text.substringAfter("://", text)
            if (hostPart.isEmpty() || !hostPart.contains('.') ||
                hostPart.startsWith("www.") && hostPart.length <= 4
            ) {
                return@mapNotNull null
            }
            val start = match.range.first
            BodyLink(
                range = start until start + text.length,
                url = if (text.startsWith("www.", ignoreCase = true)) {
                    "https://$text"
                } else {
                    text
                },
            )
        }.toList()
}
