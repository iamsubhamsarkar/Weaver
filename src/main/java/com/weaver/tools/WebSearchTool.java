package com.weaver.tools;

import dev.langchain4j.agent.tool.Tool;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

@Component
public class WebSearchTool {

    private static final Logger log = LoggerFactory.getLogger(WebSearchTool.class);

    @Tool("Search the web using DuckDuckGo. Returns top results with titles, URLs, and snippets. Use for finding documentation, error solutions, library usage examples.")
    public String webSearch(String query) {
        try {
            String encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8);
            String url = "https://html.duckduckgo.com/html/?q=" + encodedQuery;

            log.info("Web search: '{}'", query);

            Document doc = Jsoup.connect(url)
                    .userAgent("Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36")
                    .timeout(15000)
                    .get();

            Elements results = doc.select(".result");
            List<String> searchResults = new ArrayList<>();

            int count = 0;
            for (Element result : results) {
                if (count >= 8) break;

                Element titleEl = result.selectFirst(".result__title a");
                Element snippetEl = result.selectFirst(".result__snippet");

                if (titleEl != null) {
                    String title = titleEl.text();
                    String link = titleEl.attr("href");
                    String snippet = snippetEl != null ? snippetEl.text() : "";

                    searchResults.add(String.format("[%d] %s\n    URL: %s\n    %s\n",
                            count + 1, title, link, snippet));
                    count++;
                }
            }

            if (searchResults.isEmpty()) {
                return "No results found for: " + query;
            }

            return "Web search results for: \"" + query + "\"\n\n" + String.join("\n", searchResults);
        } catch (Exception e) {
            log.warn("Web search failed: {}", e.getMessage());
            return "ERROR: Web search failed - " + e.getMessage();
        }
    }

    @Tool("Fetch and extract the main text content from a web page URL. Useful for reading documentation pages.")
    public String fetchWebPage(String url) {
        try {
            log.info("Fetching: {}", url);

            Document doc = Jsoup.connect(url)
                    .userAgent("Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36")
                    .timeout(15000)
                    .maxBodySize(500_000)
                    .get();

            doc.select("script, style, nav, footer, header, .sidebar, .menu, .ads").remove();

            String text = doc.body().text();
            if (text.length() > 8000) {
                text = text.substring(0, 8000) + "\n... [TRUNCATED]";
            }

            return "Content from " + url + ":\n\n" + text;
        } catch (Exception e) {
            return "ERROR fetching page: " + e.getMessage();
        }
    }
}
