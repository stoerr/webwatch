package net.stoerr.tools;

import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.chat.ChatModel;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Class to retrieve HTML from Dresden Tango calendar and extract concert information using OpenAI
 */
public class PrintTangoConcerts {

    private static final String TANGO_URL = "https://www.dresden-tango.de/html/ifkalender.html";
    private static final String MODEL_NAME = "gpt-4.1"; // "gpt-4o-search-preview";

    public static void main(String[] args) {
        PrintTangoConcerts extractor = new PrintTangoConcerts();
        extractor.extractAndPrintConcerts();
    }

    public void extractAndPrintConcerts() {
        try {
            // Retrieve HTML content
            String htmlContent = retrieveHtmlContent(TANGO_URL);

            // Process with OpenAI
            String concerts = extractConcertsWithAI(htmlContent);

            // Print results
            System.out.println("Tango Concerts from Dresden Tango Calendar:");
            System.out.println("=".repeat(50));
            System.out.println(concerts);

        } catch (Exception e) {
            System.err.println("Error extracting tango concerts: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Retrieves HTML content from the given URL using java.net.http.HttpClient
     */
    private String retrieveHtmlContent(String url) throws IOException, InterruptedException {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new IOException("HTTP request failed with status code: " + response.statusCode());
        }

        String content = response.body();
        System.out.println("Successfully retrieved HTML content (" + content.length() + " characters)");
        return content;
    }

    /**
     * Uses OpenAI to extract concert information from HTML content
     */
    private String extractConcertsWithAI(String htmlContent) {
        String apiKey = System.getenv("OPENAI_API_KEY");
        if (apiKey == null || apiKey.trim().isEmpty()) {
            throw new RuntimeException("OPENAI_API_KEY environment variable is not set");
        }

        ChatModel chatModel = OpenAiChatModel.builder()
                .apiKey(apiKey)
                .modelName(MODEL_NAME)
                .build();

        String prompt = buildPrompt(htmlContent);

        System.out.println("Sending request to OpenAI...");
        String response = chatModel.chat(prompt);

        return response;
    }

    /**
     * Builds the prompt for OpenAI to extract tango concert information
     */
    private String buildPrompt(String htmlContent) {
        return String.format("""
            Please analyze the following HTML content from a Dresden Tango calendar website and extract all tango concerts/events.
            
            For each concert/event, please provide:
            - Date and time
            - Event name/title
            - Location/venue
            - Artists/performers (if mentioned)
            - Any additional relevant details
            
            Please format the output in a clear, readable way with each concert as a separate entry.
            Focus only on actual tango concerts, performances, or musical events - ignore general dance classes or social events unless they specifically mention live music or concerts.
            
            HTML Content:
            %s
            """, htmlContent);
    }
}
