package net.stoerr.tools;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Class to retrieve HTML from Dresden Tango calendar and extract concert information using OpenAI.
 * We write the concerts we have already alerted the user into a file "tango-concerts-seen.txt"
 * and read it on the next run to avoid duplicate alerts.
 */
public class PrintTangoConcerts3 {

    private static final String TANGO_URL = "https://www.dresden-tango.de/html/ifkalender.html";
    private static final String MODEL_NAME = "gpt-4.1"; // "gpt-4o-search-preview";
    private static final String SEEN_CONCERTS_FILE = "data/tango-concerts-seen.txt";
    private static final String SEEN_CONCERTS_FILE_NEW = "data/tango-concerts-seen-new.txt";

    public static void main(String[] args) throws Exception {
        String oldConcerts = "";
        // read old concerts from file
        try {
            oldConcerts = Files.readString(Path.of(SEEN_CONCERTS_FILE));
        } catch (Exception e) {
            System.out.println("No previous concerts file found, starting fresh.");
        }

        StringBuilder newConcerts = new StringBuilder();

        try (var in = new URL(TANGO_URL).openStream()) {
            String htmlContent = new String(in.readAllBytes());

            ChatModel chatModel = OpenAiChatModel.builder()
                    .apiKey(System.getenv("OPENAI_API_KEY"))
                    .modelName(MODEL_NAME)
                    .build();

            htmlContent = htmlContent
                    .replaceAll("(?i)style=\"[^\"]*\"", "")
                    .replaceAll("(?i)class=\"[^\"]*\"", "");

            newConcerts.append("Tango Concerts from Dresden Tango Calendar:\n");

            ConcertExtractor extractor = AiServices.builder(ConcertExtractor.class).chatModel(chatModel).build();
            String concerts = extractor.extractConcerts(htmlContent);
            newConcerts.append(concerts).append("\n");

            // Determine new concerts that were not in the old concerts
            String newConcertsOnly = extractor.newConcertsOnly(concerts, oldConcerts);

            System.out.println("New Concerts Since Last Check:");
            System.out.println(newConcertsOnly);
            // Write the current concerts to the file for next time
            Files.writeString(Path.of(SEEN_CONCERTS_FILE_NEW), concerts);
        }
    }

    private interface ConcertExtractor {
        @SystemMessage("Extract all concerts from the given HTML. " +
                "Each concert should be on a new line, with date and time if available." +
                "Only mention concerts, no other events like Milongas or Praktika.")
        String extractConcerts(String html);

        @SystemMessage("Given two lists of concerts, 'current' and 'old', return only those concerts from 'current' that are not in 'old'. " +
                "If there are no new concerts, return the message 'No new concerts found.'")
        @UserMessage("Current concerts:\n{{current}}\n\nOld concerts:\n{{old}}\n\nPrint only the new concerts.n Do not mention the old concerts.")
        String newConcertsOnly(@V("current") String current, @V("old") String old);
    }

}
