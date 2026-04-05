package com.claudecode.lsp;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * LSP 服务器实例 —— 对应 claude-code/src/services/lsp/LSPServerInstance.ts。
 * <p>
 * 管理单个 LSP 服务器的完整生命周期：
 * <ul>
 *   <li>延迟启动（首次使用时才启动）</li>
 *   <li>初始化（发送 initialize + initialized）</li>
 *   <li>文件同步（didOpen/didChange/didSave/didClose）</li>
 *   <li>崩溃恢复（最多 maxRestarts 次）</li>
 * </ul>
 */
public class LSPServerInstance implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(LSPServerInstance.class);

    public enum State { STOPPED, STARTING, RUNNING, STOPPING, ERROR }

    private final String name;
    private final LSPServerConfig config;
    private final Path workspaceRoot;

    private LSPClient client;
    private volatile State state = State.STOPPED;
    private int crashCount = 0;
    private JsonNode serverCapabilities;

    /** 已打开的文件：URI → 版本号 */
    private final ConcurrentHashMap<String, Integer> openFiles = new ConcurrentHashMap<>();

    public LSPServerInstance(String name, LSPServerConfig config, Path workspaceRoot) {
        this.name = name;
        this.config = config;
        this.workspaceRoot = workspaceRoot;
    }

    /**
     * 确保服务器已启动（延迟启动逻辑）。
     *
     * @return true 如果服务器正在运行
     */
    public synchronized boolean ensureStarted() {
        if (state == State.RUNNING) return true;
        if (state == State.STARTING) return false;

        if (state == State.ERROR && crashCount >= config.maxRestarts()) {
            log.warn("[{}] Max restarts ({}) reached, not restarting", name, config.maxRestarts());
            return false;
        }

        try {
            start();
            return state == State.RUNNING;
        } catch (Exception e) {
            log.error("[{}] Failed to start", name, e);
            state = State.ERROR;
            return false;
        }
    }

    private void start() throws Exception {
        state = State.STARTING;
        log.info("[{}] Starting LSP server: {} {}", name, config.command(), config.args());

        client = new LSPClient(name);
        client.start(config.command(), config.args(), config.env(),
                config.workspaceFolder() != null ? config.workspaceFolder() : workspaceRoot.toString());

        // 注册诊断通知处理器（在 initialize 之前注册）
        // 具体的处理由 LSPServerManager 通过 onDiagnostics 回调完成

        // 发送初始化请求
        Map<String, Object> initParams = buildInitializeParams();
        JsonNode result = client.initialize(initParams);
        serverCapabilities = result != null ? result.get("capabilities") : null;

        state = State.RUNNING;
        log.info("[{}] LSP server initialized (capabilities: {})",
                name, serverCapabilities != null ? serverCapabilities.fieldNames() : "none");
    }

    private Map<String, Object> buildInitializeParams() {
        String wsUri = workspaceRoot.toUri().toString();
        String wsName = workspaceRoot.getFileName().toString();

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("processId", ProcessHandle.current().pid());
        params.put("rootPath", workspaceRoot.toString());
        params.put("rootUri", wsUri);
        params.put("initializationOptions", config.initializationOptions());
        params.put("workspaceFolders", List.of(Map.of("uri", wsUri, "name", wsName)));

        // 客户端能力声明
        Map<String, Object> capabilities = new LinkedHashMap<>();

        // workspace
        capabilities.put("workspace", Map.of(
                "configuration", false,
                "workspaceFolders", false
        ));

        // textDocument
        Map<String, Object> textDoc = new LinkedHashMap<>();
        textDoc.put("synchronization", Map.of("didSave", true, "willSave", false));
        textDoc.put("publishDiagnostics", Map.of("relatedInformation", true));
        textDoc.put("hover", Map.of("contentFormat", List.of("markdown", "plaintext")));
        textDoc.put("definition", Map.of("linkSupport", true));
        textDoc.put("references", Map.of());
        textDoc.put("documentSymbol", Map.of("hierarchicalDocumentSymbolSupport", true));
        textDoc.put("callHierarchy", Map.of());
        capabilities.put("textDocument", textDoc);

        // general
        capabilities.put("general", Map.of("positionEncodings", List.of("utf-16")));

        params.put("capabilities", capabilities);
        return params;
    }

    // ==================== 文件同步 ====================

    /**
     * 通知服务器打开文件。
     */
    public void openFile(String filePath, String content) throws IOException {
        if (state != State.RUNNING) return;

        String uri = pathToUri(filePath);
        String languageId = getLanguageId(filePath);
        int version = 1;
        openFiles.put(uri, version);

        client.sendNotification("textDocument/didOpen", Map.of(
                "textDocument", Map.of(
                        "uri", uri,
                        "languageId", languageId,
                        "version", version,
                        "text", content
                )
        ));
    }

    /**
     * 通知服务器文件内容变更。
     */
    public void changeFile(String filePath, String content) throws IOException {
        if (state != State.RUNNING) return;

        String uri = pathToUri(filePath);
        int version = openFiles.compute(uri, (k, v) -> v == null ? 1 : v + 1);

        client.sendNotification("textDocument/didChange", Map.of(
                "textDocument", Map.of("uri", uri, "version", version),
                "contentChanges", List.of(Map.of("text", content))
        ));
    }

    /**
     * 通知服务器文件已保存。
     */
    public void saveFile(String filePath) throws IOException {
        if (state != State.RUNNING) return;
        String uri = pathToUri(filePath);
        int version = openFiles.getOrDefault(uri, 1);

        client.sendNotification("textDocument/didSave", Map.of(
                "textDocument", Map.of("uri", uri, "version", version)
        ));
    }

    /**
     * 通知服务器关闭文件。
     */
    public void closeFile(String filePath) throws IOException {
        if (state != State.RUNNING) return;
        String uri = pathToUri(filePath);
        openFiles.remove(uri);

        client.sendNotification("textDocument/didClose", Map.of(
                "textDocument", Map.of("uri", uri)
        ));
    }

    // ==================== 请求 ====================

    /**
     * 发送请求到 LSP 服务器，支持内容修改重试。
     */
    public JsonNode sendRequest(String method, Object params) throws Exception {
        if (state != State.RUNNING) {
            throw new IOException("Server not running: " + name);
        }

        int retries = 3;
        long delay = 500;

        for (int i = 0; i < retries; i++) {
            try {
                return client.sendRequest(method, params);
            } catch (LSPClient.LSPException e) {
                if (e.isContentModified() && i < retries - 1) {
                    log.debug("[{}] Content modified, retrying in {}ms", name, delay);
                    Thread.sleep(delay);
                    delay *= 2;
                } else {
                    throw e;
                }
            }
        }
        throw new IOException("Request failed after retries: " + method);
    }

    /**
     * 注册通知处理器（转发到 LSPClient）。
     */
    public void onNotification(String method, java.util.function.Consumer<JsonNode> handler) {
        if (client != null) {
            client.onNotification(method, handler);
        }
    }

    // ==================== 生命周期 ====================

    @Override
    public void close() {
        if (state == State.STOPPED || state == State.STOPPING) return;
        state = State.STOPPING;

        if (client != null) {
            client.close();
        }
        openFiles.clear();

        state = State.STOPPED;
        log.info("[{}] LSP server stopped", name);
    }

    public void onCrash() {
        crashCount++;
        state = State.ERROR;
        log.warn("[{}] LSP server crashed (count: {}/{})", name, crashCount, config.maxRestarts());
    }

    // ==================== 工具方法 ====================

    private String pathToUri(String filePath) {
        return Path.of(filePath).toUri().toString();
    }

    private String getLanguageId(String filePath) {
        int dot = filePath.lastIndexOf('.');
        if (dot < 0) return "plaintext";
        String ext = filePath.substring(dot);
        String lang = config.languageForExtension(ext);
        return lang != null ? lang : "plaintext";
    }

    public boolean isFileOpen(String filePath) {
        return openFiles.containsKey(pathToUri(filePath));
    }

    public String getName() { return name; }
    public State getState() { return state; }
    public LSPServerConfig getConfig() { return config; }
    public JsonNode getCapabilities() { return serverCapabilities; }
    public int getCrashCount() { return crashCount; }
}
