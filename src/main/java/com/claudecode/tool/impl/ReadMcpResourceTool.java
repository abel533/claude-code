package com.claudecode.tool.impl;

import com.claudecode.mcp.McpClient;
import com.claudecode.mcp.McpManager;
import com.claudecode.tool.Tool;
import com.claudecode.tool.ToolContext;

import java.util.Map;

/**
 * ReadMcpResource 工具 —— 读取 MCP 服务器的指定资源。
 * <p>
 * 对应 claude-code 中读取 MCP 资源的功能。
 * 通过 URI 从 MCP 服务器读取资源内容。
 */
public class ReadMcpResourceTool implements Tool {

    @Override
    public String name() {
        return "ReadMcpResource";
    }

    @Override
    public String description() {
        return """
            Read a specific resource from a connected MCP (Model Context Protocol) server.
            Provide the resource URI (obtained from ListMcpResources) to fetch its content.
            The server name is optional — if omitted, all servers are searched for the URI.""";
    }

    @Override
    public String inputSchema() {
        return """
            {
              "type": "object",
              "properties": {
                "uri": {
                  "type": "string",
                  "description": "The resource URI to read (e.g., 'file:///path' or 'custom://resource')"
                },
                "server": {
                  "type": "string",
                  "description": "Optional: the MCP server name that provides this resource"
                }
              },
              "required": ["uri"]
            }""";
    }

    @Override
    public String execute(Map<String, Object> input, ToolContext context) {
        String uri = (String) input.get("uri");
        String serverFilter = (String) input.getOrDefault("server", null);

        if (uri == null || uri.isBlank()) {
            return "Error: 'uri' is required. Use ListMcpResources to discover available resources.";
        }

        McpManager mcpManager = context.getOrDefault("MCP_MANAGER", null);
        if (mcpManager == null) {
            return "Error: No MCP servers configured.";
        }

        var clients = mcpManager.getClients();
        if (clients.isEmpty()) {
            return "Error: No MCP servers connected.";
        }

        // If server specified, try only that server
        if (serverFilter != null && !serverFilter.isBlank()) {
            McpClient client = clients.get(serverFilter);
            if (client == null) {
                return "Error: MCP server '" + serverFilter + "' not found. "
                        + "Available servers: " + String.join(", ", clients.keySet());
            }
            return readFromClient(client, serverFilter, uri);
        }

        // Try all connected servers
        for (var entry : clients.entrySet()) {
            McpClient client = entry.getValue();
            if (!client.isInitialized() || !client.isConnected()) continue;

            // Check if this server has the resource
            boolean hasResource = client.getResources().stream()
                    .anyMatch(r -> r.uri().equals(uri));
            if (hasResource) {
                return readFromClient(client, entry.getKey(), uri);
            }
        }

        // No server has this resource — try reading anyway (some servers allow arbitrary URIs)
        for (var entry : clients.entrySet()) {
            McpClient client = entry.getValue();
            if (!client.isInitialized() || !client.isConnected()) continue;
            try {
                String result = client.readResource(uri);
                if (result != null && !result.isBlank()) {
                    return result;
                }
            } catch (Exception ignored) {
                // Try next server
            }
        }

        return "Error: Resource '" + uri + "' not found on any connected MCP server. "
                + "Use ListMcpResources to see available resources.";
    }

    private String readFromClient(McpClient client, String serverName, String uri) {
        if (!client.isInitialized() || !client.isConnected()) {
            return "Error: MCP server '" + serverName + "' is not connected.";
        }
        try {
            String content = client.readResource(uri);
            if (content == null || content.isBlank()) {
                return "(Resource returned empty content)";
            }
            return content;
        } catch (Exception e) {
            return "Error reading resource '" + uri + "' from server '" + serverName + "': " + e.getMessage();
        }
    }

    @Override
    public String activityDescription(Map<String, Object> input) {
        String uri = (String) input.getOrDefault("uri", "?");
        return "📖 Reading MCP resource: " + uri;
    }
}
