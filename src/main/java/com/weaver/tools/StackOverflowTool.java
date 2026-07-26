package com.weaver.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.Tool;
import org.jsoup.Jsoup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;

@Component
public class StackOverflowTool {

    private static final Logger log = LoggerFactory.getLogger(StackOverflowTool.class);
    private static final String SE_API_BASE = "https://api.stackexchange.com/2.3";
    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();

    @Tool("Search Stack Overflow for code solutions and programming answers. Returns top answers with code snippets.")
    public String searchStackOverflow(String query) {
        try {
            String encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8);
            String url = SE_API_BASE + "/search/advanced?order=desc&sort=relevance&accepted=True"
                    + "&q=" + encodedQuery
                    + "&site=stackoverflow&filter=withbody&pagesize=3";

            log.info("StackOverflow search: '{}'", query);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Accept-Encoding", "gzip")
                    .GET()
                    .build();

            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());

            byte[] body = response.body();
            String json;
            if (response.headers().firstValue("content-encoding").orElse("").contains("gzip")) {
                try (GZIPInputStream gis = new GZIPInputStream(new ByteArrayInputStream(body))) {
                    json = new String(gis.readAllBytes(), StandardCharsets.UTF_8);
                }
            } else {
                json = new String(body, StandardCharsets.UTF_8);
            }

            JsonNode root = mapper.readTree(json);
            JsonNode items = root.get("items");

            if (items == null || items.isEmpty()) {
                return "No Stack Overflow results found for: " + query;
            }

            List<String> results = new ArrayList<>();
            for (int i = 0; i < Math.min(3, items.size()); i++) {
                JsonNode item = items.get(i);
                String title = item.has("title") ? item.get("title").asText() : "Untitled";
                String link = item.has("link") ? item.get("link").asText() : "";
                int score = item.has("score") ? item.get("score").asInt() : 0;
                int answerCount = item.has("answer_count") ? item.get("answer_count").asInt() : 0;

                String bodyHtml = item.has("body") ? item.get("body").asText() : "";
                String bodyText = Jsoup.parse(bodyHtml).text();
                if (bodyText.length() > 800) {
                    bodyText = bodyText.substring(0, 800) + "...";
                }

                results.add(String.format("[%d] %s (score: %d, answers: %d)\n    %s\n    %s\n",
                        i + 1, title, score, answerCount, link, bodyText));
            }

            return "Stack Overflow results for: \"" + query + "\"\n\n" + String.join("\n", results);
        } catch (Exception e) {
            log.warn("StackOverflow search failed: {}", e.getMessage());
            return "ERROR: StackOverflow search failed - " + e.getMessage();
        }
    }
}
