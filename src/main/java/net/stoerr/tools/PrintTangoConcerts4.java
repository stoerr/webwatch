package net.stoerr.tools;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

import java.lang.reflect.Type;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Class to retrieve HTML from Dresden Tango calendar and extract concert information using OpenAI.
 * We write the concerts we have already alerted the user into a file "tango-concerts-seen.txt"
 * and read it on the next run to avoid duplicate alerts.
 */
public class PrintTangoConcerts4 {

    private static final String TANGO_URL = "https://www.dresden-tango.de/html/ifkalender.html";
    private static final String MODEL_NAME = "gpt-4.1"; // "gpt-4o-search-preview";
    private static final String SEEN_CONCERTS_FILE = "data/tango-concerts-seen.json";
    private static final String SEEN_CONCERTS_FILE_NEW = "data/tango-concerts-seen-new.json";

    public static void main(String[] args) throws Exception {
        Gson gson = new Gson();
        Type concertListType = new TypeToken<List<Concert>>() {}.getType();

        List<Concert> oldConcerts = new ArrayList<>();
        // read old concerts from file (JSON list)
        try {
            String oldJson = Files.readString(Path.of(SEEN_CONCERTS_FILE));
            oldConcerts = gson.fromJson(oldJson, concertListType);
            if (oldConcerts == null) oldConcerts = new ArrayList<>();
        } catch (Exception e) {
            System.out.println("No previous concerts file found, starting fresh.");
        }

        try (var in = new URL(TANGO_URL).openStream()) {
            String htmlContent = new String(in.readAllBytes());

            ChatModel chatModel = OpenAiChatModel.builder()
                    .apiKey(System.getenv("OPENAI_API_KEY"))
                    .modelName(MODEL_NAME)
                    .build();

            htmlContent = htmlContent
                    .replaceAll("(?i)style=\"[^\"]*\"", "")
                    .replaceAll("(?i)class=\"[^\"]*\"", "");

            ConcertExtractor extractor = AiServices.builder(ConcertExtractor.class).chatModel(chatModel).build();

            // Extract concerts as a list of Concert records
            List<Concert> currentConcerts = extractor.extractConcerts(htmlContent);

            // Determine new concerts that were not in the old concerts
            List<Concert> newConcertsOnly = extractor.newConcertsOnly(currentConcerts, oldConcerts);

            System.out.println("New Concerts Since Last Check:");
            if (newConcertsOnly == null || newConcertsOnly.isEmpty()) {
                System.out.println("No new concerts found.");
            } else {
                System.out.println(gson.toJson(newConcertsOnly));
            }

            // Write the current concerts to the file for next time as JSON
            String currentJson = gson.toJson(currentConcerts != null ? currentConcerts : new ArrayList<Concert>());
            Files.writeString(Path.of(SEEN_CONCERTS_FILE_NEW), currentJson);
        }
    }

    private record Concert(String date, String time, String description, String location, String link) {
    }

    private interface ConcertExtractor {
        @SystemMessage("Extract all concerts from the given HTML. " +
                "Return a JSON-serializable list of Concert records (date, time, description, location, link). " +
                "Only mention concerts, no other events like Milongas or Praktika.")
        List<Concert> extractConcerts(String html);

        @SystemMessage("Given two lists of concerts, 'current' and 'old', return only those concerts from 'current' that are not in 'old'. " +
                "If there are no new concerts, return an empty list.")
        @UserMessage("Current concerts (as JSON):\n{{current}}\n\nOld concerts (as JSON):\n{{old}}\n\nReturn only the new concerts as a JSON array.")
        List<Concert> newConcertsOnly(@V("current") List<Concert> current, @V("old") List<Concert> old);
    }

}
