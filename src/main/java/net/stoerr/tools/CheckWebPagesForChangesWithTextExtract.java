// filepath: /Users/hans-peter.stoerr/dev/my/webwatch/src/main/java/net/stoerr/tools/CheckWebPagesForChanges.java
package net.stoerr.tools;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

import java.io.IOException;
import java.lang.reflect.Type;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

// jsoup imports
import org.jsoup.Jsoup;
import org.jsoup.nodes.Attribute;
import org.jsoup.nodes.Attributes;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

/**
 * Simple tool to monitor a list of web pages for changes.
 *
 * Behavior summary (changed):
 * - Reads a JSON configuration file (default: `data/checkpages-config.json`) containing an array of page entries.
 * - For each page it fetches the HTML and runs an LLM to extract the page text in Markdown format.
 * - The Markdown is stored in `data/checkpages/<sanitized-url>.md` and previous versions are kept as `*.prev.md`.
 * - If a previous markdown exists the program will feed old and new Markdown to an LLM (if OPENAI_API_KEY is set)
 *   to produce a concise summary of relevant differences.
 */
public class CheckWebPagesForChangesWithTextExtract {

    private static final String DEFAULT_CONFIG = "data/checkpages-config.json";
    private static final String DATA_DIR = "data/checkpages";
    private static final String MODEL_NAME = "gpt-4.1-mini";

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

        // Initialize LLM if API key present
        String apiKey = System.getenv("OPENAI_API_KEY");
        ChatModel chatModel;
        TextExtractor textExtractor;
        DiffExtractor diffExtractor;
        if (apiKey != null && !apiKey.isBlank()) {
            chatModel = OpenAiChatModel.builder().apiKey(apiKey).modelName(MODEL_NAME).
                    temperature(0.0).seed(6432).timeout(Duration.of(5, ChronoUnit.MINUTES)).build();
            textExtractor = AiServices.builder(TextExtractor.class).chatModel(chatModel).build();
            diffExtractor = AiServices.builder(DiffExtractor.class).chatModel(chatModel).build();
        } else {
            throw new IllegalStateException("OPENAI_API_KEY not set; LLM-based extraction and diffs will be skipped.");
        }

        for (PageConfig pc : configs) {
            if (pc == null || pc.url == null || pc.url.isBlank()) {
                System.err.println("Skipping invalid config entry: " + pc);
                continue;
            }
            try {
                String html = fetchHtml(pc);

                // Remove all attributes except href/HREF before sending to the LLM
                String cleanedHtml = stripAttributesExceptHref(html);

                String markdown = textExtractor.convertHtmlToMarkdown(cleanedHtml, pc.url);

                String filename = sanitizeFilename(pc.url);
                Path filePath = Path.of(DATA_DIR, filename + ".md");
                Path prevPath = Path.of(DATA_DIR, filename + ".prev.md");

                String previous = null;
                if (Files.exists(filePath)) {
                    try {
                        previous = Files.readString(filePath);
                    } catch (Exception ex) {
                        System.err.println("Warning: can't read previous file for " + pc.url + ": " + ex);
                    }
                }

                if (previous == null) {
                    // no previous capture: write the markdown
                    Files.writeString(filePath, markdown != null ? markdown : "");
                    System.out.println("NEW CAPTURE: " + pc.url + (pc.name != null ? " (" + pc.name + ")" : ""));
                    anyChanges = true;
                } else if (!Objects.equals(previous, markdown)) {
                    // changed: produce LLM-based summary comparing the markdowns
                    anyChanges = true;
                    System.out.println("CHANGED: " + pc.url + (pc.name != null ? " (" + pc.name + ")" : ""));
                    String diffSummary = diffExtractor.describeDifferences(previous, markdown != null ? markdown : "", pc.url);
                    System.out.println("LLM summary:\n" + diffSummary);
                    // keep previous copy
                    Files.copy(filePath, prevPath, StandardCopyOption.REPLACE_EXISTING);
                    Files.writeString(filePath, markdown != null ? markdown : "");
                } else {
                    System.out.println("NO CHANGE: " + pc.url + (pc.name != null ? " (" + pc.name + ")" : ""));
                }

            } catch (Exception e) {
                System.err.println("Error processing " + pc.url + ": " + e);
            }
        }

        System.exit(anyChanges ? 0 : 1);
    }

    private static String fetchHtml(PageConfig pc) throws IOException {
        try (var in = new URL(pc.url).openStream()) {
            return new String(in.readAllBytes());
        }
    }

    // new helper: remove all attributes except href (case-insensitive)
    private static String stripAttributesExceptHref(String html) {
        if (html == null) return null;
        Document doc = Jsoup.parse(html);
        for (Element el : doc.getAllElements()) {
            Attributes attrs = el.attributes();
            List<String> toRemove = new ArrayList<>();
            for (Attribute a : attrs) {
                if (!a.getKey().equalsIgnoreCase("href")) {
                    toRemove.add(a.getKey());
                }
            }
            for (String k : toRemove) {
                el.removeAttr(k);
            }
        }
        return doc.outerHtml();
    }

    private static String sanitizeFilename(String input) {
        String stripped = input.replaceAll("[^A-Za-z0-9]", "");
        if (stripped.length() > 200) stripped = stripped.substring(0, 200);
        return stripped;
    }

    // Configuration record read from JSON
    public record PageConfig(String name, String url) {
    }

    // LLM interface to convert HTML into Markdown
    private interface TextExtractor {
        @SystemMessage("""
                You are a helpful assistant that converts a full HTML page into clean, readable Markdown.
                Faithfully preserve all text content and links, but try to recognize headings and present them; use # for h1, ## for h2, etc.
                Return only valid Markdown and nothing else. 
                If the information is tabular format, convert it to a Markdown table, but do not indent / align it with spaces.
                """)
        @UserMessage("""
===============================================================================
HTML:
{{html}}
===============================================================================
Return the page content as Markdown.
""")
        String convertHtmlToMarkdown(@V("html") String html, @V("url") String url);
    }

    // LLM interface to describe differences between two Markdown captures
    private interface DiffExtractor {
        @SystemMessage("""
                You are a helpful assistant that compares two versions of a page in Markdown format and returns a concise summary of relevant changes.
                Focus on substantive content changes (added/removed/changed text, new or removed sections, added links or images).
                Absolutely ignore advertisements or formatting changes. 
                The markdown files might be very different in formatting but identical in content - focus on the actual content.
                Keep the summary short and actionable (a few bullet points). If there are no meaningful changes, return the single word: NO_CHANGE.
                """)
        @UserMessage("""
===============================================================================
Old Markdown:
{{old}}
===============================================================================
New Markdown:
{{new}}
===============================================================================
Return a concise summary of changes.
""")
        String describeDifferences(@V("old") String oldMarkdown, @V("new") String newMarkdown, @V("url") String url);
    }

}
