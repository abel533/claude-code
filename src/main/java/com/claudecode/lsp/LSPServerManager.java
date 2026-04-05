package com.claudecode.lsp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * LSP 服务器管理器 —— 对应 claude-code/src/services/lsp/LSPServerManager.ts。
 * <p>
 * 管理多个 LSP 服务器实例，按文件扩展名路由请求。
 * 支持延迟启动：只在首次使用某种文件类型时才启动对应服务器。
 */
public class LSPServerManager implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(LSPServerManager.class);

    private final Path workspaceRoot;
    private final Map<String, LSPServerInstance> servers = new ConcurrentHashMap<>();
    private final Map<String, String> extensionToServerName = new ConcurrentHashMap<>();
    private final Map<String, String> openedFiles = new ConcurrentHashMap<>();

    /** 诊断注册回调 */
    private LSPDiagnosticRegistry diagnosticRegistry;

    public LSPServerManager(Path workspaceRoot) {
        this.workspaceRoot = workspaceRoot;
    }

    public void setDiagnosticRegistry(LSPDiagnosticRegistry registry) {
        this.diagnosticRegistry = registry;
    }

    /**
     * 注册一个 LSP 服务器配置。
     * <p>
     * 服务器不会立即启动，而是在首次需要时延迟启动。
     */
    public void registerServer(LSPServerConfig config) {
        String name = config.name();
        LSPServerInstance instance = new LSPServerInstance(name, config, workspaceRoot);
        servers.put(name, instance);

        // 建立扩展名→服务器名的映射
        for (String ext : config.supportedExtensions()) {
            String existing = extensionToServerName.get(ext);
            if (existing != null) {
                log.warn("Extension {} already mapped to {}, overriding with {}",
                        ext, existing, name);
            }
            extensionToServerName.put(ext, name);
        }

        log.info("Registered LSP server: {} (extensions: {})", name, config.supportedExtensions());
    }

    /**
     * 获取处理指定文件的服务器（如果需要则延迟启动）。
     */
    public LSPServerInstance ensureServerForFile(String filePath) {
        String ext = getExtension(filePath);
        String serverName = extensionToServerName.get(ext);
        if (serverName == null) return null;

        LSPServerInstance instance = servers.get(serverName);
        if (instance == null) return null;

        // 延迟启动
        if (instance.getState() != LSPServerInstance.State.RUNNING) {
            if (!instance.ensureStarted()) {
                return null;
            }
            // 启动后注册诊断处理器
            registerDiagnosticHandler(instance);
        }

        return instance;
    }

    /**
     * 获取文件对应的服务器（不启动）。
     */
    public LSPServerInstance getServerForFile(String filePath) {
        String ext = getExtension(filePath);
        String serverName = extensionToServerName.get(ext);
        if (serverName == null) return null;
        return servers.get(serverName);
    }

    // ==================== 文件同步 ====================

    public void openFile(String filePath, String content) {
        LSPServerInstance server = ensureServerForFile(filePath);
        if (server == null) return;

        try {
            server.openFile(filePath, content);
            openedFiles.put(filePath, server.getName());
        } catch (Exception e) {
            log.error("Failed to open file in LSP: {}", filePath, e);
        }
    }

    public void changeFile(String filePath, String content) {
        String serverName = openedFiles.get(filePath);
        if (serverName == null) return;

        LSPServerInstance server = servers.get(serverName);
        if (server == null) return;

        try {
            server.changeFile(filePath, content);
        } catch (Exception e) {
            log.error("Failed to notify file change in LSP: {}", filePath, e);
        }
    }

    public void saveFile(String filePath) {
        String serverName = openedFiles.get(filePath);
        if (serverName == null) {
            // 文件可能是第一次出现，尝试打开
            return;
        }

        LSPServerInstance server = servers.get(serverName);
        if (server == null) return;

        try {
            server.saveFile(filePath);
        } catch (Exception e) {
            log.error("Failed to notify file save in LSP: {}", filePath, e);
        }

        // 保存后清除该文件的已交付诊断（触发重新检查）
        if (diagnosticRegistry != null) {
            diagnosticRegistry.clearDeliveredForFile(Path.of(filePath).toUri().toString());
        }
    }

    public void closeFile(String filePath) {
        String serverName = openedFiles.remove(filePath);
        if (serverName == null) return;

        LSPServerInstance server = servers.get(serverName);
        if (server == null) return;

        try {
            server.closeFile(filePath);
        } catch (Exception e) {
            log.error("Failed to close file in LSP: {}", filePath, e);
        }
    }

    public boolean isFileOpen(String filePath) {
        return openedFiles.containsKey(filePath);
    }

    // ==================== 请求路由 ====================

    /**
     * 向文件对应的 LSP 服务器发送请求。
     */
    public com.fasterxml.jackson.databind.JsonNode sendRequest(
            String filePath, String method, Object params) throws Exception {
        LSPServerInstance server = ensureServerForFile(filePath);
        if (server == null) {
            return null;
        }
        return server.sendRequest(method, params);
    }

    // ==================== 诊断处理 ====================

    private void registerDiagnosticHandler(LSPServerInstance instance) {
        if (diagnosticRegistry == null) return;

        instance.onNotification("textDocument/publishDiagnostics", params -> {
            try {
                String uri = params.get("uri").asText();
                var diagnosticsNode = params.get("diagnostics");

                List<LSPDiagnosticRegistry.Diagnostic> diagnostics = new ArrayList<>();
                if (diagnosticsNode != null && diagnosticsNode.isArray()) {
                    for (var diagNode : diagnosticsNode) {
                        String message = diagNode.path("message").asText("");
                        int severity = diagNode.path("severity").asInt(1);
                        String source = diagNode.path("source").asText(null);
                        String code = diagNode.path("code").asText(null);

                        var range = diagNode.get("range");
                        int startLine = 0, startChar = 0, endLine = 0, endChar = 0;
                        if (range != null) {
                            var start = range.get("start");
                            var end = range.get("end");
                            if (start != null) {
                                startLine = start.path("line").asInt();
                                startChar = start.path("character").asInt();
                            }
                            if (end != null) {
                                endLine = end.path("line").asInt();
                                endChar = end.path("character").asInt();
                            }
                        }

                        diagnostics.add(new LSPDiagnosticRegistry.Diagnostic(
                                message, mapSeverity(severity),
                                startLine, startChar, endLine, endChar,
                                source, code));
                    }
                }

                // 转换 URI 到文件路径
                String filePath = uriToPath(uri);
                diagnosticRegistry.registerDiagnostics(
                        instance.getName(), filePath, diagnostics);
            } catch (Exception e) {
                log.error("[{}] Failed to process diagnostics", instance.getName(), e);
            }
        });
    }

    // ==================== 生命周期 ====================

    @Override
    public void close() {
        log.info("Shutting down LSP server manager ({} servers)", servers.size());
        for (LSPServerInstance server : servers.values()) {
            try {
                server.close();
            } catch (Exception e) {
                log.error("Failed to stop LSP server: {}", server.getName(), e);
            }
        }
        servers.clear();
        extensionToServerName.clear();
        openedFiles.clear();
    }

    public boolean isConnected() {
        return servers.values().stream()
                .anyMatch(s -> s.getState() == LSPServerInstance.State.RUNNING);
    }

    public Map<String, LSPServerInstance> getAllServers() {
        return Map.copyOf(servers);
    }

    // ==================== 工具方法 ====================

    private static String getExtension(String filePath) {
        int dot = filePath.lastIndexOf('.');
        return dot >= 0 ? filePath.substring(dot) : "";
    }

    private static String mapSeverity(int lspSeverity) {
        return switch (lspSeverity) {
            case 1 -> "Error";
            case 2 -> "Warning";
            case 3 -> "Info";
            case 4 -> "Hint";
            default -> "Error";
        };
    }

    private static String uriToPath(String uri) {
        if (uri.startsWith("file:///")) {
            String path = uri.substring("file:///".length());
            // Windows: file:///C:/path → C:/path
            if (path.length() > 1 && path.charAt(1) == ':') {
                return path.replace('/', '\\');
            }
            return "/" + path;
        }
        if (uri.startsWith("file://")) {
            return uri.substring("file://".length());
        }
        return uri;
    }
}
