package net.stoerr.tools;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.SystemMessage;

import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Class to retrieve HTML from Dresden Tango calendar and extract concert information using OpenAI
 */
public class PrintTangoConcerts2 {

    private static final String TANGO_URL = "https://www.dresden-tango.de/html/ifkalender.html";
    private static final String MODEL_NAME = "gpt-4.1"; // "gpt-4o-search-preview";

    public static void main(String[] args) throws Exception {
        try (var in = new URL(TANGO_URL).openStream()) {
            String htmlContent = new String(in.readAllBytes());

            ChatModel chatModel = OpenAiChatModel.builder()
                    .apiKey(System.getenv("OPENAI_API_KEY"))
                    .modelName(MODEL_NAME)
                    .build();

            htmlContent = htmlContent
                    .replaceAll("(?i)style=\"[^\"]*\"", "")
                    .replaceAll("(?i)class=\"[^\"]*\"", "");
            // System.out.println(htmlContent);

            System.out.println("Tango Concerts from Dresden Tango Calendar:");
            System.out.println("=".repeat(50));

            ConcertExtractor extractor = AiServices.builder(ConcertExtractor.class).chatModel(chatModel).build();
            String concerts = extractor.extractConcerts(htmlContent);
            System.out.println(concerts);

            System.out.println("=".repeat(50));
        }
    }

    private interface ConcertExtractor {
        @SystemMessage("Extract all concerts from the given HTML. " +
                "Each concert should be on a new line, with date and time if available." +
                "Only mention concerts, no other events like Milongas or Praktika.")
        String extractConcerts(String html);
    }

}
