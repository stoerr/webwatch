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
    private static final String MODEL_NAME = "gpt-5-nano";
    private static final String MODEL_NAME_HI = "gpt-5.1";

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

        // Initialize LLM only if API key present
        String apiKey = System.getenv("OPENAI_API_KEY");
        DiffExtractor extractor = null;
        Cleanup cleanup = null;
        if (apiKey != null && !apiKey.isBlank()) {
            ChatModel chatModel = OpenAiChatModel.builder().apiKey(apiKey).modelName(MODEL_NAME)
                    .timeout(Duration.of(1, ChronoUnit.MINUTES)).build();
            extractor = AiServices.builder(DiffExtractor.class).chatModel(chatModel).build();

            ChatModel cleanupModel = OpenAiChatModel.builder().apiKey(apiKey).modelName(MODEL_NAME_HI)
                    .timeout(Duration.of(1, ChronoUnit.MINUTES)).build();
            cleanup = AiServices.builder(Cleanup.class).chatModel(cleanupModel).build();
        } else {
            System.err.println("No API key.");
            System.exit(3);
        }

        StringBuilder diffs = new StringBuilder();
        String today = java.time.LocalDate.now().toString();

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
                    // Only output the URL and the change indicator
                    diffs.append("\n\n").append(pc.url).append("\nNEW_CAPTURE\n");
                } else if (!Objects.equals(previous, markdown)) {
                    // changed: produce LLM-based summary if possible
                    // create unified diff from previous -> markdown
                    List<String> originalLines = Arrays.asList(previous.split("\n", -1));
                    List<String> revisedLines = Arrays.asList(markdown.split("\n", -1));
                    Patch<String> patch = DiffUtils.diff(originalLines, revisedLines);
                    List<String> unified = UnifiedDiffUtils.generateUnifiedDiff("previous.md", "current.md", originalLines, patch, 3);
                    String unifiedDiff = String.join("\n", unified);
                    String diffSummary = extractor.describeDifferences(unifiedDiff, pc.url, today);
                    if (diffSummary != null && !"NO_CHANGE".equals(diffSummary.trim())) {
                        diffs.append("\n\n").append(pc.url).append("\n").append(diffSummary).append("\n");
                    }
                    // overwrite current capture with new content
                    Files.writeString(filePath, markdown);
                }

            } catch (Exception e) {
                System.err.println("Error processing " + pc.url + ": " + e);
            }
        }

        String cleaned = cleanup.cleanUp(diffs.toString(), today);
        System.out.println(cleaned);
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
                Focus on substantive content changes (added/changed content, new sections, added links), ignore advertisements irrelevant to the main page content.
                Keep the summary short and actionable (a few bullet points). NEVER mention formatting changes and
                ignore removed content unless it is critical information. Never report changed ticket counts or removed events.
                Focus on changes / new information about what is described by the page, not on changes in the choosen presentation in the page.
                If there are no meaningful changes, return the single word: NO_CHANGE.
                Today is the {{current_date}} - do not mention removed information about past events or sold out events.
                """)
        @UserMessage("""
                {{diff}}
                """)
        String describeDifferences(@V("diff") String unifiedDiff, @V("url") String url,
                                   @V("current_date") String currentDate);
    }

    private interface Cleanup {
        @SystemMessage("""
                Your job is to print the user's text but remove minor changes like numbers of available tickets,
                presentation changes like hanged headlines and links. Only changes of the content, such as new events
                changed events, new available information should be kept.
                Focus on changes / new information about what is described by the page, not on changes in the choosen p
                resentation in the page. You can formulate the cleaned up text more concisely to reach that goal.
                If there are only irrelevant changes for a web page then remove the section for that page entirely.
                Today is the {{current_date}} - do not mention removed information about past events or sold out events.
                """)
        @UserMessage("{{text}}")
        String cleanUp(@V("text") String text, @V("current_date") String currentDate);
    }

}
