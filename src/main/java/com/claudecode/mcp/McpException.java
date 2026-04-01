package com.claudecode.mcp;

/**
 * MCP 相关异常 —— 统一封装 MCP 通信、协议解析和工具调用中的错误。
 */
public class McpException extends Exception {

    /** JSON-RPC 错误码（若源自 JSON-RPC error 响应） */
    private final int errorCode;

    public McpException(String message) {
        super(message);
        this.errorCode = -1;
    }

    public McpException(String message, Throwable cause) {
        super(message, cause);
        this.errorCode = -1;
    }

    public McpException(String message, int errorCode) {
        super(message);
        this.errorCode = errorCode;
    }

    public McpException(String message, int errorCode, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    /**
     * 获取 JSON-RPC 错误码。
     *
     * @return 错误码，若非 JSON-RPC 错误则返回 {@code -1}
     */
    public int getErrorCode() {
        return errorCode;
    }

    /**
     * 是否为 JSON-RPC 协议级错误。
     */
    public boolean isJsonRpcError() {
        return errorCode != -1;
    }
}
