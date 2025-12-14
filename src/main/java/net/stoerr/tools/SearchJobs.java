package net.stoerr.tools;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.SystemMessage;

import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Run configured search jobs: feed each job.prompt to the LLM and print any output that is not the
 * literal string "NOT FOUND". Exit code 0 if at least one positive result was printed, 2 if all
 * returned "NOT FOUND", 1 on unexpected error.
 */
public class SearchJobs {

    private static final String DEFAULT_CONFIG = "src/main/resources/searchjobs.json";
    private static final String MODEL_NAME = "gpt-5-search-api";

    public static void main(String[] args) throws Exception {
        String configFile = args.length > 0 ? args[0] : DEFAULT_CONFIG;
        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        List<Job> jobs;
        try {
            String cfg = Files.readString(Path.of(configFile));
            Type listType = new TypeToken<List<Job>>() {}.getType();
            jobs = gson.fromJson(cfg, listType);
            if (jobs == null) jobs = new ArrayList<>();
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

    private static class Job {
        String title;
        String prompt;
    }

    private interface SearchExtractor {
        @SystemMessage("Given the prompt, either return the single token NOT FOUND if nothing relevant was found, or return the result text. Do not include extra instructions.")
        String search(String prompt);
    }
}

