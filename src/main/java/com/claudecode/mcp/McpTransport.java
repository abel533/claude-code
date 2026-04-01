package com.claudecode.mcp;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * MCP 传输层接口 —— 抽象不同的通信方式（StdIO / SSE / HTTP）。
 * <p>
 * MCP 协议底层基于 JSON-RPC 2.0，传输层负责将 JSON-RPC 消息
 * 发送给 MCP 服务器并接收响应。不同传输实现（如 StdIO 子进程、
 * HTTP+SSE 远程连接）均通过此接口统一暴露。
 *
 * @see StdioTransport
 */
public interface McpTransport extends AutoCloseable {

    /**
     * 发送 JSON-RPC 请求并等待响应。
     * <p>
     * 实现应根据请求中的 {@code id} 字段将响应与请求关联。
     *
     * @param jsonRpcRequest 完整的 JSON-RPC 2.0 请求字符串
     * @return 服务器返回的 JSON-RPC 响应节点
     * @throws McpException 通信异常或超时
     */
    JsonNode sendRequest(String jsonRpcRequest) throws McpException;

    /**
     * 发送 JSON-RPC 通知（无需响应）。
     * <p>
     * 通知消息不包含 {@code id} 字段，服务器无需回复。
     *
     * @param jsonRpcNotification 完整的 JSON-RPC 2.0 通知字符串
     * @throws McpException 通信异常
     */
    void sendNotification(String jsonRpcNotification) throws McpException;

    /**
     * 传输层是否已连接且可用。
     *
     * @return 如果底层连接仍然活跃则返回 {@code true}
     */
    boolean isConnected();
}
