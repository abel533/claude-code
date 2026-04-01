package com.claudecode.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * MCP 客户端 —— 对应 claude-code 中的 mcp/ 模块。
 * <p>
 * 负责与单个 MCP 服务器的完整生命周期管理：
 * <ol>
 *   <li>通过 {@link McpTransport} 建立连接</li>
 *   <li>发送 {@code initialize} 握手请求</li>
 *   <li>发现服务器提供的工具（{@code tools/list}）和资源（{@code resources/list}）</li>
 *   <li>调用工具（{@code tools/call}）和读取资源（{@code resources/read}）</li>
 * </ol>
 * <p>
 * MCP 协议使用 JSON-RPC 2.0 格式通信。
 *
 * @see McpTransport
 * @see McpManager
 */
public class McpClient implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(McpClient.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** JSON-RPC 请求 ID 生成器 */
    private final AtomicInteger idCounter = new AtomicInteger(1);

    /** 服务器名称标识 */
    private final String serverName;

    /** 底层传输层 */
    private final McpTransport transport;

    /** 已发现的工具集合：toolName -> McpTool */
    private final Map<String, McpTool> tools = new ConcurrentHashMap<>();

    /** 已发现的资源集合：uri -> McpResource */
    private final Map<String, McpResource> resources = new ConcurrentHashMap<>();

    /** 服务器能力信息 */
    private JsonNode serverCapabilities;

    /** 服务器信息 */
    private JsonNode serverInfo;

    /** 是否已完成初始化 */
    private volatile boolean initialized = false;

    /**
     * 创建 MCP 客户端。
     *
     * @param serverName 服务器标识名称
     * @param transport  传输层实现
     */
    public McpClient(String serverName, McpTransport transport) {
        this.serverName = Objects.requireNonNull(serverName, "服务器名称不能为空");
        this.transport = Objects.requireNonNull(transport, "传输层不能为空");
    }

    /**
     * 初始化连接 —— MCP 协议握手流程。
     * <p>
     * 步骤：
     * <ol>
     *   <li>发送 {@code initialize} 请求，声明客户端能力和协议版本</li>
     *   <li>解析服务器返回的能力信息</li>
     *   <li>发送 {@code notifications/initialized} 通知</li>
     *   <li>发现服务器提供的工具和资源</li>
     * </ol>
     *
     * @throws McpException 初始化失败
     */
    public void initialize() throws McpException {
        log.info("正在初始化 MCP 服务器 '{}'...", serverName);

        // 1. 发送 initialize 请求
        int initId = nextId();
        var initRequest = Map.of(
                "jsonrpc", "2.0",
                "id", initId,
                "method", "initialize",
                "params", Map.of(
                        "protocolVersion", "2024-11-05",
                        "capabilities", Map.of(),
                        "clientInfo", Map.of(
                                "name", "claude-code-java",
                                "version", "1.0.0"
                        )
                )
        );

        JsonNode response;
        try {
            response = transport.sendRequest(MAPPER.writeValueAsString(initRequest));
        } catch (Exception e) {
            throw new McpException("MCP initialize 请求失败: " + e.getMessage(), e);
        }

        // 2. 解析服务器能力
        JsonNode result = response.get("result");
        if (result != null) {
            serverCapabilities = result.get("capabilities");
            serverInfo = result.get("serverInfo");
            String serverVersion = result.has("protocolVersion")
                    ? result.get("protocolVersion").asText() : "unknown";
            log.info("MCP 服务器 '{}' 协议版本: {}", serverName, serverVersion);
            if (serverInfo != null) {
                log.info("MCP 服务器信息: {}", serverInfo);
            }
        }

        // 3. 发送 initialized 通知
        var initializedNotif = Map.of(
                "jsonrpc", "2.0",
                "method", "notifications/initialized"
        );
        try {
            transport.sendNotification(MAPPER.writeValueAsString(initializedNotif));
        } catch (Exception e) {
            throw new McpException("发送 initialized 通知失败: " + e.getMessage(), e);
        }

        // 4. 发现工具
        discoverTools();

        // 5. 发现资源
        discoverResources();

        initialized = true;
        log.info("MCP 服务器 '{}' 初始化完成: {} 个工具, {} 个资源",
                serverName, tools.size(), resources.size());
    }

    /**
     * 发现服务器提供的工具 —— 发送 {@code tools/list} 请求。
     */
    private void discoverTools() throws McpException {
        // 检查服务器是否支持 tools 能力
        if (serverCapabilities != null
                && serverCapabilities.has("tools")
                && serverCapabilities.get("tools").isObject()) {
            // 服务器声明支持工具
        } else if (serverCapabilities != null && !serverCapabilities.has("tools")) {
            log.debug("MCP 服务器 '{}' 未声明 tools 能力，尝试发现工具", serverName);
        }

        int id = nextId();
        var request = Map.of(
                "jsonrpc", "2.0",
                "id", id,
                "method", "tools/list",
                "params", Map.of()
        );

        try {
            JsonNode response = transport.sendRequest(MAPPER.writeValueAsString(request));
            JsonNode result = response.get("result");
            if (result != null && result.has("tools")) {
                ArrayNode toolsArray = (ArrayNode) result.get("tools");
                for (JsonNode toolNode : toolsArray) {
                    String name = toolNode.get("name").asText();
                    String description = toolNode.has("description")
                            ? toolNode.get("description").asText() : "";
                    JsonNode inputSchema = toolNode.get("inputSchema");

                    tools.put(name, new McpTool(name, description, inputSchema));
                    log.debug("发现 MCP 工具: {} - {}", name, description);
                }
            }
        } catch (McpException e) {
            // tools/list 可能不被支持，记录警告但不中断初始化
            if (e.isJsonRpcError() && e.getErrorCode() == -32601) {
                log.debug("MCP 服务器 '{}' 不支持 tools/list", serverName);
            } else {
                log.warn("发现 MCP 工具失败: {}", e.getMessage());
            }
        } catch (Exception e) {
            log.warn("发现 MCP 工具时序列化异常: {}", e.getMessage());
        }
    }

    /**
     * 发现服务器提供的资源 —— 发送 {@code resources/list} 请求。
     */
    private void discoverResources() throws McpException {
        // 检查服务器是否支持 resources 能力
        if (serverCapabilities != null
                && serverCapabilities.has("resources")
                && serverCapabilities.get("resources").isObject()) {
            // 服务器声明支持资源
        } else if (serverCapabilities != null && !serverCapabilities.has("resources")) {
            log.debug("MCP 服务器 '{}' 未声明 resources 能力，跳过资源发现", serverName);
            return;
        }

        int id = nextId();
        var request = Map.of(
                "jsonrpc", "2.0",
                "id", id,
                "method", "resources/list",
                "params", Map.of()
        );

        try {
            JsonNode response = transport.sendRequest(MAPPER.writeValueAsString(request));
            JsonNode result = response.get("result");
            if (result != null && result.has("resources")) {
                ArrayNode resourcesArray = (ArrayNode) result.get("resources");
                for (JsonNode resNode : resourcesArray) {
                    String uri = resNode.get("uri").asText();
                    String name = resNode.has("name") ? resNode.get("name").asText() : uri;
                    String description = resNode.has("description")
                            ? resNode.get("description").asText() : "";
                    String mimeType = resNode.has("mimeType")
                            ? resNode.get("mimeType").asText() : "text/plain";

                    resources.put(uri, new McpResource(uri, name, description, mimeType));
                    log.debug("发现 MCP 资源: {} ({})", name, uri);
                }
            }
        } catch (McpException e) {
            if (e.isJsonRpcError() && e.getErrorCode() == -32601) {
                log.debug("MCP 服务器 '{}' 不支持 resources/list", serverName);
            } else {
                log.warn("发现 MCP 资源失败: {}", e.getMessage());
            }
        } catch (Exception e) {
            log.warn("发现 MCP 资源时序列化异常: {}", e.getMessage());
        }
    }

    /**
     * 调用 MCP 工具 —— 发送 {@code tools/call} 请求。
     *
     * @param toolName  工具名称
     * @param arguments 工具参数（键值对）
     * @return 工具执行结果文本
     * @throws McpException 调用失败或工具不存在
     */
    public String callTool(String toolName, Map<String, Object> arguments) throws McpException {
        if (!initialized) {
            throw new McpException("MCP 客户端尚未初始化");
        }
        if (!tools.containsKey(toolName)) {
            throw new McpException("MCP 工具不存在: " + toolName);
        }

        int id = nextId();
        var request = Map.of(
                "jsonrpc", "2.0",
                "id", id,
                "method", "tools/call",
                "params", Map.of(
                        "name", toolName,
                        "arguments", arguments != null ? arguments : Map.of()
                )
        );

        try {
            log.debug("调用 MCP 工具: {} (参数: {})", toolName, arguments);
            JsonNode response = transport.sendRequest(MAPPER.writeValueAsString(request));
            JsonNode result = response.get("result");

            if (result == null) {
                return "";
            }

            // MCP tools/call 返回 { content: [{ type: "text", text: "..." }, ...] }
            if (result.has("content")) {
                StringBuilder sb = new StringBuilder();
                for (JsonNode contentItem : result.get("content")) {
                    String type = contentItem.has("type") ? contentItem.get("type").asText() : "text";
                    if ("text".equals(type) && contentItem.has("text")) {
                        if (!sb.isEmpty()) {
                            sb.append("\n");
                        }
                        sb.append(contentItem.get("text").asText());
                    }
                }

                // 检查 isError 标志
                if (result.has("isError") && result.get("isError").asBoolean()) {
                    throw new McpException("MCP 工具 '" + toolName + "' 执行出错: " + sb);
                }

                return sb.toString();
            }

            // 兜底：直接返回 result 的文本形式
            return result.toString();

        } catch (McpException e) {
            throw e;
        } catch (Exception e) {
            throw new McpException("调用 MCP 工具 '" + toolName + "' 失败: " + e.getMessage(), e);
        }
    }

    /**
     * 读取 MCP 资源 —— 发送 {@code resources/read} 请求。
     *
     * @param uri 资源 URI
     * @return 资源内容文本
     * @throws McpException 读取失败或资源不存在
     */
    public String readResource(String uri) throws McpException {
        if (!initialized) {
            throw new McpException("MCP 客户端尚未初始化");
        }

        int id = nextId();
        var request = Map.of(
                "jsonrpc", "2.0",
                "id", id,
                "method", "resources/read",
                "params", Map.of("uri", uri)
        );

        try {
            log.debug("读取 MCP 资源: {}", uri);
            JsonNode response = transport.sendRequest(MAPPER.writeValueAsString(request));
            JsonNode result = response.get("result");

            if (result == null) {
                return "";
            }

            // MCP resources/read 返回 { contents: [{ uri, text/blob }] }
            if (result.has("contents")) {
                StringBuilder sb = new StringBuilder();
                for (JsonNode contentItem : result.get("contents")) {
                    if (contentItem.has("text")) {
                        if (!sb.isEmpty()) {
                            sb.append("\n");
                        }
                        sb.append(contentItem.get("text").asText());
                    }
                }
                return sb.toString();
            }

            return result.toString();

        } catch (McpException e) {
            throw e;
        } catch (Exception e) {
            throw new McpException("读取 MCP 资源 '" + uri + "' 失败: " + e.getMessage(), e);
        }
    }

    /**
     * 获取所有已发现的工具（不可变视图）。
     */
    public Collection<McpTool> getTools() {
        return Collections.unmodifiableCollection(tools.values());
    }

    /**
     * 获取所有已发现的资源（不可变视图）。
     */
    public Collection<McpResource> getResources() {
        return Collections.unmodifiableCollection(resources.values());
    }

    /**
     * 按名称查找工具。
     *
     * @param toolName 工具名称
     * @return 工具定义，若不存在则返回 {@link Optional#empty()}
     */
    public Optional<McpTool> findTool(String toolName) {
        return Optional.ofNullable(tools.get(toolName));
    }

    /** 获取服务器名称标识 */
    public String getServerName() {
        return serverName;
    }

    /** 是否已完成初始化 */
    public boolean isInitialized() {
        return initialized;
    }

    /** 传输层是否仍然连接 */
    public boolean isConnected() {
        return transport.isConnected();
    }

    /** 获取服务器能力信息 */
    public JsonNode getServerCapabilities() {
        return serverCapabilities;
    }

    /** 获取服务器信息 */
    public JsonNode getServerInfo() {
        return serverInfo;
    }

    @Override
    public void close() throws Exception {
        initialized = false;
        transport.close();
        log.info("MCP 客户端 '{}' 已关闭", serverName);
    }

    /** 生成下一个 JSON-RPC 请求 ID */
    private int nextId() {
        return idCounter.getAndIncrement();
    }

    // ========== 内部记录类型 ==========

    /**
     * MCP 工具定义 —— 服务器暴露的可调用工具。
     *
     * @param name        工具名称
     * @param description 工具描述
     * @param inputSchema 输入参数的 JSON Schema
     */
    public record McpTool(String name, String description, JsonNode inputSchema) {
    }

    /**
     * MCP 资源定义 —— 服务器暴露的可读取资源。
     *
     * @param uri         资源 URI
     * @param name        资源名称
     * @param description 资源描述
     * @param mimeType    MIME 类型
     */
    public record McpResource(String uri, String name, String description, String mimeType) {
    }
}
