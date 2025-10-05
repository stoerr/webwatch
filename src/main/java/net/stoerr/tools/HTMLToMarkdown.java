// Adding a new helper that fetches HTML and converts it into a best-effort markdown representation.
package net.stoerr.tools;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Attribute;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;

import java.io.IOException;
import java.util.List;

public class HTMLToMarkdown {

    // Fetches the URL, strips attributes, removes scripts/styles and converts the body to markdown.
    public static String convertFromUrl(String url) throws IOException {
        Document doc = Jsoup.connect(url)
                .userAgent("Mozilla/5.0 (compatible; webwatch/1.0)")
                .timeout(15000)
                .get();

        // Remove noise
        doc.select("script, style, noscript, iframe, header, footer, nav").remove();

        // Remove all attributes from elements to simplify structure, but keep href/src/alt/title
        for (Element el : doc.getAllElements()) {
            List<Attribute> attrs = el.attributes().asList();
            for (Attribute a : attrs) {
                String key = a.getKey();
                if ("href".equalsIgnoreCase(key) || "src".equalsIgnoreCase(key) || "alt".equalsIgnoreCase(key) || "title".equalsIgnoreCase(key)) {
                    continue; // keep these
                }
                el.removeAttr(key);
            }
        }

        Element body = doc.body();
        StringBuilder sb = new StringBuilder();
        processNode(body, sb, 0, false);

        String out = sb.toString().trim();
        // Normalize whitespace and collapse excessive blank lines
        out = out.replaceAll("\r\n", "\n");
        out = out.replaceAll("\n{3,}", "\n\n");
        return out;
    }

    // Recursive node processor. listLevel is >0 when inside nested lists. inPre toggles pre/code blocks.
    private static void processNode(Node node, StringBuilder sb, int listLevel, boolean inPre) {
        if (node instanceof TextNode) {
            String text = ((TextNode) node).text();
            if (inPre) {
                sb.append(text);
            } else {
                // collapse internal whitespace
                text = text.replaceAll("\\s+", " ");
                sb.append(text);
            }
            return;
        }

        // Use pattern matching for instanceof to avoid separate cast
        if (!(node instanceof Element el)) return;
        String tag = el.tagName().toLowerCase();

        switch (tag) {
            case "h1":
            case "h2":
            case "h3":
            case "h4":
            case "h5":
            case "h6": {
                int level = Integer.parseInt(tag.substring(1));
                sb.append("\n\n");
                sb.append("#".repeat(level)).append(" ");
                for (Node c : el.childNodes()) processNode(c, sb, 0, false);
                sb.append("\n\n");
                break;
            }
            case "p": {
                sb.append("\n\n");
                for (Node c : el.childNodes()) processNode(c, sb, 0, false);
                sb.append("\n\n");
                break;
            }
            case "br": {
                sb.append("  \n");
                break;
            }
            case "a": {
                String href = el.attr("href");
                StringBuilder inner = new StringBuilder();
                for (Node c : el.childNodes()) processNode(c, inner, 0, false);
                String text = inner.toString().trim();
                if (text.isEmpty()) text = href;
                if (href.isEmpty()) {
                    sb.append(text);
                } else {
                    sb.append("[").append(text).append("](").append(href).append(")");
                }
                break;
            }
            case "ul": {
                sb.append("\n");
                for (Node c : el.childNodes()) processNode(c, sb, listLevel + 1, false);
                sb.append("\n");
                break;
            }
            case "ol": {
                sb.append("\n");
                int idx = 1;
                // iterate Elements to avoid casts
                for (Element child : el.children()) {
                    if ("li".equalsIgnoreCase(child.tagName())) {
                        sb.append(idx).append(". ");
                        processNode(child, sb, listLevel + 1, false);
                        sb.append("\n");
                        idx++;
                    } else {
                        processNode(child, sb, listLevel + 1, false);
                    }
                }
                sb.append("\n");
                break;
            }
            case "li": {
                // prefix for unordered lists: build indent using repeat instead of concatenation in loop
                String indent = listLevel > 1 ? "  ".repeat(listLevel - 1) : "";
                String prefix = indent + "- ";
                sb.append(prefix);
                for (Node c : el.childNodes()) processNode(c, sb, listLevel, false);
                sb.append("\n");
                break;
            }
            case "strong":
            case "b": {
                sb.append("**");
                for (Node c : el.childNodes()) processNode(c, sb, 0, false);
                sb.append("**");
                break;
            }
            case "em":
            case "i": {
                sb.append("*");
                for (Node c : el.childNodes()) processNode(c, sb, 0, false);
                sb.append("*");
                break;
            }
            case "code": {
                // if parent is pre, treat as block; otherwise inline
                if (el.parent() != null && "pre".equalsIgnoreCase(el.parent().tagName())) {
                    sb.append("\n\n```");
                    for (Node c : el.childNodes()) processNode(c, sb, 0, true);
                    sb.append("```");
                    sb.append("\n\n");
                } else {
                    sb.append("`");
                    for (Node c : el.childNodes()) processNode(c, sb, 0, false);
                    sb.append("`");
                }
                break;
            }
            case "pre": {
                sb.append("\n\n```");
                for (Node c : el.childNodes()) processNode(c, sb, 0, true);
                sb.append("```");
                sb.append("\n\n");
                break;
            }
            case "img": {
                String alt = el.attr("alt");
                String src = el.attr("src");
                // Do not emit empty image tags like ![]()
                if (src == null || src.isBlank()) {
                    if (alt != null && !alt.isBlank()) {
                        // emit alt text as plain text when src missing
                        sb.append(alt);
                    }
                    // otherwise skip entirely
                } else {
                    sb.append("![").append(alt == null ? "" : alt).append("](").append(src).append(")");
                }
                break;
            }
            case "table": {
                // Very simple table handling: render text separated by | for rows
                sb.append("\n\n");
                for (Element row : el.select("tr")) {
                    boolean first = true;
                    for (Element cell : row.select("th,td")) {
                        if (!first) sb.append(" | ");
                        StringBuilder inner = new StringBuilder();
                        for (Node c : cell.childNodes()) processNode(c, inner, 0, false);
                        sb.append(inner.toString().trim());
                        first = false;
                    }
                    sb.append("\n");
                }
                sb.append("\n\n");
                break;
            }
            default: {
                // Default: recurse into children
                for (Node c : el.childNodes()) processNode(c, sb, listLevel, inPre);
            }
        }
    }
}
