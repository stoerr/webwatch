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
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

/**
 * Simple tool to monitor a list of web pages for changes.
 * <p>
 * Behavior summary:
 * - Reads a JSON configuration file (default: `data/checkpages-config.json`) containing an array of page entries.
 * - For each page it fetches the HTML, sanitizes it (removes element attributes), and writes the cleaned HTML to a file.
 * - If a previous capture exists the program will feed old and new HTML to an LLM (if OPENAI_API_KEY is set) to produce
 * a concise summary of relevant differences. If no key is set the program prints a short preview diff.
 * - Stores the cleaned HTML into `data/checkpages/<sanitized-url>.html` and keeps the previous version as `*.prev.html`.
 */
public class CheckWebPagesForChanges {

    private static final String DEFAULT_CONFIG = "data/checkpages-config.json";
    private static final String DATA_DIR = "data/checkpages";
    private static final String MODEL_NAME = "gpt-4.1-mini";

    public static void main(String[] args) throws Exception {
        String configFile = args.length > 0 ? args[0] : DEFAULT_CONFIG;

        Gson gson = new GsonBuilder().setPrettyPrinting().create();

        List<PageConfig> configs;
        try {
            String cfg = Files.readString(Path.of(configFile));
            Type listType = new com.google.gson.reflect.TypeToken<List<PageConfig>>() {
            }.getType();
            configs = gson.fromJson(cfg, listType);
            if (configs == null) configs = new ArrayList<>();
        } catch (Exception e) {
            System.err.println("Failed to read config '" + configFile + "': " + e);
            System.exit(2);
            return;
        }

        Files.createDirectories(Path.of(DATA_DIR));
        boolean anyChanges = false;

        // Initialize LLM if API key present
        String apiKey = System.getenv("OPENAI_API_KEY");
        ChatModel chatModel = null;
        DiffExtractor extractor = null;
        if (apiKey != null && !apiKey.isBlank()) {
            chatModel = OpenAiChatModel.builder().apiKey(apiKey).modelName(MODEL_NAME).build();
            extractor = AiServices.builder(DiffExtractor.class).chatModel(chatModel).build();
        } else {
            throw new IllegalStateException("OPENAI_API_KEY not set; LLM-based diffs will be skipped.");
        }

        for (PageConfig pc : configs) {
            if (pc == null || pc.url == null || pc.url.isBlank()) {
                System.err.println("Skipping invalid config entry: " + pc);
                continue;
            }
            try {
                String cleanedHtml = fetchAndCleanHtml(pc);
                String filename = sanitizeFilename(pc.url);
                Path filePath = Path.of(DATA_DIR, filename + ".html");
                Path prevPath = Path.of(DATA_DIR, filename + ".prev.html");

                String previous = null;
                if (Files.exists(filePath)) {
                    try {
                        previous = Files.readString(filePath);
                    } catch (Exception ex) {
                        System.err.println("Warning: can't read previous file for " + pc.url + ": " + ex);
                    }
                }

                if (previous == null) {
                    // no previous capture: write the cleaned HTML
                    Files.writeString(filePath, cleanedHtml);
                    System.out.println("NEW CAPTURE: " + pc.url + (pc.name != null ? " (" + pc.name + ")" : ""));
                    anyChanges = true;
                } else if (!Objects.equals(previous, cleanedHtml)) {
                    // changed: produce LLM-based summary if possible
                    anyChanges = true;
                    System.out.println("CHANGED: " + pc.url + (pc.name != null ? " (" + pc.name + ")" : ""));
                    String diffSummary = extractor.describeDifferences(previous, cleanedHtml, pc.url != null ? pc.url : "");
                    System.out.println("LLM summary:\n" + diffSummary);
                    // keep previous copy
                    Files.copy(filePath, prevPath, StandardCopyOption.REPLACE_EXISTING);
                    Files.writeString(filePath, cleanedHtml);
                } else {
                    System.out.println("NO CHANGE: " + pc.url + (pc.name != null ? " (" + pc.name + ")" : ""));
                }

            } catch (Exception e) {
                System.err.println("Error processing " + pc.url + ": " + e);
            }
        }

        System.exit(anyChanges ? 0 : 1);
    }

    private static void printInlinePreview(String oldS, String newS) {
        System.out.println("  (no LLM available) Showing short previews:");
        System.out.println("    - previous: " + shortPreview(oldS));
        System.out.println("    - current : " + shortPreview(newS));
    }

    private static String shortPreview(String s) {
        if (s == null) return "(null)";
        s = s.strip();
        if (s.length() <= 200) return s.replaceAll("\n", " ");
        return s.substring(0, 200).replaceAll("\n", " ") + "... (truncated, length=" + s.length() + ")";
    }

    private static String fetchAndCleanHtml(PageConfig pc) throws IOException {
        try (var in = new URL(pc.url).openStream()) {
            String html = new String(in.readAllBytes());
            Document doc = Jsoup.parse(html, pc.url);
            // remove all attributes except href or HREF to keep extraction stable
            for (Element el : doc.getAllElements()) {
                List<Attribute> attrs = new ArrayList<>(el.attributes().asList());
                for (Attribute a : attrs)
                    if (!a.getKey().equalsIgnoreCase("href"))
                        el.removeAttr(a.getKey());
            }
            // return the cleaned outerHtml of the document
            return doc.outerHtml();
        }
    }

    private static String sanitizeFilename(String input) {
        String stripped = input.replaceAll("[^A-Za-z0-9]", "");
        if (stripped.length() > 200) stripped = stripped.substring(0, 200);
        return stripped;
    }

    // Configuration record read from JSON
    public static record PageConfig(String name, String url) {
    }

    // LLM interface to describe differences between two HTML captures
    private interface DiffExtractor {
        @SystemMessage("You are a helpful assistant that compares two versions of a web page and returns a concise summary of relevant changes.\n" +
                "The HTML provided has been cleaned: all element attributes (ids, classes, styles) have already been removed.\n" +
                "Focus on substantive content changes (added/removed/changed text, new or removed sections, added links or images), ignore advertisements.\n" +
                "Keep the summary short and actionable (a few bullet points). If there are no meaningful changes, return the single word: NO_CHANGE.")
        @UserMessage("""
                ===============================================================================
                Old HTML:
                {{old}}
                ===============================================================================
                New HTML:
                {{new}}
                ===============================================================================
                Return a concise summary of changes.
                """)
        String describeDifferences(@V("old") String oldHtml, @V("new") String newHtml, @V("url") String url);
    }

}
