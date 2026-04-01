package com.claudecode.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

/**
 * 基于 StdIO 的 MCP 传输实现 —— 通过子进程的 stdin/stdout 进行 JSON-RPC 通信。
 * <p>
 * 工作原理：
 * <ol>
 *   <li>启动外部 MCP 服务器进程（如 {@code npx -y @modelcontextprotocol/server-filesystem}）</li>
 *   <li>通过进程的 stdin 发送 JSON-RPC 消息（每行一条 JSON）</li>
 *   <li>通过独立读线程从 stdout 异步读取响应</li>
 *   <li>使用 {@link CompletableFuture} 按 {@code id} 字段进行请求-响应关联</li>
 * </ol>
 * <p>
 * 消息分隔：每条 JSON-RPC 消息占一行，以 {@code \n} 分隔。
 */
public class StdioTransport implements McpTransport {

    private static final Logger log = LoggerFactory.getLogger(StdioTransport.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 请求默认超时时间（秒） */
    private static final int DEFAULT_TIMEOUT_SECONDS = 30;

    /** MCP 服务器子进程 */
    private Process process;

    /** 子进程 stdin 写入流 */
    private BufferedWriter processStdin;

    /** 异步读取线程（stdout） */
    private Thread readerThread;

    /** 异步读取线程（stderr） */
    private Thread stderrThread;

    /** 待匹配的请求：id(String) -> CompletableFuture */
    private final ConcurrentHashMap<String, CompletableFuture<JsonNode>> pendingRequests =
            new ConcurrentHashMap<>();

    /** 启动命令 */
    private final String command;

    /** 启动参数 */
    private final List<String> args;

    /** 环境变量（可选） */
    private final Map<String, String> env;

    /** 连接状态 */
    private volatile boolean connected = false;

    /**
     * 创建 StdIO 传输实例。
     *
     * @param command 服务器可执行命令（如 "npx"）
     * @param args    命令参数列表
     * @param env     额外的环境变量，可为 {@code null}
     */
    public StdioTransport(String command, List<String> args, Map<String, String> env) {
        this.command = command;
        this.args = args != null ? List.copyOf(args) : List.of();
        this.env = env != null ? Map.copyOf(env) : Map.of();
    }

    /**
     * 启动 MCP 服务器子进程并开始监听 stdout。
     *
     * @throws McpException 进程启动失败
     */
    public void start() throws McpException {
        try {
            // 构建进程命令行
            var cmdList = new java.util.ArrayList<String>();
            cmdList.add(command);
            cmdList.addAll(args);

            log.info("Starting MCP server process: {}", String.join(" ", cmdList));

            ProcessBuilder pb = new ProcessBuilder(cmdList);
            pb.redirectErrorStream(false); // stderr 单独处理

            // 设置环境变量
            if (!env.isEmpty()) {
                pb.environment().putAll(env);
            }

            process = pb.start();

            // 初始化 stdin 写入器
            processStdin = new BufferedWriter(
                    new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));

            // 启动 stdout 异步读取线程
            readerThread = Thread.ofVirtual().name("mcp-stdio-reader").start(this::readLoop);

            // 启动 stderr 日志线程（仅记录日志，不参与协议通信）
            stderrThread = Thread.ofVirtual().name("mcp-stdio-stderr").start(this::stderrLoop);

            connected = true;
            log.info("MCP server process started (PID: {})", process.pid());

        } catch (IOException e) {
            throw new McpException("Failed to start MCP server process: " + e.getMessage(), e);
        }
    }

    /**
     * stdout 读取循环 —— 逐行读取 JSON-RPC 响应并分发到对应的 Future。
     */
    private void readLoop() {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {

            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) {
                    continue;
                }

                try {
                    JsonNode message = MAPPER.readTree(line);
                    handleMessage(message);
                } catch (Exception e) {
                    log.warn("Failed to parse MCP response: {}", line, e);
                }
            }
        } catch (IOException e) {
            if (connected) {
                log.warn("MCP stdout read interrupted: {}", e.getMessage());
            }
        } finally {
            connected = false;
            // 清理所有等待中的请求
            pendingRequests.forEach((id, future) ->
                    future.completeExceptionally(new McpException("MCP connection disconnected")));
            pendingRequests.clear();
        }
    }

    /**
     * stderr 读取循环 —— 将服务器 stderr 输出记录为日志。
     */
    private void stderrLoop() {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {

            String line;
            while ((line = reader.readLine()) != null) {
                log.debug("[MCP stderr] {}", line);
            }
        } catch (IOException e) {
            // stderr 读取结束，忽略
        }
    }

    /**
     * 处理从 stdout 读取的 JSON-RPC 消息。
     * <p>
     * 若消息包含 {@code id} 字段，则匹配到对应的待处理请求；
     * 若消息为通知（无 {@code id}），则记录日志。
     */
    private void handleMessage(JsonNode message) {
        // 检查是否有 id 字段（响应消息）
        JsonNode idNode = message.get("id");
        if (idNode != null && !idNode.isNull()) {
            // 统一转为 String，避免 Integer/String 类型不匹配导致的查找失败
            String id = idNode.asText();

            CompletableFuture<JsonNode> future = pendingRequests.remove(id);
            if (future != null) {
                future.complete(message);
            } else {
                log.warn("Received unmatched MCP response (id={}): {}", id, message);
            }
        } else {
            // 服务器主动通知（如 notifications/tools/list_changed）
            String method = message.has("method") ? message.get("method").asText() : "unknown";
            log.debug("Received MCP server notification: {}", method);
        }
    }

    @Override
    public JsonNode sendRequest(String jsonRpcRequest) throws McpException {
        if (!connected) {
            throw new McpException("MCP transport not connected");
        }

        String id = null;
        try {
            // 解析出请求 id（统一转为 String）
            JsonNode requestNode = MAPPER.readTree(jsonRpcRequest);
            JsonNode idNode = requestNode.get("id");
            if (idNode == null || idNode.isNull()) {
                throw new McpException("JSON-RPC request missing id field");
            }
            id = idNode.asText();

            // 注册 Future
            CompletableFuture<JsonNode> future = new CompletableFuture<>();
            pendingRequests.put(id, future);

            // 写入 stdin（一行 JSON + 换行符）
            synchronized (processStdin) {
                processStdin.write(jsonRpcRequest);
                processStdin.newLine();
                processStdin.flush();
            }

            log.debug("Sent MCP request (id={}): {}", id, truncate(jsonRpcRequest, 200));

            // 等待响应
            JsonNode response = future.get(DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS);

            // 检查 JSON-RPC error
            JsonNode errorNode = response.get("error");
            if (errorNode != null && !errorNode.isNull()) {
                int code = errorNode.has("code") ? errorNode.get("code").asInt() : -1;
                String msg = errorNode.has("message") ? errorNode.get("message").asText() : "Unknown error";
                throw new McpException("MCP server returned error: " + msg, code);
            }

            return response;

        } catch (McpException e) {
            throw e;
        } catch (TimeoutException e) {
            throw new McpException("MCP request timeout (" + DEFAULT_TIMEOUT_SECONDS + "s)", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof McpException mcp) {
                throw mcp;
            }
            throw new McpException("MCP request execution exception: " + cause.getMessage(), cause);
        } catch (Exception e) {
            throw new McpException("MCP request send failed: " + e.getMessage(), e);
        } finally {
            // 无论成功、超时或异常，都清理待处理请求，防止内存泄漏
            if (id != null) {
                pendingRequests.remove(id);
            }
        }
    }

    @Override
    public void sendNotification(String jsonRpcNotification) throws McpException {
        if (!connected) {
            throw new McpException("MCP transport not connected");
        }

        try {
            synchronized (processStdin) {
                processStdin.write(jsonRpcNotification);
                processStdin.newLine();
                processStdin.flush();
            }
            log.debug("Sent MCP notification: {}", truncate(jsonRpcNotification, 200));
        } catch (IOException e) {
            throw new McpException("MCP notification send failed: " + e.getMessage(), e);
        }
    }

    @Override
    public boolean isConnected() {
        return connected && process != null && process.isAlive();
    }

    @Override
    public void close() throws Exception {
        connected = false;
        log.info("Closing MCP StdIO transport...");

        // 关闭 stdin（通知服务器退出）
        if (processStdin != null) {
            try {
                processStdin.close();
            } catch (IOException e) {
                log.debug("Exception closing stdin: {}", e.getMessage());
            }
        }

        // 等待进程退出
        if (process != null && process.isAlive()) {
            boolean exited = process.waitFor(5, TimeUnit.SECONDS);
            if (!exited) {
                log.warn("MCP server process did not exit within 5s, force terminating");
                process.destroyForcibly();
                process.waitFor(3, TimeUnit.SECONDS);
            }
        }

        // 中断读取线程
        if (readerThread != null && readerThread.isAlive()) {
            readerThread.interrupt();
        }
        if (stderrThread != null && stderrThread.isAlive()) {
            stderrThread.interrupt();
        }

        // 清理待处理请求
        pendingRequests.forEach((id, future) ->
                future.completeExceptionally(new McpException("MCP transport closed")));
        pendingRequests.clear();

        log.info("MCP StdIO transport closed");
    }

    /**
     * 截断字符串用于日志输出。
     */
    private static String truncate(String s, int maxLen) {
        if (s == null) return "null";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }
}
