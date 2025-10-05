package net.stoerr.tools;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.Locale;

/**
 * Read a JSON configuration (array of objects with at least a "url" property, optional "name").
 * For each entry fetch the URL, convert to markdown using HTMLToMarkdown and write a .md file
 * into data/markdown. Filenames are sanitized: all non-alphanumeric characters removed and
 * trimmed to reasonable length; collisions are resolved by appending suffixes.
 */
public class RetrieveURLs {

    public static void main(String[] args) {
        String jsonFile = args.length > 0 ? args[0] : "data/checkpages-config-all.json";
        Path jsonPath = Paths.get(jsonFile);
        Path outDir = Paths.get("data/markdown");

        try {
            if (!Files.exists(outDir)) Files.createDirectories(outDir);
        } catch (IOException e) {
            System.err.println("Could not create output directory " + outDir + ": " + e.getMessage());
            System.exit(2);
        }

        JsonArray arr;
        try {
            String json = Files.readString(jsonPath, StandardCharsets.UTF_8);
            Gson gson = new Gson();
            arr = gson.fromJson(json, JsonArray.class);
            if (arr == null) {
                System.err.println("JSON file does not contain an array: " + jsonPath);
                System.exit(2);
                return;
            }
        } catch (IOException e) {
            System.err.println("Error reading JSON file " + jsonPath + ": " + e.getMessage());
            System.exit(2);
            return;
        } catch (Exception e) {
            System.err.println("Error parsing JSON file " + jsonPath + ": " + e.getMessage());
            System.exit(2);
            return;
        }

        int counter = 1;
        for (JsonElement el : arr) {
            if (!el.isJsonObject()) continue;
            JsonObject obj = el.getAsJsonObject();
            String name = obj.has("name") && !obj.get("name").isJsonNull() ? obj.get("name").getAsString() : null;
            String url = obj.has("url") && !obj.get("url").isJsonNull() ? obj.get("url").getAsString() : null;
            if (url == null || url.isBlank()) continue;

            String markdown;
            try {
                markdown = HTMLToMarkdown.convertFromUrl(url);
            } catch (IOException ioe) {
                markdown = "Could not fetch URL: " + url + "\n\nError: " + ioe.getMessage();
                System.err.println("[WARN] Failed to fetch " + url + ": " + ioe.getMessage());
            } catch (Exception ex) {
                markdown = "Error processing URL: " + url + "\n\n" + ex.getMessage();
                System.err.println("[WARN] Error processing " + url + ": " + ex.getMessage());
            }

            String title = (name == null || name.isBlank()) ? url : name;
            StringBuilder out = new StringBuilder();
            out.append("# ").append(title).append("\n\n");
            out.append("Original URL: [").append(url).append("](").append(url).append(")\n\n");
            out.append("Retrieved: ").append(Instant.now().toString()).append("\n\n");
            out.append(markdown).append("\n");

            // Post-process: remove empty image constructs and collapse >2 newlines into 2
            String processed = out.toString();
            processed = removeEmptyImageTokens(processed);
            processed = collapseNewlines(processed);
            // trim leading/trailing whitespace
            processed = processed.trim() + "\n";

            String baseName = sanitizeForFilename(url);
            if (baseName.isBlank()) baseName = sanitizeForFilename(title);
            if (baseName.isBlank()) baseName = "output" + counter;
            // limit filename length
            if (baseName.length() > 200) baseName = baseName.substring(0, 200);

            Path outFile = outDir.resolve(baseName + ".md");
            int dup = 1;
            while (Files.exists(outFile)) {
                outFile = outDir.resolve(baseName + "_" + dup + ".md");
                dup++;
            }

            try {
                Files.writeString(outFile, processed, StandardCharsets.UTF_8);
                System.out.println("Wrote: " + outFile + " (from " + url + ")");
            } catch (IOException e) {
                System.err.println("Failed to write file " + outFile + ": " + e.getMessage());
            }

            counter++;
        }
    }

    // Remove occurrences of markdown image tokens that have empty alt and empty src, e.g. ![]()
    private static String removeEmptyImageTokens(String s) {
        if (s == null || s.isEmpty()) return s;
        StringBuilder sb = new StringBuilder(s);
        int idx = 0;
        while (true) {
            int start = sb.indexOf("![", idx);
            if (start < 0) break;
            int mid = sb.indexOf("](", start + 2);
            if (mid < 0) break;
            int end = sb.indexOf(")", mid + 2);
            if (end < 0) break;
            String betweenBracket = sb.substring(start + 2, mid).trim();
            String betweenParen = sb.substring(mid + 2, end).trim();
            if (betweenBracket.isEmpty() && betweenParen.isEmpty()) {
                // remove the empty token
                sb.delete(start, end + 1);
                idx = start;
            } else {
                idx = end + 1;
            }
        }
        return sb.toString();
    }

    // Collapse any run of 3 or more consecutive newlines into exactly two newlines
    private static String collapseNewlines(String s) {
        if (s == null || s.isEmpty()) return s;
        StringBuilder out = new StringBuilder();
        int n = s.length();
        int i = 0;
        int run = 0;
        while (i < n) {
            char c = s.charAt(i);
            if (c == '\n') {
                run++;
                i++;
            } else {
                if (run > 0) {
                    if (run > 2) run = 2;
                    for (int k = 0; k < run; k++) out.append('\n');
                    run = 0;
                }
                out.append(c);
                i++;
            }
        }
        if (run > 0) {
            if (run > 2) run = 2;
            for (int k = 0; k < run; k++) out.append('\n');
        }
        return out.toString();
    }

    // Simple sanitizer: keep only ASCII letters and digits, normalized to lower-case.
    // If input looks like a URL, try to extract host+path for a more readable name.
    private static String sanitizeForFilename(String input) {
        if (input == null) return "";
        String candidate = input;
        // try to extract host+path for URLs
        try {
            URI uri = new URI(input);
            String host = uri.getHost();
            String path = uri.getPath();
            String query = uri.getQuery();
            StringBuilder sb = new StringBuilder();
            if (host != null) sb.append(host.replaceAll("[^A-Za-z0-9]", ""));
            if (path != null && !path.isBlank()) sb.append(path.replaceAll("[^A-Za-z0-9]", ""));
            if (query != null && !query.isBlank()) sb.append(query.replaceAll("[^A-Za-z0-9]", ""));
            if (!sb.isEmpty()) candidate = sb.toString();
        } catch (URISyntaxException ignored) {
            // not a URL, fall back to full input
        }

        // keep only alphanumeric, lower-case
        return candidate.replaceAll("[^A-Za-z0-9]", "").toLowerCase(Locale.ROOT);
    }
}
