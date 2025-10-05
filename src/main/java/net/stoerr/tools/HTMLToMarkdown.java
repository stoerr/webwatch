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
                // detect if href points to an image file
                boolean hrefLooksLikeImage = !href.isBlank() && href.toLowerCase().matches(".*\\.(png|jpe?g|gif|bmp|svg)(?:\\?.*)?$");

                // Build inner text excluding image elements
                StringBuilder innerTextBuilder = new StringBuilder();
                for (Node c : el.childNodes()) {
                    if (c instanceof TextNode) {
                        innerTextBuilder.append(((TextNode) c).text()).append(" ");
                    } else if (c instanceof Element childEl) {
                        if (!"img".equalsIgnoreCase(childEl.tagName())) {
                            innerTextBuilder.append(childEl.text()).append(" ");
                        }
                    }
                }
                String innerText = innerTextBuilder.toString().trim();

                // If the link wraps only images or the href looks like an image, do not emit a link.
                // Instead, process children normally (images will be emitted by the img case).
                if (hrefLooksLikeImage || innerText.isEmpty()) {
                    for (Node c : el.childNodes()) processNode(c, sb, 0, false);
                } else {
                    // Otherwise emit a regular markdown link with the textual content
                    // but process child nodes that are not images to preserve styling inside the link text
                    StringBuilder visible = new StringBuilder();
                    for (Node c : el.childNodes()) {
                        if (c instanceof Element childEl && "img".equalsIgnoreCase(childEl.tagName())) {
                            // skip images inside link text
                            continue;
                        }
                        processNode(c, visible, 0, false);
                    }
                    String visibleText = visible.toString().trim();
                    if (!visibleText.isEmpty() && !href.isBlank()) {
                        sb.append("[").append(visibleText).append("](").append(href).append(")");
                    } else {
                        // fallback: if no visible text, just process children (no link)
                        for (Node c : el.childNodes()) processNode(c, sb, 0, false);
                    }
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
                // Previously emitted images as Markdown. Skip images entirely to avoid image links in output.
                // This removes decorative or logo images such as small icons and site logos.
                break;
            }
            case "table": {
                // Very simple table handling: render text separated by | for rows
                sb.append("\n\n");
                for (Element row : el.select("tr")) {
                    boolean first = true;
                    for (Element cell : row.select("th,td")) {
                        if (!first) sb.append(" ");
                        sb.append("| ");
                        StringBuilder inner = new StringBuilder();
                        for (Node c : cell.childNodes()) processNode(c, inner, 0, false);
                        sb.append(inner.toString().trim());
                        first = false;
                    }
                    sb.append(" |\n");
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
