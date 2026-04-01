package com.claudecode.tool.impl;

import com.claudecode.mcp.McpClient;
import com.claudecode.mcp.McpException;
import com.claudecode.mcp.McpManager;
import com.claudecode.tool.Tool;
import com.claudecode.tool.ToolContext;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.*;

/**
 * MCP 工具桥接 —— 将 MCP 服务器暴露的远程工具桥接为本地 {@link Tool} 实例。
 * <p>
 * 使 AI 模型可以像调用本地工具一样调用 MCP 服务器上的工具。
 * 每个 {@code McpToolBridge} 实例对应一个 MCP 服务器上的一个工具。
 * <p>
 * 桥接流程：
 * <ol>
 *   <li>从 {@link McpClient.McpTool} 提取工具定义（名称、描述、参数 schema）</li>
 *   <li>实现 {@link Tool} 接口，使其可注册到 {@link com.claudecode.tool.ToolRegistry}</li>
 *   <li>执行时通过 {@link ToolContext} 中的 {@link McpManager} 路由到对应 MCP 服务器</li>
 * </ol>
 *
 * @see McpManager
 * @see McpClient.McpTool
 */
public class McpToolBridge implements Tool {

    /** MCP 管理器在 ToolContext 中的存储键 */
    public static final String MCP_MANAGER_KEY = "MCP_MANAGER";

    /** 工具所属的 MCP 服务器名称 */
    private final String serverName;

    /** MCP 工具名称（在 MCP 服务器上的名称） */
    private final String mcpToolName;

    /** 桥接后的本地工具名称（格式：mcp__{serverName}__{toolName}） */
    private final String bridgedName;

    /** MCP 工具描述 */
    private final String mcpDescription;

    /** MCP 工具的输入参数 JSON Schema（原始 JsonNode） */
    private final JsonNode mcpInputSchema;

    /** 缓存的 inputSchema JSON 字符串 */
    private final String inputSchemaString;

    /**
     * 创建 MCP 工具桥接实例。
     *
     * @param serverName MCP 服务器名称
     * @param mcpTool    MCP 工具定义
     */
    public McpToolBridge(String serverName, McpClient.McpTool mcpTool) {
        this.serverName = Objects.requireNonNull(serverName, "服务器名称不能为空");
        Objects.requireNonNull(mcpTool, "MCP 工具定义不能为空");

        this.mcpToolName = mcpTool.name();
        this.mcpDescription = mcpTool.description();
        this.mcpInputSchema = mcpTool.inputSchema();

        // 生成桥接名称：mcp__{serverName}__{toolName}
        // 使用双下划线分隔，避免与本地工具名称冲突
        this.bridgedName = "mcp__" + sanitizeName(serverName) + "__" + sanitizeName(mcpToolName);

        // 序列化 inputSchema
        this.inputSchemaString = buildInputSchema();
    }

    @Override
    public String name() {
        return bridgedName;
    }

    @Override
    public String description() {
        return String.format("[MCP:%s] %s", serverName, mcpDescription);
    }

    @Override
    public String inputSchema() {
        return inputSchemaString;
    }

    /**
     * 执行 MCP 远程工具调用。
     * <p>
     * 通过 {@link ToolContext} 中存储的 {@link McpManager} 实例
     * 路由调用到对应的 MCP 服务器。
     *
     * @param input   解析后的输入参数
     * @param context 工具执行上下文（必须包含 {@code MCP_MANAGER} 键）
     * @return MCP 工具的执行结果
     */
    @Override
    public String execute(Map<String, Object> input, ToolContext context) {
        // 从上下文获取 McpManager
        McpManager mcpManager = context.get(MCP_MANAGER_KEY);
        if (mcpManager == null) {
            return "错误: MCP 管理器未在上下文中注册 (key=" + MCP_MANAGER_KEY + ")";
        }

        try {
            return mcpManager.callTool(serverName, mcpToolName, input);
        } catch (McpException e) {
            return "MCP 工具调用失败 [" + serverName + "/" + mcpToolName + "]: " + e.getMessage();
        }
    }

    @Override
    public boolean isReadOnly() {
        // MCP 工具的读写属性未知，保守地标记为非只读
        return false;
    }

    @Override
    public String activityDescription(Map<String, Object> input) {
        return "🔌 [MCP:" + serverName + "] " + mcpToolName;
    }

    /**
     * 获取 MCP 服务器名称。
     */
    public String getServerName() {
        return serverName;
    }

    /**
     * 获取 MCP 工具原始名称。
     */
    public String getMcpToolName() {
        return mcpToolName;
    }

    /**
     * 从 MCP 工具定义批量创建桥接工具。
     *
     * @param serverName 服务器名称
     * @param mcpTools   MCP 工具集合
     * @return 桥接工具列表
     */
    public static List<McpToolBridge> createBridges(String serverName,
                                                     Collection<McpClient.McpTool> mcpTools) {
        return mcpTools.stream()
                .map(tool -> new McpToolBridge(serverName, tool))
                .toList();
    }

    /**
     * 构建 inputSchema JSON 字符串。
     * <p>
     * 如果 MCP 工具提供了 inputSchema，直接使用；
     * 否则生成一个接受任意参数的兜底 schema。
     */
    private String buildInputSchema() {
        if (mcpInputSchema != null && !mcpInputSchema.isNull()) {
            return mcpInputSchema.toString();
        }
        // 兜底：接受任意 JSON 对象
        return """
                {
                  "type": "object",
                  "properties": {},
                  "additionalProperties": true
                }""";
    }

    /**
     * 清理名称，替换非法字符为下划线。
     * 保留字母、数字、连字符和下划线。
     */
    private static String sanitizeName(String name) {
        return name.replaceAll("[^a-zA-Z0-9_-]", "_");
    }

    @Override
    public String toString() {
        return "McpToolBridge{" +
                "server='" + serverName + '\'' +
                ", tool='" + mcpToolName + '\'' +
                ", bridgedAs='" + bridgedName + '\'' +
                '}';
    }
}
