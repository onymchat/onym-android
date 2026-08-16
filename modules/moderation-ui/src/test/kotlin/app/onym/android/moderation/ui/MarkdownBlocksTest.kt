package app.onym.android.moderation.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The markdown subset the authority's published policy documents use,
 * pinned block by block — plus the link-resolution and in-app-follow
 * rules the viewer's trust story depends on. The parser's failure
 * posture is iOS's: unparseable input is still readable input.
 */
class MarkdownBlocksTest {

    @Test
    fun `headings paragraphs and inline styles parse`() {
        val blocks = MarkdownBlocks.parse(
            """
            # Child sexual abuse material (`csam`)

            **Violation.** The material depicts *anything* under `code`.
            """.trimIndent(),
        )
        assertEquals(2, blocks.size)
        val heading = blocks[0] as MdBlock.Heading
        assertEquals(1, heading.level)
        assertTrue(heading.inlines.any { it is MdInline.Code && it.text == "csam" })

        val paragraph = blocks[1] as MdBlock.Paragraph
        assertEquals(MdInline.Bold("Violation."), paragraph.inlines.first())
        assertTrue(paragraph.inlines.any { it is MdInline.Italic && it.text == "anything" })
        assertTrue(paragraph.inlines.any { it is MdInline.Code && it.text == "code" })
    }

    /** Adjacent lines join into one paragraph; a blank line splits. */
    @Test
    fun `hard-wrapped paragraphs rejoin`() {
        val blocks = MarkdownBlocks.parse("one line\nsame paragraph\n\nnext paragraph")
        assertEquals(2, blocks.size)
        assertEquals(
            listOf(MdInline.Text("one line same paragraph")),
            (blocks[0] as MdBlock.Paragraph).inlines,
        )
    }

    @Test
    fun `bullet and ordered lists parse`() {
        val blocks = MarkdownBlocks.parse("- first\n- second\n1. one\n2) two")
        val items = blocks.filterIsInstance<MdBlock.ListItem>()
        assertEquals(4, items.size)
        assertEquals(null, items[0].ordinal)
        assertEquals(1, items[2].ordinal)
        assertEquals(2, items[3].ordinal)
    }

    /** The published Terms tables use the `| | |` empty-header form:
     * the alignment row is syntax and the blank header is dropped —
     * data rows survive with their cells. */
    @Test
    fun `empty-header pipe table drops header keeps rows`() {
        val blocks = MarkdownBlocks.parse(
            """
            | | |
            |---|---|
            | Response window | 3 days |
            | Ban term | 365 days |
            """.trimIndent(),
        )
        val table = blocks.single() as MdBlock.Table
        assertEquals(2, table.rows.size)
        assertEquals(listOf(MdInline.Text("Response window")), table.rows[0][0])
        assertEquals(listOf(MdInline.Text("3 days")), table.rows[0][1])
    }

    @Test
    fun `quotes code fences and dividers parse`() {
        val blocks = MarkdownBlocks.parse(
            "> quoted words\n> continue\n\n```\nlet x = 1\n```\n\n---",
        )
        assertEquals(
            listOf(MdInline.Text("quoted words continue")),
            (blocks[0] as MdBlock.Quote).inlines,
        )
        assertEquals("let x = 1", (blocks[1] as MdBlock.CodeBlock).text)
        assertEquals(MdBlock.Divider, blocks[2])
    }

    @Test
    fun `links parse with text and target`() {
        val paragraph = MarkdownBlocks.parse(
            "[What these windows mean](./evidence-rules#what-the-windows-mean).",
        ).single() as MdBlock.Paragraph
        assertEquals(
            MdInline.Link("What these windows mean", "./evidence-rules#what-the-windows-mean"),
            paragraph.inlines.first(),
        )
        assertEquals(MdInline.Text("."), paragraph.inlines.last())
    }

    /** iOS posture: unparseable input is still readable input — an
     * unrecognized line is a paragraph, never dropped. */
    @Test
    fun `arbitrary text degrades to paragraphs`() {
        val blocks = MarkdownBlocks.parse("]]] not markdown ***")
        assertTrue(blocks.single() is MdBlock.Paragraph)
    }

    // ─── Link resolution + the in-app follow privilege ───────────────

    /** Policy documents cross-reference each other relatively; a link
     * with no base is parsed but dead. */
    @Test
    fun `relative links resolve against the document`() {
        assertEquals(
            "https://authority.onym.app/policy/evidence-rules#windows",
            MarkdownBlocks.resolveLink(
                "./evidence-rules#windows",
                "https://authority.onym.app/policy/csam",
            ),
        )
        assertEquals(
            "https://elsewhere.example/x",
            MarkdownBlocks.resolveLink("https://elsewhere.example/x", "https://authority.onym.app/policy/csam"),
        )
    }

    /** In-app follow is a same-host privilege: the viewer's chrome
     * says "this is the authority's document" and shows no address,
     * so another domain must not render inside it. */
    @Test
    fun `only same-host web links follow in-app`() {
        val root = "https://authority.onym.app/policy/csam"
        assertTrue(MarkdownBlocks.followsInApp("https://authority.onym.app/policy/evidence-rules", root))
        assertTrue(!MarkdownBlocks.followsInApp("https://evil.example/policy", root))
        assertTrue(!MarkdownBlocks.followsInApp("mailto:abuse@onym.app", root))
        assertTrue(!MarkdownBlocks.followsInApp("not a url", root))
    }

    @Test
    fun `implied titles come from the last path segment`() {
        assertEquals(
            "Evidence rules",
            MarkdownBlocks.impliedTitle("https://authority.onym.app/policy/evidence-rules#anchor"),
        )
    }

    /** The real corpus, end to end: the served csam policy's shapes
     * all land in typed blocks, nothing dropped to noise. */
    @Test
    fun `the published policy document shape parses fully`() {
        val blocks = MarkdownBlocks.parse(
            """
            # Child sexual abuse material (`csam`)

            This is the rule a decision is made against.

            **Violation.** The authenticated material depicts.

            ## Terms

            | | |
            |---|---|
            | Response window | 3 days |
            | Appeal | 30 days, non-suspensive |

            [What these windows mean](./evidence-rules#what-the-windows-mean).

            ## Why 365 days and not permanent

            The reference policy permits a permanent ban.
            """.trimIndent(),
        )
        assertEquals(3, blocks.count { it is MdBlock.Heading })
        assertEquals(1, blocks.count { it is MdBlock.Table })
        assertTrue(
            blocks.filterIsInstance<MdBlock.Paragraph>()
                .any { p -> p.inlines.any { it is MdInline.Link } },
        )
    }
}
