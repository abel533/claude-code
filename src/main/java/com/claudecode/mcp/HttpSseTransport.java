package com.claudecode.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * HTTP + SSE 传输层 —— 对应 claude-code 中的 HTTP 传输实现。
 * <p>
 * 用于连接基于 HTTP 的 MCP 服务器，使用 SSE (Server-Sent Events) 接收通知，
 * 使用 HTTP POST 发送请求。
 * <p>
 * MCP HTTP 传输协议流程：
 * <ol>
 *   <li>建立 SSE 连接获取 endpoint URL</li>
 *   <li>通过 POST 请求发送 JSON-RPC 消息到 endpoint</li>
 *   <li>通过 SSE 流接收响应和通知</li>
 * </ol>
 *
 * @see McpTransport
 */
public class HttpSseTransport implements McpTransport {

    private static final Logger log = LoggerFactory.getLogger(HttpSseTransport.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);

    private final String baseUrl;
    private final HttpClient httpClient;
    private final Map<String, String> headers;
    private final Duration timeout;

    /** Endpoint URL received from SSE connection */
    private volatile String messageEndpoint;

    /** Pending response futures: request id -> CompletableFuture */
    private final ConcurrentHashMap<String, CompletableFuture<JsonNode>> pendingRequests =
            new ConcurrentHashMap<>();

    /** SSE connection state */
    private final AtomicBoolean connected = new AtomicBoolean(false);
    private volatile Future<?> sseListenerFuture;
    private final ExecutorService sseExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "mcp-sse-listener");
        t.setDaemon(true);
        return t;
    });

    /**
     * 创建 HTTP+SSE 传输层。
     *
     * @param baseUrl MCP 服务器的基础 URL (e.g., "http://localhost:3000")
     */
    public HttpSseTransport(String baseUrl) {
        this(baseUrl, Map.of(), DEFAULT_TIMEOUT);
    }

    /**
     * 创建 HTTP+SSE 传输层（自定义头和超时）。
     *
     * @param baseUrl MCP 服务器的基础 URL
     * @param headers 自定义 HTTP 头（如认证 token）
     * @param timeout 请求超时时间
     */
    public HttpSseTransport(String baseUrl, Map<String, String> headers, Duration timeout) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.headers = headers != null ? headers : Map.of();
        this.timeout = timeout != null ? timeout : DEFAULT_TIMEOUT;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(this.timeout)
                .build();
    }

    /**
     * 连接到 SSE 端点并开始监听。
     * 必须在发送请求前调用。
     */
    public void connect() throws McpException {
        if (connected.get()) return;

        log.info("Connecting to MCP HTTP server at {}", baseUrl);

        // Start SSE listener
        sseListenerFuture = sseExecutor.submit(() -> {
            try {
                listenSse();
            } catch (Exception e) {
                if (connected.get()) {
                    log.warn("SSE listener error: {}", e.getMessage());
                }
            }
        });

        // Wait for endpoint URL
        int waitMs = 0;
        while (messageEndpoint == null && waitMs < timeout.toMillis()) {
            try {
                Thread.sleep(100);
                waitMs += 100;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new McpException("Interrupted while waiting for SSE endpoint");
            }
        }

        if (messageEndpoint == null) {
            throw new McpException("Timeout waiting for SSE endpoint from " + baseUrl);
        }

        connected.set(true);
        log.info("Connected to MCP HTTP server, endpoint: {}", messageEndpoint);
    }

    /**
     * SSE 监听循环 —— 连接到 /sse 端点并解析事件流。
     */
    private void listenSse() throws Exception {
        String sseUrl = baseUrl + "/sse";
        log.debug("Starting SSE listener at {}", sseUrl);

        var requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(sseUrl))
                .timeout(Duration.ofMinutes(30)) // Long timeout for SSE
                .GET();

        // Add custom headers
        for (var entry : headers.entrySet()) {
            requestBuilder.header(entry.getKey(), entry.getValue());
        }

        HttpRequest request = requestBuilder.build();
        HttpResponse<java.io.InputStream> response = httpClient.send(
                request, HttpResponse.BodyHandlers.ofInputStream());

        if (response.statusCode() != 200) {
            throw new McpException("SSE connection failed with status " + response.statusCode());
        }

        try (var reader = new BufferedReader(
                new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {

            String eventType = null;
            StringBuilder dataBuffer = new StringBuilder();

            String line;
            while ((line = reader.readLine()) != null && connected.get()) {
                if (line.startsWith("event:")) {
                    eventType = line.substring(6).strip();
                } else if (line.startsWith("data:")) {
                    dataBuffer.append(line.substring(5).strip());
                } else if (line.isEmpty() && dataBuffer.length() > 0) {
                    // End of event
                    handleSseEvent(eventType, dataBuffer.toString());
                    eventType = null;
                    dataBuffer.setLength(0);
                }
            }
        }
    }

    /**
     * 处理 SSE 事件。
     */
    private void handleSseEvent(String eventType, String data) {
        if ("endpoint".equals(eventType)) {
            // Server sends the POST endpoint URL
            if (data.startsWith("http://") || data.startsWith("https://")) {
                messageEndpoint = data;
            } else {
                messageEndpoint = baseUrl + (data.startsWith("/") ? data : "/" + data);
            }
            log.debug("Received SSE endpoint: {}", messageEndpoint);
        } else if ("message".equals(eventType) || eventType == null) {
            // JSON-RPC response or notification
            try {
                JsonNode json = MAPPER.readTree(data);
                if (json.has("id")) {
                    // It's a response to a pending request
                    String id = json.get("id").asText();
                    CompletableFuture<JsonNode> future = pendingRequests.remove(id);
                    if (future != null) {
                        future.complete(json);
                    } else {
                        log.debug("Received response for unknown request id: {}", id);
                    }
                } else {
                    // It's a notification — log it
                    String method = json.has("method") ? json.get("method").asText() : "unknown";
                    log.debug("Received SSE notification: {}", method);
                }
            } catch (Exception e) {
                log.debug("Failed to parse SSE message data: {}", data, e);
            }
        }
    }

    @Override
    public JsonNode sendRequest(String jsonRpcRequest) throws McpException {
        if (!connected.get() || messageEndpoint == null) {
            connect();
        }

        try {
            // Extract request ID for response matching
            JsonNode requestNode = MAPPER.readTree(jsonRpcRequest);
            String requestId = requestNode.has("id") ? requestNode.get("id").asText() : null;

            // Register pending response
            CompletableFuture<JsonNode> responseFuture = new CompletableFuture<>();
            if (requestId != null) {
                pendingRequests.put(requestId, responseFuture);
            }

            // Send HTTP POST
            var httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(messageEndpoint))
                    .timeout(timeout)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonRpcRequest))
                    .build();

            // Add custom headers
            // Note: HttpRequest is immutable, headers must be set at build time
            // For simplicity, rebuild if we have custom headers
            if (!headers.isEmpty()) {
                var builder = HttpRequest.newBuilder()
                        .uri(URI.create(messageEndpoint))
                        .timeout(timeout)
                        .header("Content-Type", "application/json");
                for (var entry : headers.entrySet()) {
                    builder.header(entry.getKey(), entry.getValue());
                }
                httpRequest = builder.POST(HttpRequest.BodyPublishers.ofString(jsonRpcRequest)).build();
            }

            HttpResponse<String> httpResponse = httpClient.send(
                    httpRequest, HttpResponse.BodyHandlers.ofString());

            if (httpResponse.statusCode() >= 400) {
                pendingRequests.remove(requestId);
                throw new McpException("HTTP error " + httpResponse.statusCode()
                        + ": " + httpResponse.body());
            }

            // If the HTTP response body contains JSON-RPC response, use it directly
            String body = httpResponse.body();
            if (body != null && !body.isBlank()) {
                try {
                    JsonNode directResponse = MAPPER.readTree(body);
                    if (directResponse.has("result") || directResponse.has("error")) {
                        pendingRequests.remove(requestId);
                        return directResponse;
                    }
                } catch (Exception ignored) {
                    // Not a JSON response, wait for SSE
                }
            }

            // Wait for response via SSE
            if (requestId != null) {
                try {
                    return responseFuture.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
                } catch (TimeoutException e) {
                    pendingRequests.remove(requestId);
                    throw new McpException("Timeout waiting for response to request " + requestId);
                }
            }

            // No ID means notification — return empty
            return MAPPER.createObjectNode();

        } catch (McpException e) {
            throw e;
        } catch (Exception e) {
            throw new McpException("HTTP request failed: " + e.getMessage(), e);
        }
    }

    @Override
    public void sendNotification(String jsonRpcNotification) throws McpException {
        if (!connected.get() || messageEndpoint == null) {
            connect();
        }

        try {
            var httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(messageEndpoint))
                    .timeout(timeout)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonRpcNotification))
                    .build();

            HttpResponse<String> response = httpClient.send(
                    httpRequest, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() >= 400) {
                throw new McpException("HTTP notification failed with status " + response.statusCode());
            }
        } catch (McpException e) {
            throw e;
        } catch (Exception e) {
            throw new McpException("Failed to send notification: " + e.getMessage(), e);
        }
    }

    @Override
    public boolean isConnected() {
        return connected.get() && messageEndpoint != null;
    }

    @Override
    public void close() throws Exception {
        connected.set(false);
        pendingRequests.values().forEach(f ->
                f.completeExceptionally(new McpException("Transport closed")));
        pendingRequests.clear();

        if (sseListenerFuture != null) {
            sseListenerFuture.cancel(true);
        }
        sseExecutor.shutdownNow();
        log.info("HttpSseTransport closed");
    }
}
