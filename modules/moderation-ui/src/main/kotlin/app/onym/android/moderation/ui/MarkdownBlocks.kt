package app.onym.android.moderation.ui

/**
 * Minimal markdown model + parser for authority policy documents —
 * the Android analogue of iOS `MarkdownBlock`, which leans on
 * Foundation's parser; Android has none, so this parses the subset
 * the published policy documents actually use: ATX headings, bullet
 * and ordered lists, block quotes, fenced code, dividers, pipe
 * tables (including the `| | |` empty-header form), and the inline
 * set (bold, italic, inline code, links).
 *
 * Pure Kotlin, no Compose types: the composables map [MdInline] runs
 * to AnnotatedString; this file stays JVM-unit-testable. The failure
 * posture mirrors iOS: unparseable input is still readable input —
 * an unrecognized line is a paragraph, never dropped.
 */
sealed interface MdBlock {
    data class Heading(val level: Int, val inlines: List<MdInline>) : MdBlock
    data class Paragraph(val inlines: List<MdInline>) : MdBlock
    data class ListItem(val ordinal: Int?, val inlines: List<MdInline>) : MdBlock
    data class Quote(val inlines: List<MdInline>) : MdBlock
    data class CodeBlock(val text: String) : MdBlock
    data object Divider : MdBlock

    /** Rows of cells; a first row whose cells are all empty (the
     * `| | |` header syntax) is dropped, matching iOS. */
    data class Table(val rows: List<List<List<MdInline>>>) : MdBlock
}

sealed interface MdInline {
    data class Text(val text: String) : MdInline
    data class Bold(val text: String) : MdInline
    data class Italic(val text: String) : MdInline
    data class Code(val text: String) : MdInline
    data class Link(val text: String, val url: String) : MdInline
}

object MarkdownBlocks {

    fun parse(markdown: String): List<MdBlock> {
        val blocks = mutableListOf<MdBlock>()
        val lines = markdown.lines()
        var i = 0
        val paragraph = StringBuilder()

        fun flushParagraph() {
            if (paragraph.isNotBlank()) {
                blocks.add(MdBlock.Paragraph(inlines(paragraph.toString().trim())))
            }
            paragraph.setLength(0)
        }

        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trim()
            when {
                trimmed.isEmpty() -> flushParagraph()

                trimmed.startsWith("```") -> {
                    flushParagraph()
                    val code = StringBuilder()
                    i += 1
                    while (i < lines.size && !lines[i].trim().startsWith("```")) {
                        code.appendLine(lines[i])
                        i += 1
                    }
                    blocks.add(MdBlock.CodeBlock(code.toString().trimEnd('\n')))
                }

                HEADING.matches(trimmed) -> {
                    flushParagraph()
                    val (hashes, text) = HEADING.find(trimmed)!!.destructured
                    blocks.add(MdBlock.Heading(hashes.length, inlines(text.trim())))
                }

                DIVIDER.matches(trimmed) -> {
                    flushParagraph()
                    blocks.add(MdBlock.Divider)
                }

                trimmed.startsWith(">") -> {
                    flushParagraph()
                    // Consecutive quote lines are one quote block.
                    val quote = StringBuilder()
                    while (i < lines.size && lines[i].trim().startsWith(">")) {
                        quote.append(lines[i].trim().removePrefix(">").trim()).append(' ')
                        i += 1
                    }
                    i -= 1
                    blocks.add(MdBlock.Quote(inlines(quote.toString().trim())))
                }

                BULLET.matches(trimmed) -> {
                    flushParagraph()
                    blocks.add(
                        MdBlock.ListItem(
                            ordinal = null,
                            inlines = inlines(BULLET.find(trimmed)!!.groupValues[1]),
                        ),
                    )
                }

                ORDERED.matches(trimmed) -> {
                    flushParagraph()
                    val match = ORDERED.find(trimmed)!!
                    blocks.add(
                        MdBlock.ListItem(
                            ordinal = match.groupValues[1].toIntOrNull(),
                            inlines = inlines(match.groupValues[2]),
                        ),
                    )
                }

                trimmed.startsWith("|") -> {
                    flushParagraph()
                    val rows = mutableListOf<List<List<MdInline>>>()
                    while (i < lines.size && lines[i].trim().startsWith("|")) {
                        val row = lines[i].trim()
                        // The |---|---| alignment row is syntax, not data.
                        if (!TABLE_RULE.matches(row)) {
                            rows.add(tableCells(row).map { inlines(it) })
                        }
                        i += 1
                    }
                    i -= 1
                    // Empty-header form: a first row of blank cells is
                    // layout syntax, not content.
                    val cleaned = if (
                        rows.isNotEmpty() &&
                        rows.first().all { cell -> cell.all { it is MdInline.Text && it.text.isBlank() } }
                    ) {
                        rows.drop(1)
                    } else {
                        rows
                    }
                    if (cleaned.isNotEmpty()) blocks.add(MdBlock.Table(cleaned))
                }

                else -> {
                    if (paragraph.isNotEmpty()) paragraph.append(' ')
                    paragraph.append(trimmed)
                }
            }
            i += 1
        }
        flushParagraph()
        return blocks
    }

    /** `./evidence-rules#anchor` against `https://host/policy/csam` →
     * `https://host/policy/evidence-rules#anchor`. Absolute links pass
     * through; a bare `#anchor` resolves to the document itself. */
    fun resolveLink(href: String, documentUrl: String): String = try {
        java.net.URI(documentUrl).resolve(href).toString()
    } catch (_: Exception) {
        href
    }

    /**
     * Whether a tapped link is the root document's own authority
     * speaking — a web URL on the same host — and may render inside
     * the in-app viewer's chrome. Everything else goes to the system
     * browser, where the address is visible (iOS `followsInApp`).
     */
    fun followsInApp(destination: String, rootUrl: String): Boolean = try {
        val dest = java.net.URI(destination)
        val root = java.net.URI(rootUrl)
        (dest.scheme == "https" || dest.scheme == "http") &&
            dest.host != null &&
            dest.host.equals(root.host, ignoreCase = true)
    } catch (_: Exception) {
        false
    }

    /** A followed link has no display title; the last path segment's
     * words are the best available ("evidence-rules" → "Evidence rules"). */
    fun impliedTitle(url: String): String {
        val slug = url.substringBefore('#').trimEnd('/')
            .substringAfterLast('/')
            .substringBefore('.')
            .replace('-', ' ')
        return slug.replaceFirstChar { it.uppercase() }
    }

    private fun tableCells(row: String): List<String> =
        row.trim().removePrefix("|").removeSuffix("|").split('|').map { it.trim() }

    /** Inline parse: links, bold, italic, code — first match wins,
     * scanning left to right; no nesting (the policy corpus doesn't
     * nest, and unmatched markers render as literal text). */
    fun inlines(text: String): List<MdInline> {
        val out = mutableListOf<MdInline>()
        var rest = text
        while (rest.isNotEmpty()) {
            val match = INLINE.find(rest)
            if (match == null) {
                out.add(MdInline.Text(rest))
                break
            }
            if (match.range.first > 0) {
                out.add(MdInline.Text(rest.substring(0, match.range.first)))
            }
            val token = match.value
            out.add(
                when {
                    token.startsWith("[") -> {
                        val link = LINK.find(token)!!
                        MdInline.Link(link.groupValues[1], link.groupValues[2])
                    }
                    token.startsWith("**") -> MdInline.Bold(token.removeSurrounding("**"))
                    token.startsWith("`") -> MdInline.Code(token.removeSurrounding("`"))
                    else -> MdInline.Italic(token.removeSurrounding("*"))
                },
            )
            rest = rest.substring(match.range.last + 1)
        }
        return out
    }

    private val HEADING = Regex("^(#{1,6})\\s+(.*)$")
    private val DIVIDER = Regex("^(-{3,}|\\*{3,}|_{3,})$")
    private val BULLET = Regex("^[-*+]\\s+(.*)$")
    private val ORDERED = Regex("^(\\d{1,3})[.)]\\s+(.*)$")
    private val TABLE_RULE = Regex("^\\|?\\s*:?-{3,}:?\\s*(\\|\\s*:?-{3,}:?\\s*)*\\|?$")
    private val LINK = Regex("\\[([^\\]]*)]\\(([^)]+)\\)")
    private val INLINE = Regex(
        "\\[[^\\]]*]\\([^)]+\\)|\\*\\*[^*]+\\*\\*|`[^`]+`|\\*[^*\\s][^*]*\\*",
    )
}
