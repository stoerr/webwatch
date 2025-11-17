package net.stoerr.tools;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

import com.github.difflib.DiffUtils;
import com.github.difflib.UnifiedDiffUtils;
import com.github.difflib.patch.Patch;

import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Simple tool to monitor a list of web pages for changes.
 * <p>
 * Behavior summary:
 * - Reads a JSON configuration file (default: `data/checkpages-config.json`) containing an array of page entries.
 * - For each page it fetches the HTML, simplifies it to markdown using `HTMLToMarkdown`, and writes the markdown to a file.
 * - If a previous capture exists the program will feed old and new markdown to an LLM (if OPENAI_API_KEY is set) to produce
 * a concise summary of relevant differences. If no key is set the program prints a short preview diff.
 * - Stores the cleaned markdown into `data/checkpages/<sanitized-url>.md`.
 */
public class CheckWebPagesForChangesWithDiffs {

    private static final String DEFAULT_CONFIG = "data/checkpages-config.json";
    private static final String DATA_DIR = "data/checkpages";
    private static final String MODEL_NAME = "gpt-4.1";

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

        // Initialize LLM only if API key present
        String apiKey = System.getenv("OPENAI_API_KEY");
        DiffExtractor extractor = null;
        if (apiKey != null && !apiKey.isBlank()) {
            ChatModel chatModel = OpenAiChatModel.builder().apiKey(apiKey).modelName(MODEL_NAME)
                    .temperature(0.0).seed(6432).timeout(Duration.of(1, ChronoUnit.MINUTES)).build();
            extractor = AiServices.builder(DiffExtractor.class).chatModel(chatModel).build();
        } else {
            System.err.println("No API key.");
            System.exit(3);
        }

        for (PageConfig pc : configs) {
            if (pc == null || pc.url == null || pc.url.isBlank()) {
                System.err.println("Skipping invalid config entry: " + pc);
                continue;
            }
            try {
                // Use HTMLToMarkdown helper to fetch and convert the page to markdown
                String markdown = HTMLToMarkdown.convertFromUrl(pc.url);
                String filename = sanitizeFilename(pc.url);
                Path filePath = Path.of(DATA_DIR, filename + ".md");

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
                    Files.writeString(filePath, markdown);
                    anyChanges = true;
                    // Only output the URL and the change indicator
                    System.out.println(pc.url);
                    System.out.println("NEW_CAPTURE");
                } else if (!Objects.equals(previous, markdown)) {
                    // changed: produce LLM-based summary if possible
                    anyChanges = true;
                    // Only output the URL and then the change summary/preview
                    System.out.println(pc.url);
                    // create unified diff from previous -> markdown
                    List<String> originalLines = Arrays.asList(previous.split("\n", -1));
                    List<String> revisedLines = Arrays.asList(markdown.split("\n", -1));
                    Patch<String> patch = DiffUtils.diff(originalLines, revisedLines);
                    List<String> unified = UnifiedDiffUtils.generateUnifiedDiff("previous.md", "current.md", originalLines, patch, 3);
                    String unifiedDiff = String.join("\n", unified);
                    String diffSummary = extractor.describeDifferences(unifiedDiff, pc.url);
                    System.out.println(diffSummary);
                    // overwrite current capture with new content
                    Files.writeString(filePath, markdown);
                }

            } catch (Exception e) {
                System.err.println("Error processing " + pc.url + ": " + e);
            }
        }
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

    private static String sanitizeFilename(String input) {
        String stripped = input.replaceAll("[^A-Za-z0-9]", "");
        if (stripped.length() > 200) stripped = stripped.substring(0, 200);
        if (stripped.isEmpty()) {
            // fallback to a short UUID to avoid empty filenames
            return "page-" + UUID.randomUUID().toString().substring(0, 8);
        }
        return stripped;
    }

    // Configuration record read from JSON
    public record PageConfig(String name, String url) {
    }

    // LLM interface to describe differences between two captures (now unified diff)
    private interface DiffExtractor {
        @SystemMessage("""
                You are a helpful assistant that reads a unified diff between two versions of a web page and returns a concise summary of changes.
                The diff follows the standard unified diff format (--- a/previous.md +++ b/current.md @@ hunks ...).
                Focus on substantive content changes (added/removed/changed text, new or removed sections, added links), ignore advertisements irrelevant to the main page content.
                Keep the summary short and actionable (a few bullet points). NEVER mention formatting changes.
                If there are no meaningful changes, return the single word: NO_CHANGE.
                """)
        @UserMessage("""
                {{diff}}
                """)
        String describeDifferences(@V("diff") String unifiedDiff, @V("url") String url);
    }

}
