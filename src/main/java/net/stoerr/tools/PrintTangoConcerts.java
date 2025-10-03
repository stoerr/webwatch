package net.stoerr.tools;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Attribute;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

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
public class PrintTangoConcerts {

    private static final String TANGO_URL = "https://www.dresden-tango.de/html/ifkalender.html";
    private static final String MODEL_NAME = "gpt-4o-search-preview";
    private static final String SEEN_CONCERTS_FILE = "data/tango-concerts-seen.json";
    private static final String SEEN_CONCERTS_FILE_NEW = "data/tango-concerts-seen-new.json";

    public static void main(String[] args) throws Exception {
        int returncode = 0;
        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        Type concertListType = new TypeToken<ConcertList>() {}.getType();

        ConcertList oldConcerts = new ConcertList(new ArrayList<>());
        // read old concerts from file (JSON object with 'concerts' field)
        try {
            String oldJson = Files.readString(Path.of(SEEN_CONCERTS_FILE));
            ConcertList parsed = gson.fromJson(oldJson, concertListType);
            if (parsed != null) oldConcerts = parsed;
        } catch (Exception e) {
            System.out.println("No previous concerts file found, starting fresh. " + e);
        }

        try (var in = new URL(TANGO_URL).openStream()) {
            String htmlContent = new String(in.readAllBytes());

            ChatModel chatModel = OpenAiChatModel.builder()
                    .apiKey(System.getenv("OPENAI_API_KEY"))
                    .modelName(MODEL_NAME)
                    .build();

            // Use jsoup to parse the HTML and remove all attributes from all elements.
            Document doc = Jsoup.parse(htmlContent, TANGO_URL);
            for (Element el : doc.getAllElements()) {
                // copy attribute list to avoid concurrent modification
                List<Attribute> attrs = new ArrayList<>(el.attributes().asList());
                for (Attribute a : attrs) {
                    el.removeAttr(a.getKey());
                }
            }
            // Use the cleaned HTML (outerHtml includes the document structure)
            htmlContent = doc.outerHtml();

            ConcertExtractor extractor = AiServices.builder(ConcertExtractor.class).chatModel(chatModel).build();

            // Extract concerts as a ConcertList
            ConcertList currentConcerts = extractor.extractConcerts(htmlContent);

            // Determine new concerts that were not in the old concerts
            ConcertList newConcertsOnly = extractor.newConcertsOnly(currentConcerts, oldConcerts);

            System.out.println("New Concerts Since Last Check:");
            if (newConcertsOnly == null || newConcertsOnly.concerts() == null || newConcertsOnly.concerts().isEmpty()) {
                // no output
                returncode = 1;
            } else {
                // print it neatly human readable
                for (Concert c : newConcertsOnly.concerts()) {
                    System.out.println(c.date() + " " + c.time());
                    System.out.println("    " + c.description());
                    System.out.println("    " + c.location());
                    System.out.println("    " + c.link());
                }
            }

            // Write the current concerts to the file for next time as JSON (wrapped as ConcertList)
            ConcertList forWrite = currentConcerts != null ? currentConcerts : new ConcertList(new ArrayList<>());
            String currentJson = gson.toJson(forWrite);
            Files.writeString(Path.of(SEEN_CONCERTS_FILE_NEW), currentJson);
        }
        System.exit(returncode);
    }

    public record Concert(String date, String time, String description, String location, String link) {
    }

    public record ConcertList(List<Concert> concerts) {
    }

    private interface ConcertExtractor {
        @SystemMessage("Extract all concerts from the given HTML. " +
                "Return a JSON-serializable ConcertList record with field 'concerts' containing Concert objects (date, time, description, location, link). " +
                "Only mention concerts, no other events like Milongas or Praktika.")
        ConcertList extractConcerts(String html);

        @SystemMessage("Given two ConcertList objects 'current' and 'old', return only those concerts from 'current' that are not in 'old'. " +
                "If there are no new concerts, return an empty ConcertList with an empty 'concerts' list.")
        @UserMessage("Current concerts (as JSON):\n{{current}}\n\nOld concerts (as JSON):\n{{old}}\n\nReturn only the new concerts as a JSON ConcertList object.")
        ConcertList newConcertsOnly(@V("current") ConcertList current, @V("old") ConcertList old);
    }


}
