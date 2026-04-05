package com.claudecode.lsp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * LSP JSON-RPC 客户端 —— 对应 claude-code/src/services/lsp/LSPClient.ts。
 * <p>
 * 通过 stdio 管道与 LSP 服务器进行 JSON-RPC 2.0 通信。
 * <p>
 * 协议格式：
 * <pre>
 * Content-Length: {length}\r\n
 * \r\n
 * {JSON-RPC body}
 * </pre>
 */
public class LSPClient implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(LSPClient.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String serverName;
    private final AtomicInteger requestIdCounter = new AtomicInteger(0);

    /** 挂起的请求：id → CompletableFuture */
    private final ConcurrentHashMap<Integer, CompletableFuture<JsonNode>> pendingRequests
            = new ConcurrentHashMap<>();

    /** 通知处理器：method → handler */
    private final ConcurrentHashMap<String, Consumer<JsonNode>> notificationHandlers
            = new ConcurrentHashMap<>();

    private Process process;
    private OutputStream stdin;
    private Thread readerThread;
    private volatile boolean running = false;
    private volatile boolean stopping = false;

    public LSPClient(String serverName) {
        this.serverName = serverName;
    }

    /**
     * 启动 LSP 服务器进程。
     */
    public void start(String command, java.util.List<String> args,
                      Map<String, String> env, String cwd) throws IOException {
        var cmd = new java.util.ArrayList<String>();
        cmd.add(command);
        if (args != null) cmd.addAll(args);

        ProcessBuilder pb = new ProcessBuilder(cmd)
                .redirectErrorStream(false);
        if (cwd != null) pb.directory(new File(cwd));
        if (env != null) pb.environment().putAll(env);

        process = pb.start();
        stdin = process.getOutputStream();
        running = true;

        // 启动 stdout 读取线程
        readerThread = Thread.ofVirtual()
                .name("lsp-reader-" + serverName)
                .start(() -> readLoop(process.getInputStream()));

        // stderr 日志线程
        Thread.ofVirtual()
                .name("lsp-stderr-" + serverName)
                .start(() -> {
                    try (var reader = new BufferedReader(
                            new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            if (!stopping) {
                                log.debug("[{}] stderr: {}", serverName, line);
                            }
                        }
                    } catch (IOException e) {
                        if (!stopping) log.debug("[{}] stderr read error", serverName, e);
                    }
                });

        log.info("[{}] LSP server started: {} {}", serverName, command, args);
    }

    /**
     * 发送 JSON-RPC 请求并等待响应。
     */
    public JsonNode sendRequest(String method, Object params) throws Exception {
        return sendRequest(method, params, 30_000);
    }

    public JsonNode sendRequest(String method, Object params, long timeoutMs) throws Exception {
        int id = requestIdCounter.incrementAndGet();
        CompletableFuture<JsonNode> future = new CompletableFuture<>();
        pendingRequests.put(id, future);

        try {
            ObjectNode request = MAPPER.createObjectNode();
            request.put("jsonrpc", "2.0");
            request.put("id", id);
            request.put("method", method);
            if (params != null) {
                request.set("params", MAPPER.valueToTree(params));
            }

            sendRaw(request);
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            pendingRequests.remove(id);
            throw new IOException("Request timed out: " + method);
        } finally {
            pendingRequests.remove(id);
        }
    }

    /**
     * 发送 JSON-RPC 通知（无需响应）。
     */
    public void sendNotification(String method, Object params) throws IOException {
        ObjectNode notification = MAPPER.createObjectNode();
        notification.put("jsonrpc", "2.0");
        notification.put("method", method);
        if (params != null) {
            notification.set("params", MAPPER.valueToTree(params));
        }
        sendRaw(notification);
    }

    /**
     * 注册通知处理器。
     */
    public void onNotification(String method, Consumer<JsonNode> handler) {
        notificationHandlers.put(method, handler);
    }

    /**
     * 发送初始化请求。
     */
    public JsonNode initialize(Map<String, Object> initParams) throws Exception {
        JsonNode result = sendRequest("initialize", initParams, 60_000);
        // 发送 initialized 通知
        sendNotification("initialized", Map.of());
        return result;
    }

    /**
     * 优雅关闭。
     */
    @Override
    public void close() {
        if (!running) return;
        stopping = true;
        running = false;

        try {
            // 发送 shutdown 请求
            sendRequest("shutdown", null, 5_000);
        } catch (Exception e) {
            log.debug("[{}] Shutdown request failed (expected if server crashed)", serverName);
        }

        try {
            // 发送 exit 通知
            sendNotification("exit", null);
        } catch (Exception ignored) {}

        // 取消所有挂起的请求
        pendingRequests.values().forEach(f ->
                f.completeExceptionally(new IOException("Client shutting down")));
        pendingRequests.clear();

        // 终止进程
        if (process != null && process.isAlive()) {
            process.destroy();
            try {
                if (!process.waitFor(3, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
            } catch (InterruptedException e) {
                process.destroyForcibly();
                Thread.currentThread().interrupt();
            }
        }

        log.info("[{}] LSP client closed", serverName);
    }

    // ==================== 内部实现 ====================

    private synchronized void sendRaw(JsonNode message) throws IOException {
        if (!running || stdin == null) {
            throw new IOException("Client not running");
        }

        byte[] body = MAPPER.writeValueAsBytes(message);
        String header = "Content-Length: " + body.length + "\r\n\r\n";

        stdin.write(header.getBytes(StandardCharsets.US_ASCII));
        stdin.write(body);
        stdin.flush();
    }

    private void readLoop(InputStream inputStream) {
        try (var reader = new BufferedReader(
                new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            while (running) {
                // 读取 header
                int contentLength = readContentLength(reader);
                if (contentLength < 0) break;

                // 读取 body
                char[] body = new char[contentLength];
                int read = 0;
                while (read < contentLength) {
                    int n = reader.read(body, read, contentLength - read);
                    if (n < 0) break;
                    read += n;
                }
                if (read < contentLength) break;

                // 解析 JSON-RPC
                try {
                    JsonNode message = MAPPER.readTree(new String(body));
                    handleMessage(message);
                } catch (Exception e) {
                    log.error("[{}] Failed to parse message", serverName, e);
                }
            }
        } catch (IOException e) {
            if (!stopping) {
                log.error("[{}] Read loop error", serverName, e);
            }
        }
    }

    private int readContentLength(BufferedReader reader) throws IOException {
        String line;
        int contentLength = -1;

        while ((line = reader.readLine()) != null) {
            if (line.isEmpty()) {
                // 空行 = header 结束
                return contentLength;
            }
            if (line.startsWith("Content-Length:")) {
                contentLength = Integer.parseInt(line.substring("Content-Length:".length()).trim());
            }
        }
        return -1; // EOF
    }

    private void handleMessage(JsonNode message) {
        if (message.has("id")) {
            // Response
            int id = message.get("id").asInt();
            CompletableFuture<JsonNode> future = pendingRequests.get(id);
            if (future != null) {
                if (message.has("error")) {
                    JsonNode error = message.get("error");
                    future.completeExceptionally(new LSPException(
                            error.path("code").asInt(),
                            error.path("message").asText("Unknown error")));
                } else {
                    future.complete(message.get("result"));
                }
            }
        } else if (message.has("method")) {
            // Notification
            String method = message.get("method").asText();
            Consumer<JsonNode> handler = notificationHandlers.get(method);
            if (handler != null) {
                try {
                    handler.accept(message.get("params"));
                } catch (Exception e) {
                    log.error("[{}] Notification handler error for {}", serverName, method, e);
                }
            }
        }
    }

    public boolean isRunning() {
        return running && process != null && process.isAlive();
    }

    /** LSP 协议错误 */
    public static class LSPException extends Exception {
        private final int code;
        public LSPException(int code, String message) {
            super(message);
            this.code = code;
        }
        public int getCode() { return code; }
        /** 内容被修改（可重试） */
        public boolean isContentModified() { return code == -32801; }
    }
}
