package smithereen.mcp;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;

/**
 * Converts the HTML the server stores for posts, comments and messages back into Markdown, so that an agent sees the
 * formatting a human sees and can reproduce it when editing. Covers what {@link smithereen.text.TextProcessor} emits:
 * paragraphs, line breaks, bold, italic, strikethrough, links and mentions, code, lists, quotes and headings.
 */
public final class HtmlToMarkdown {
	private HtmlToMarkdown() {}

	public static String convert(String html) {
		StringBuilder sb = new StringBuilder();
		render(Jsoup.parseBodyFragment(html).body(), sb, false);
		return normalize(sb.toString());
	}

	private static void render(Node node, StringBuilder sb, boolean preformatted) {
		if (node instanceof TextNode text) {
			sb.append(preformatted ? text.getWholeText() : collapseWhitespace(text.getWholeText()));
			return;
		}
		if (!(node instanceof Element el))
			return;
		switch (el.normalName()) {
			case "p", "div" -> {
				blankLine(sb);
				children(el, sb, preformatted);
				blankLine(sb);
			}
			case "br" -> sb.append('\n');
			case "b", "strong" -> wrap(el, sb, "**", preformatted);
			case "i", "em" -> wrap(el, sb, "*", preformatted);
			case "s", "del", "strike" -> wrap(el, sb, "~~", preformatted);
			case "code" -> {
				if (preformatted)
					children(el, sb, true);
				else
					wrap(el, sb, "`", false);
			}
			case "pre" -> {
				blankLine(sb);
				sb.append("```\n");
				StringBuilder inner = new StringBuilder();
				children(el, inner, true);
				sb.append(inner.toString().strip()).append("\n```");
				blankLine(sb);
			}
			case "a" -> {
				StringBuilder inner = new StringBuilder();
				children(el, inner, preformatted);
				String text = inner.toString().strip();
				String href = el.attr("href");
				if (el.hasClass("mention") || href.isEmpty()) {
					sb.append(text);
				} else if (text.isEmpty() || text.equals(href)) {
					sb.append('<').append(href).append('>');
				} else {
					sb.append('[').append(text).append("](").append(href).append(')');
				}
			}
			case "ul", "ol" -> {
				blankLine(sb);
				boolean ordered = el.normalName().equals("ol");
				int n = 1;
				for (Element li : el.children()) {
					if (!li.normalName().equals("li"))
						continue;
					StringBuilder inner = new StringBuilder();
					children(li, inner, preformatted);
					sb.append(ordered ? n++ + ". " : "- ").append(normalize(inner.toString()).replace("\n", "\n  ")).append('\n');
				}
				blankLine(sb);
			}
			case "blockquote" -> {
				blankLine(sb);
				StringBuilder inner = new StringBuilder();
				children(el, inner, preformatted);
				for (String line : normalize(inner.toString()).split("\n"))
					sb.append("> ").append(line).append('\n');
				blankLine(sb);
			}
			case "h1", "h2", "h3", "h4", "h5", "h6" -> {
				blankLine(sb);
				sb.append("#".repeat(el.normalName().charAt(1) - '0')).append(' ');
				children(el, sb, preformatted);
				blankLine(sb);
			}
			default -> children(el, sb, preformatted);
		}
	}

	private static void children(Element el, StringBuilder sb, boolean preformatted) {
		for (Node child : el.childNodes())
			render(child, sb, preformatted);
	}

	private static void wrap(Element el, StringBuilder sb, String marker, boolean preformatted) {
		StringBuilder inner = new StringBuilder();
		children(el, inner, preformatted);
		String text = inner.toString();
		String stripped = text.strip();
		if (stripped.isEmpty()) {
			sb.append(text);
			return;
		}
		// Markdown emphasis can not start or end with a space, so the spaces move outside the markers
		if (!text.isEmpty() && Character.isWhitespace(text.charAt(0)))
			sb.append(' ');
		sb.append(marker).append(stripped).append(marker);
		if (Character.isWhitespace(text.charAt(text.length() - 1)))
			sb.append(' ');
	}

	private static void blankLine(StringBuilder sb) {
		if (sb.isEmpty())
			return;
		int end = sb.length();
		while (end > 0 && sb.charAt(end - 1) == ' ')
			end--;
		sb.setLength(end);
		if (sb.isEmpty())
			return;
		if (sb.charAt(sb.length() - 1) != '\n')
			sb.append("\n\n");
		else if (sb.length() < 2 || sb.charAt(sb.length() - 2) != '\n')
			sb.append('\n');
	}

	private static String collapseWhitespace(String s) {
		return s.replaceAll("[ \\t\\r\\n]+", " ");
	}

	/** Trailing spaces off every line, at most one blank line in a row, nothing around the edges. */
	static String normalize(String s) {
		return s.replaceAll("[ \\t]+\n", "\n").replaceAll("\n{3,}", "\n\n").strip();
	}
}
