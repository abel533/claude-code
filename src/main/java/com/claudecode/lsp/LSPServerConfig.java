package com.claudecode.lsp;

import java.util.List;
import java.util.Map;

/**
 * LSP 服务器配置 —— 对应 claude-code/src/services/lsp/config.ts 的 LspServerConfig。
 *
 * @param name                   服务器名称（唯一标识）
 * @param command                可执行文件路径
 * @param args                   命令行参数
 * @param extensionToLanguage    文件扩展名→语言ID映射 (e.g., {".ts": "typescript"})
 * @param transport              传输方式: "stdio" (默认) 或 "socket"
 * @param env                    环境变量
 * @param initializationOptions  LSP 初始化选项
 * @param workspaceFolder        工作目录
 * @param startupTimeout         启动超时（毫秒）
 * @param maxRestarts            最大重启次数（默认 3）
 */
public record LSPServerConfig(
        String name,
        String command,
        List<String> args,
        Map<String, String> extensionToLanguage,
        String transport,
        Map<String, String> env,
        Map<String, Object> initializationOptions,
        String workspaceFolder,
        long startupTimeout,
        int maxRestarts
) {
    public LSPServerConfig {
        if (command == null || command.isBlank()) {
            throw new IllegalArgumentException("LSP server command is required");
        }
        if (extensionToLanguage == null || extensionToLanguage.isEmpty()) {
            throw new IllegalArgumentException("At least one extensionToLanguage mapping is required");
        }
        if (args == null) args = List.of();
        if (transport == null) transport = "stdio";
        if (env == null) env = Map.of();
        if (initializationOptions == null) initializationOptions = Map.of();
        if (startupTimeout <= 0) startupTimeout = 30_000;
        if (maxRestarts <= 0) maxRestarts = 3;
    }

    /** 获取此服务器支持的所有文件扩展名 */
    public List<String> supportedExtensions() {
        return List.copyOf(extensionToLanguage.keySet());
    }

    /** 获取文件扩展名对应的语言ID */
    public String languageForExtension(String ext) {
        return extensionToLanguage.get(ext);
    }
}
