// filepath: /Users/hans-peter.stoerr/dev/my/webwatch/src/main/java/net/stoerr/tools/CheckWebPagesForChanges.java
package net.stoerr.tools;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Attribute;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.io.IOException;
import java.lang.reflect.Type;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Simple tool to monitor a list of web pages for changes.
 *
 * Behavior summary:
 * - Reads a JSON configuration file (default: `data/checkpages-config.json`) containing an array of page entries.
 * - For each page it fetches the HTML, sanitizes it (removes element attributes), and extracts the text/html for configured CSS selectors.
 * - Stores the extracted, structured data into `data/checkpages/<sanitized-name>.json` and keeps the previous version as `*.prev.json`.
 * - Compares the current extraction to the previous one and prints concise change information.
 *
 * Exit codes:
 * - 0: at least one page showed changes
 * - 1: no changes
 * - 2: fatal error (e.g. missing config)
 *
 * Configuration example (data/checkpages-config.json):
 * [
 *   {
 *     "name": "dresden-tango-calendar",
 *     "url": "https://www.dresden-tango.de/html/ifkalender.html",
 *     "selectors": ["#content", "table"]
 *   }
 * ]
 */
public class CheckWebPagesForChanges {

    private static final String DEFAULT_CONFIG = "data/checkpages-config.json";
    private static final String DATA_DIR = "data/checkpages";

    public static void main(String[] args) throws Exception {
        String configFile = args.length > 0 ? args[0] : DEFAULT_CONFIG;

        Gson gson = new GsonBuilder().setPrettyPrinting().create();

        List<PageConfig> configs;
        try {
            String cfg = Files.readString(Path.of(configFile));
            Type listType = new com.google.gson.reflect.TypeToken<List<PageConfig>>(){}.getType();
            configs = gson.fromJson(cfg, listType);
            if (configs == null) configs = new ArrayList<>();
        } catch (Exception e) {
            System.err.println("Failed to read config '" + configFile + "': " + e);
            System.exit(2);
            return;
        }

        Files.createDirectories(Path.of(DATA_DIR));
        boolean anyChanges = false;

        for (PageConfig pc : configs) {
            if (pc == null || pc.url == null || pc.url.isBlank()) {
                System.err.println("Skipping invalid config entry: " + pc);
                continue;
            }
            try {
                PageResult current = fetchAndExtract(pc);
                // Use the URL as the basis for the filename as requested (remove all non-letter/digit chars)
                String filename = sanitizeFilename(pc.url);
                Path filePath = Path.of(DATA_DIR, filename + ".json");
                Path prevPath = Path.of(DATA_DIR, filename + ".prev.json");
                PageResult previous = null;
                if (Files.exists(filePath)) {
                    try {
                        String prevJson = Files.readString(filePath);
                        previous = gson.fromJson(prevJson, PageResult.class);
                    } catch (Exception ex) {
                        System.err.println("Warning: can't parse previous file for " + pc.url + ": " + ex);
                    }
                }

                boolean changed = !Objects.equals(previous, current);
                if (changed) {
                    anyChanges = true;
                    System.out.println("CHANGED: " + pc.url + (pc.name != null ? " (" + pc.name + ")" : ""));
                    printDifferences(previous, current);
                    // keep previous copy
                    if (Files.exists(filePath)) {
                        Files.copy(filePath, prevPath, StandardCopyOption.REPLACE_EXISTING);
                    }
                    Files.writeString(filePath, gson.toJson(current));
                } else {
                    System.out.println("NO CHANGE: " + pc.url + (pc.name != null ? " (" + pc.name + ")" : ""));
                }

            } catch (Exception e) {
                System.err.println("Error processing " + pc.url + ": " + e);
            }
        }

        System.exit(anyChanges ? 0 : 1);
    }

    private static void printDifferences(PageResult oldR, PageResult newR) {
        if (oldR == null) {
            System.out.println("  (no previous capture, created new record)");
            // print a short listing of selectors and their content sizes
            for (Extracted e : newR.extracted()) {
                System.out.println("  selector: " + e.selector() + " -> " + shortPreview(e.content()));
            }
            return;
        }
        Map<String, Extracted> oldMap = oldR.extracted().stream().collect(Collectors.toMap(Extracted::selector, e -> e));
        for (Extracted ne : newR.extracted()) {
            Extracted oe = oldMap.get(ne.selector());
            if (oe == null) {
                System.out.println("  ADDED selector: " + ne.selector() + " -> " + shortPreview(ne.content()));
            } else if (!Objects.equals(oe.content(), ne.content())) {
                System.out.println("  CHANGED selector: " + ne.selector());
                System.out.println("    - previous: " + shortPreview(oe.content()));
                System.out.println("    - current : " + shortPreview(ne.content()));
            }
        }
        // detect removed selectors
        Map<String, Extracted> newMap = newR.extracted().stream().collect(Collectors.toMap(Extracted::selector, e -> e));
        for (Extracted oe : oldR.extracted()) {
            if (!newMap.containsKey(oe.selector())) {
                System.out.println("  REMOVED selector: " + oe.selector() + " -> " + shortPreview(oe.content()));
            }
        }
    }

    private static String shortPreview(String s) {
        if (s == null) return "(null)";
        s = s.strip();
        if (s.length() <= 200) return s.replaceAll("\n", " ");
        return s.substring(0, 200).replaceAll("\n", " ") + "... (truncated, length=" + s.length() + ")";
    }

    private static PageResult fetchAndExtract(PageConfig pc) throws IOException {
        try (var in = new URL(pc.url).openStream()) {
            String html = new String(in.readAllBytes());
            Document doc = Jsoup.parse(html, pc.url);
            // remove all attributes to keep extraction stable
            for (Element el : doc.getAllElements()) {
                List<Attribute> attrs = new ArrayList<>(el.attributes().asList());
                for (Attribute a : attrs) el.removeAttr(a.getKey());
            }

            List<Extracted> extracted = new ArrayList<>();
            List<String> selectors = pc.selectors != null && !pc.selectors.isEmpty() ? pc.selectors : List.of("body");
            for (String sel : selectors) {
                try {
                    var els = doc.select(sel);
                    if (els.isEmpty()) {
                        extracted.add(new Extracted(sel, ""));
                    } else {
                        // join multiple elements with a separator
                        String joined = els.stream().map(Element::outerHtml).collect(Collectors.joining("\n\n---\n\n"));
                        extracted.add(new Extracted(sel, joined));
                    }
                } catch (Exception e) {
                    // selector parse error or other issue
                    extracted.add(new Extracted(sel, "(error extracting selector: " + e + ")"));
                }
            }

            return new PageResult(pc.url, pc.name, extracted, Instant.now().toString());
        }
    }

    private static String sanitizeFilename(String input) {
        // As requested: take the URL and remove all non-letter/non-digit characters.
        if (input == null) input = "";
        String stripped = input.replaceAll("[^A-Za-z0-9]", "");
        if (stripped.isBlank()) {
            // fallback to a short hash when stripping yields nothing
            try {
                MessageDigest md = MessageDigest.getInstance("SHA-1");
                byte[] digest = md.digest(input.getBytes());
                StringBuilder sb = new StringBuilder();
                for (byte b : digest) sb.append(String.format("%02x", b));
                return sb.toString().substring(0, 32);
            } catch (Exception ex) {
                return Integer.toHexString(Objects.hashCode(input));
            }
        }
        // keep filename reasonably sized
        if (stripped.length() > 200) stripped = stripped.substring(0, 200);
        return stripped;
    }

    // Configuration record read from JSON
    public static final class PageConfig {
        public String name;
        public String url;
        public List<String> selectors;

        public PageConfig() {}

        @Override
        public String toString() {
            return "PageConfig{name='" + name + "', url='" + url + "'}";
        }
    }

    public static record Extracted(String selector, String content) {}
    public static record PageResult(String url, String name, List<Extracted> extracted, String fetchedAt) {}

}
