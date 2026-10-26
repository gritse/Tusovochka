package smithereen.mcp;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class HtmlToMarkdownTest {
	@Test
	public void inlineFormatting() {
		assertEquals("Hello **world** and *you*, ~~not~~ `code`.", HtmlToMarkdown.convert("<p>Hello <b>world</b> and <i>you</i>, <s>not</s> <code>code</code>.</p>"));
		assertEquals("**Графит** is a mineral", HtmlToMarkdown.convert("<p><strong>Графит</strong> is a mineral</p>"));
		// Emphasis markers never touch a space
		assertEquals("a **b** c", HtmlToMarkdown.convert("a<b> b </b>c"));
	}

	@Test
	public void paragraphsAndLineBreaks() {
		assertEquals("first\nsecond\n\nthird", HtmlToMarkdown.convert("<p>first<br/>second</p><p>third</p>"));
		assertEquals("one\n\ntwo", HtmlToMarkdown.convert("<p>one </p>\n<p>two</p>\n"));
		assertEquals("plain text", HtmlToMarkdown.convert("plain text"));
		assertEquals("", HtmlToMarkdown.convert(""));
	}

	@Test
	public void linksAndMentions() {
		assertEquals("see [the docs](https://example.com/docs) now", HtmlToMarkdown.convert("<p>see <a href=\"https://example.com/docs\">the docs</a> now</p>"));
		assertEquals("<https://example.com>", HtmlToMarkdown.convert("<a href=\"https://example.com\">https://example.com</a>"));
		assertEquals("hi @alice!", HtmlToMarkdown.convert("<p>hi <a href=\"https://x/alice\" class=\"mention\" data-user-id=\"3\">@alice</a>!</p>"));
	}

	@Test
	public void blocks() {
		assertEquals("- one\n- two **bold**", HtmlToMarkdown.convert("<ul><li>one</li><li>two <b>bold</b></li></ul>"));
		assertEquals("1. a\n2. b", HtmlToMarkdown.convert("<ol><li>a</li><li>b</li></ol>"));
		assertEquals("> quoted\n> text\n\nafter", HtmlToMarkdown.convert("<blockquote><p>quoted<br>text</p></blockquote><p>after</p>"));
		assertEquals("```\nint x = 1;\n```", HtmlToMarkdown.convert("<pre><code>int x = 1;\n</code></pre>"));
		assertEquals("## Title\n\nbody", HtmlToMarkdown.convert("<h2>Title</h2><p>body</p>"));
	}
}
