package com.claudecode.tool.impl;

import com.claudecode.tool.Tool;
import com.claudecode.tool.PermissionResult;
import com.claudecode.tool.ToolContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 网络搜索工具 —— 使用 DuckDuckGo HTML 搜索（免费，无需 API Key）。
 * <p>
 * 对应 claude-code 的 WebSearchTool，用于搜索互联网获取实时信息。
 * 通过解析 DuckDuckGo HTML 搜索结果页面提取搜索结果。
 */
public class WebSearchTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(WebSearchTool.class);

    /** DuckDuckGo HTML 搜索端点（不需要 JavaScript） */
    private static final String DDG_URL = "https://html.duckduckgo.com/html/";
    private static final int MAX_RESULTS = 8;
    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    @Override
    public String name() {
        return "WebSearch";
    }

    @Override
    public String description() {
        return "Search the web using DuckDuckGo. Returns search results with titles, URLs and snippets. " +
                "Use this to find up-to-date information, documentation, or answers to questions.";
    }

    @Override
    public String inputSchema() {
        return """
                {
                  "type": "object",
                  "properties": {
                    "query": {
                      "type": "string",
                      "description": "Search query string"
                    },
                    "maxResults": {
                      "type": "integer",
                      "description": "Maximum number of results to return (default: 8)"
                    }
                  },
                  "required": ["query"]
                }
                """;
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public String activityDescription(Map<String, Object> input) {
        String query = (String) input.getOrDefault("query", "");
        return "Searching: " + query;
    }

    @Override
    public String execute(Map<String, Object> input, ToolContext context) {
        String query = (String) input.get("query");
        if (query == null || query.isBlank()) {
            return "Error: query parameter is required";
        }

        int maxResults = MAX_RESULTS;
        if (input.containsKey("maxResults")) {
            maxResults = ((Number) input.get("maxResults")).intValue();
            maxResults = Math.max(1, Math.min(maxResults, 20));
        }

        try {
            String html = fetchSearchPage(query);
            return parseResults(html, maxResults);
        } catch (Exception e) {
            log.debug("Search failed: query={}", query, e);
            return "Error: Search failed - " + e.getMessage();
        }
    }

    /** 请求 DuckDuckGo HTML 搜索页面 */
    private String fetchSearchPage(String query) throws IOException, InterruptedException {
        String encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8);

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(DDG_URL + "?q=" + encodedQuery))
                .header("User-Agent", "Mozilla/5.0 (compatible; ClaudeCodeJava/1.0)")
                .GET()
                .timeout(TIMEOUT)
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new IOException("HTTP " + response.statusCode());
        }

        return response.body();
    }

    /** 从 DuckDuckGo HTML 页面解析搜索结果 */
    private String parseResults(String html, int maxResults) {
        StringBuilder sb = new StringBuilder();

        // DuckDuckGo HTML 搜索结果格式：
        // <a class="result__a" href="...">Title</a>
        // <a class="result__snippet" href="...">Snippet</a>
        // 或者结果块在 <div class="result results_links results_links_deep web-result">

        // 提取结果链接和标题
        Pattern resultPattern = Pattern.compile(
                "<a[^>]+class=\"result__a\"[^>]*href=\"([^\"]*)\"[^>]*>(.*?)</a>",
                Pattern.DOTALL);

        // 提取摘要
        Pattern snippetPattern = Pattern.compile(
                "<a[^>]+class=\"result__snippet\"[^>]*>(.*?)</a>",
                Pattern.DOTALL);

        Matcher resultMatcher = resultPattern.matcher(html);
        Matcher snippetMatcher = snippetPattern.matcher(html);

        int count = 0;
        while (resultMatcher.find() && count < maxResults) {
            count++;
            String url = resultMatcher.group(1);
            String title = stripHtml(resultMatcher.group(2));

            // DuckDuckGo 的链接是重定向格式，提取实际 URL
            if (url.contains("uddg=")) {
                try {
                    String decoded = java.net.URLDecoder.decode(
                            url.substring(url.indexOf("uddg=") + 5), StandardCharsets.UTF_8);
                    // 截取到 & 之前
                    int ampIdx = decoded.indexOf('&');
                    if (ampIdx > 0) decoded = decoded.substring(0, ampIdx);
                    url = decoded;
                } catch (Exception ignored) {}
            }

            String snippet = "";
            if (snippetMatcher.find()) {
                snippet = stripHtml(snippetMatcher.group(1));
            }

            sb.append(count).append(". ").append(title).append("\n");
            sb.append("   URL: ").append(url).append("\n");
            if (!snippet.isBlank()) {
                sb.append("   ").append(snippet).append("\n");
            }
            sb.append("\n");
        }

        if (count == 0) {
            // 尝试备用解析模式
            return parseResultsFallback(html, maxResults);
        }

        return sb.toString();
    }

    /** 备用解析：简单的链接提取 */
    private String parseResultsFallback(String html, int maxResults) {
        StringBuilder sb = new StringBuilder();

        // 提取所有外部链接
        Pattern linkPattern = Pattern.compile("<a[^>]+href=\"(https?://[^\"]*)\"[^>]*>(.*?)</a>", Pattern.DOTALL);
        Matcher matcher = linkPattern.matcher(html);

        int count = 0;
        java.util.Set<String> seenUrls = new java.util.HashSet<>();

        while (matcher.find() && count < maxResults) {
            String url = matcher.group(1);
            String title = stripHtml(matcher.group(2));

            // 跳过 DuckDuckGo 自身链接和重复
            if (url.contains("duckduckgo.com") || title.isBlank() || !seenUrls.add(url)) {
                continue;
            }

            count++;
            sb.append(count).append(". ").append(title).append("\n");
            sb.append("   URL: ").append(url).append("\n\n");
        }

        if (count == 0) {
            return "No results found. Try a different query.";
        }

        return sb.toString();
    }

    /** 去除 HTML 标签 */
    private String stripHtml(String html) {
        if (html == null) return "";
        return html.replaceAll("<[^>]+>", "")
                .replaceAll("&amp;", "&")
                .replaceAll("&lt;", "<")
                .replaceAll("&gt;", ">")
                .replaceAll("&quot;", "\"")
                .replaceAll("&#x27;", "'")
                .replaceAll("&nbsp;", " ")
                .strip();
    }
}
