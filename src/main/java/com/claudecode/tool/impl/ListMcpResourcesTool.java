package com.claudecode.tool.impl;

import com.claudecode.mcp.McpClient;
import com.claudecode.mcp.McpManager;
import com.claudecode.tool.Tool;
import com.claudecode.tool.ToolContext;

import java.util.Map;

/**
 * ListMcpResources 工具 —— 列出 MCP 服务器提供的资源。
 * <p>
 * 对应 claude-code 中浏览 MCP 资源的功能。
 * 显示所有已连接 MCP 服务器的资源列表，包括 URI、名称、描述和 MIME 类型。
 */
public class ListMcpResourcesTool implements Tool {

    @Override
    public String name() {
        return "ListMcpResources";
    }

    @Override
    public String description() {
        return """
            List resources available from connected MCP (Model Context Protocol) servers.
            Shows all resources with their URIs, names, descriptions, and MIME types.
            Use this to discover what data sources are available before reading them.
            Optionally filter by server name.""";
    }

    @Override
    public String inputSchema() {
        return """
            {
              "type": "object",
              "properties": {
                "server": {
                  "type": "string",
                  "description": "Optional: filter resources by MCP server name"
                }
              }
            }""";
    }

    @Override
    public String execute(Map<String, Object> input, ToolContext context) {
        McpManager mcpManager = context.getOrDefault("MCP_MANAGER", null);
        if (mcpManager == null) {
            return "No MCP servers configured.";
        }

        String serverFilter = (String) input.getOrDefault("server", null);
        var clients = mcpManager.getClients();

        if (clients.isEmpty()) {
            return "No MCP servers connected.";
        }

        StringBuilder sb = new StringBuilder();
        int totalResources = 0;

        for (var entry : clients.entrySet()) {
            String serverName = entry.getKey();
            McpClient client = entry.getValue();

            if (serverFilter != null && !serverFilter.isBlank()
                    && !serverName.equalsIgnoreCase(serverFilter)) {
                continue;
            }

            if (!client.isInitialized() || !client.isConnected()) {
                sb.append("⚠ Server '").append(serverName).append("': not connected\n");
                continue;
            }

            var resources = client.getResources();
            if (resources.isEmpty()) {
                sb.append("Server '").append(serverName).append("': no resources\n");
                continue;
            }

            sb.append("## ").append(serverName).append(" (").append(resources.size()).append(" resources)\n\n");

            for (var resource : resources) {
                sb.append("- **").append(resource.name()).append("**\n");
                sb.append("  URI: `").append(resource.uri()).append("`\n");
                if (!resource.description().isBlank()) {
                    sb.append("  ").append(resource.description()).append("\n");
                }
                sb.append("  Type: ").append(resource.mimeType()).append("\n\n");
                totalResources++;
            }
        }

        if (totalResources == 0) {
            return serverFilter != null
                    ? "No resources found for server '" + serverFilter + "'."
                    : "No MCP resources available from any connected server.";
        }

        return sb.toString().stripTrailing();
    }

    @Override
    public String activityDescription(Map<String, Object> input) {
        return "📋 Listing MCP resources";
    }
}
