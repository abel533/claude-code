package com.claudecode.tool.impl;

import com.claudecode.tool.Tool;
import com.claudecode.tool.ToolContext;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 网页获取工具 —— 对应 claude-code/src/tools/WebFetchTool。
 * <p>
 * 使用 HTTP GET 获取指定 URL 的内容，自动将 HTML 简化为纯文本。
 * 支持大小限制、超时控制和基本的 HTML→文本转换。
 */
public class WebFetchTool implements Tool {

    /** 最大响应体大小：100KB */
    private static final int MAX_BODY_SIZE = 100 * 1024;

    /** HTTP 请求超时 */
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    /** User-Agent 标识 */
    private static final String USER_AGENT = "ClaudeCode-Java/0.1 (WebFetchTool)";

    @Override
    public String name() {
        return "WebFetch";
    }

    @Override
    public String description() {
        return """
            Fetch the content of a URL. Returns the page content as text. \
            HTML pages are automatically simplified to readable text. \
            Useful for reading documentation, API responses, or web pages. \
            Has a 100KB size limit and 30s timeout.""";
    }

    @Override
    public String inputSchema() {
        return """
            {
              "type": "object",
              "properties": {
                "url": {
                  "type": "string",
                  "description": "The URL to fetch (must start with http:// or https://)"
                },
                "maxLength": {
                  "type": "integer",
                  "description": "Maximum number of characters to return (default: 50000)"
                }
              },
              "required": ["url"]
            }""";
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public String execute(Map<String, Object> input, ToolContext context) {
        String url = (String) input.get("url");
        int maxLength = input.containsKey("maxLength")
                ? ((Number) input.get("maxLength")).intValue()
                : 50000;

        // URL 校验
        if (url == null || url.isBlank()) {
            return "Error: URL is required";
        }
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return "Error: URL must start with http:// or https://";
        }

        try {
            URI uri = URI.create(url);

            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(TIMEOUT)
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build();

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(uri)
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "text/html,application/xhtml+xml,application/json,text/plain,*/*")
                    .timeout(TIMEOUT)
                    .GET()
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            int statusCode = response.statusCode();
            String body = response.body();

            if (statusCode >= 400) {
                return "Error: HTTP " + statusCode + "\n" + truncate(body, 2000);
            }

            // 检查大小限制
            if (body.length() > MAX_BODY_SIZE) {
                body = body.substring(0, MAX_BODY_SIZE);
            }

            // 根据内容类型处理
            String contentType = response.headers().firstValue("Content-Type").orElse("text/plain");

            String result;
            if (contentType.contains("text/html") || contentType.contains("application/xhtml")) {
                result = htmlToText(body);
            } else {
                result = body;
            }

            // 截断到最大长度
            result = truncate(result, maxLength);

            StringBuilder sb = new StringBuilder();
            sb.append("URL: ").append(url).append("\n");
            sb.append("Status: ").append(statusCode).append("\n");
            sb.append("Content-Type: ").append(contentType).append("\n");
            sb.append("---\n");
            sb.append(result);

            return sb.toString();

        } catch (IllegalArgumentException e) {
            return "Error: Invalid URL: " + e.getMessage();
        } catch (java.net.http.HttpTimeoutException e) {
            return "Error: Request timed out after " + TIMEOUT.toSeconds() + " seconds";
        } catch (Exception e) {
            return "Error fetching URL: " + e.getMessage();
        }
    }

    /**
     * 简单的 HTML → 纯文本转换。
     * 移除脚本/样式块，转换常见标签为文本格式。
     */
    private String htmlToText(String html) {
        // 移除 script 和 style 块
        String text = html.replaceAll("(?is)<script[^>]*>.*?</script>", "");
        text = text.replaceAll("(?is)<style[^>]*>.*?</style>", "");

        // 移除 HTML 注释
        text = text.replaceAll("(?s)<!--.*?-->", "");

        // 将块级元素转为换行
        text = text.replaceAll("(?i)<br\\s*/?>", "\n");
        text = text.replaceAll("(?i)</(p|div|h[1-6]|li|tr|blockquote|pre)>", "\n");
        text = text.replaceAll("(?i)<(p|div|h[1-6]|li|tr|blockquote|pre)[^>]*>", "\n");

        // 将链接转为 [text](url) 格式
        Pattern linkPattern = Pattern.compile("<a[^>]*href=[\"']([^\"']*)[\"'][^>]*>(.*?)</a>", Pattern.CASE_INSENSITIVE);
        Matcher linkMatcher = linkPattern.matcher(text);
        text = linkMatcher.replaceAll("[$2]($1)");

        // 移除所有剩余 HTML 标签
        text = text.replaceAll("<[^>]+>", "");

        // 解码常见 HTML 实体
        text = text.replace("&amp;", "&");
        text = text.replace("&lt;", "<");
        text = text.replace("&gt;", ">");
        text = text.replace("&quot;", "\"");
        text = text.replace("&apos;", "'");
        text = text.replace("&nbsp;", " ");
        // 数字实体
        java.util.regex.Pattern numEntity = java.util.regex.Pattern.compile("&#(\\d+);");
        java.util.regex.Matcher numMatcher = numEntity.matcher(text);
        text = numMatcher.replaceAll(mr -> {
            try {
                return String.valueOf((char) Integer.parseInt(mr.group(1)));
            } catch (Exception e) {
                return mr.group();
            }
        });

        // 压缩多余空行（3个以上连续空行压缩为2个）
        text = text.replaceAll("\\n{3,}", "\n\n");
        // 压缩行内多余空格
        text = text.replaceAll("[ \\t]+", " ");

        return text.strip();
    }

    /** 截断文本到指定长度 */
    private String truncate(String text, int maxLength) {
        if (text.length() <= maxLength) return text;
        return text.substring(0, maxLength) + "\n...[truncated at " + maxLength + " chars]";
    }

    @Override
    public String activityDescription(Map<String, Object> input) {
        String url = (String) input.getOrDefault("url", "");
        // 截断过长的 URL
        if (url.length() > 50) {
            url = url.substring(0, 47) + "...";
        }
        return "🌐 Fetching " + url;
    }
}
