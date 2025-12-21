package net.stoerr.tools;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.SystemMessage;
import org.yaml.snakeyaml.Yaml;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Run configured search jobs: feed each job.prompt to the LLM and print any output that is not the
 * literal string "NOT FOUND". Exit code 0 if at least one positive result was printed, 2 if all
 * returned "NOT FOUND", 1 on unexpected error.
 */
public class SearchJobs {

    private static final String DEFAULT_CONFIG = "src/main/resources/searchjobs.yaml";
    private static final String MODEL_NAME = "gpt-5-search-api";

    public static void main(String[] args) throws Exception {
        String configFile = args.length > 0 ? args[0] : DEFAULT_CONFIG;
        List<Job> jobs;
        try {
            String cfg = Files.readString(Path.of(configFile));
            jobs = parseJobs(cfg);
        } catch (Exception e) {
            System.err.println("Failed to read config '" + configFile + "': " + e);
            System.exit(1);
            return;
        }

        String apiKey = System.getenv("OPENAI_API_KEY");
        ChatModel chatModel = OpenAiChatModel.builder()
                .apiKey(apiKey)
                .modelName(MODEL_NAME)
                .timeout(Duration.ofSeconds(60))
                .build();

        SearchExtractor extractor = AiServices.builder(SearchExtractor.class).chatModel(chatModel).build();

        boolean anyPositive = false;
        try {
            for (Job job : jobs) {
                if (job == null || job.prompt == null || job.prompt.isBlank()) continue;
                String raw = extractor.search(job.prompt);
                String out = raw == null ? "" : raw.trim();
                if (!"NOT FOUND".equals(out)) {
                    anyPositive = true;
                    String header = "=== SearchJobs result: " + (job.title != null ? job.title : "(no title)") + " ===";
                    System.out.println(header);
                    System.out.println(out);
                }
            }
        } catch (Exception e) {
            System.err.println("Error while running search jobs: " + e);
            System.exit(1);
            return;
        }

        if (anyPositive) {
            System.exit(0);
        } else {
            System.exit(2);
        }
    }

    private static List<Job> parseJobs(String cfg) {
        Yaml yaml = new Yaml();
        Object loaded = yaml.load(cfg);
        List<Job> jobs = new ArrayList<>();
        if (loaded == null) {
            return jobs;
        }
        if (!(loaded instanceof List<?> list)) {
            throw new IllegalArgumentException("Expected YAML list at top level");
        }
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map)) continue;
            Job job = new Job();
            Object title = map.get("title");
            Object prompt = map.get("prompt");
            job.title = title == null ? null : title.toString();
            job.prompt = prompt == null ? null : prompt.toString();
            jobs.add(job);
        }
        return jobs;
    }

    private static class Job {
        String title;
        String prompt;
    }

    private interface SearchExtractor {
        @SystemMessage("Given the prompt, either return the single token NOT FOUND if nothing relevant was found, or return the result text. Do not include extra instructions.")
        String search(String prompt);
    }
}
