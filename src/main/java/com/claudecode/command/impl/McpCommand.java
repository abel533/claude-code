package com.claudecode.command.impl;

import com.claudecode.command.CommandContext;
import com.claudecode.command.SlashCommand;
import com.claudecode.console.AnsiStyle;
import com.claudecode.mcp.McpClient;
import com.claudecode.mcp.McpException;
import com.claudecode.mcp.McpManager;
import com.claudecode.tool.ToolRegistry;
import com.claudecode.tool.impl.McpToolBridge;

import java.util.*;

/**
 * /mcp 命令 —— 管理 MCP（Model Context Protocol）服务器连接。
 * <p>
 * 子命令：
 * <ul>
 *   <li>{@code /mcp} —— 列出所有 MCP 服务器及状态</li>
 *   <li>{@code /mcp connect <name> <command> [args...]} —— 连接到 MCP 服务器</li>
 *   <li>{@code /mcp disconnect <name>} —— 断开 MCP 服务器</li>
 *   <li>{@code /mcp tools [server]} —— 列出 MCP 工具</li>
 *   <li>{@code /mcp resources [server]} —— 列出 MCP 资源</li>
 *   <li>{@code /mcp reload} —— 从配置文件重新加载</li>
 * </ul>
 */
public class McpCommand implements SlashCommand {

    @Override
    public String name() {
        return "mcp";
    }

    @Override
    public String description() {
        return "Manage MCP server connections";
    }

    @Override
    public String execute(String args, CommandContext context) {
        // 从工具注册表的上下文中获取 McpManager 不可行，
        // 因此使用 CommandContext。这里通过反射或约定获取。
        // 实际实现中，McpManager 应作为 CommandContext 的扩展字段提供。
        // 暂时通过静态持有者或类似机制获取。
        McpManager manager = McpManagerHolder.getInstance();
        if (manager == null) {
            return AnsiStyle.red("  ❌ MCP manager not initialized");
        }

        String trimmed = args.strip();
        if (trimmed.isEmpty()) {
            return showStatus(manager);
        }

        String[] parts = trimmed.split("\\s+", 2);
        String subCommand = parts[0].toLowerCase();
        String subArgs = parts.length > 1 ? parts[1].strip() : "";

        return switch (subCommand) {
            case "connect" -> handleConnect(manager, subArgs, context);
            case "disconnect" -> handleDisconnect(manager, subArgs);
            case "tools" -> handleTools(manager, subArgs);
            case "resources" -> handleResources(manager, subArgs);
            case "reload" -> handleReload(manager, context);
            case "help" -> showHelp();
            default -> AnsiStyle.red("  Unknown subcommand: " + subCommand) + "\n" + showHelp();
        };
    }

    /**
     * 显示所有 MCP 服务器状态。
     */
    private String showStatus(McpManager manager) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n");
        sb.append(AnsiStyle.bold("  🔌 MCP Server Status\n"));
        sb.append("  ").append("─".repeat(50)).append("\n\n");

        Map<String, McpClient> clients = manager.getClients();
        if (clients.isEmpty()) {
            sb.append("  No connected MCP servers\n\n");
            sb.append(AnsiStyle.dim("  Tip: Use /mcp connect <name> <command> [args] to connect\n"));
            sb.append(AnsiStyle.dim("  Or define servers in .mcp.json config file\n"));
            return sb.toString();
        }

        for (Map.Entry<String, McpClient> entry : clients.entrySet()) {
            String name = entry.getKey();
            McpClient client = entry.getValue();

            String statusIcon;
            String statusText;
            if (client.isConnected() && client.isInitialized()) {
                statusIcon = "✅";
                statusText = AnsiStyle.green("Connected");
            } else if (client.isConnected()) {
                statusIcon = "🔄";
                statusText = AnsiStyle.yellow("Connecting");
            } else {
                statusIcon = "❌";
                statusText = AnsiStyle.red("Disconnected");
            }

            sb.append(String.format("  %s %-18s %s%n", statusIcon, AnsiStyle.bold(name), statusText));

            // 显示工具和资源数量
            int toolCount = client.getTools().size();
            int resCount = client.getResources().size();
            sb.append(String.format("     %s%n",
                    AnsiStyle.dim(toolCount + " tools, " + resCount + " resources")));

            // 显示服务器信息
            if (client.getServerInfo() != null) {
                sb.append(String.format("     %s%n",
                        AnsiStyle.dim("Info: " + client.getServerInfo().toString())));
            }
        }

        sb.append("\n");
        sb.append(AnsiStyle.dim("  Total " + clients.size() + " servers, "
                + manager.getAllTools().size() + " tools\n"));

        return sb.toString();
    }

    /**
     * 处理 /mcp connect 子命令。
     */
    private String handleConnect(McpManager manager, String args, CommandContext context) {
        if (args.isEmpty()) {
            return AnsiStyle.red("  Usage: /mcp connect <name> <command> [args...]");
        }

        String[] parts = args.split("\\s+");
        if (parts.length < 2) {
            return AnsiStyle.red("  Usage: /mcp connect <name> <command> [args...]");
        }

        String name = parts[0];
        String command = parts[1];
        List<String> cmdArgs = parts.length > 2
                ? List.of(Arrays.copyOfRange(parts, 2, parts.length))
                : List.of();

        try {
            McpClient client = manager.connect(name, command, cmdArgs, null);

            // 将 MCP 工具桥接到工具注册表
            registerBridgedTools(client, name, context);

            StringBuilder sb = new StringBuilder();
            sb.append(AnsiStyle.green("  ✅ Connected to MCP server: " + name)).append("\n");
            sb.append(AnsiStyle.dim("     " + client.getTools().size() + " tools, "
                    + client.getResources().size() + " resources")).append("\n");

            // 列出发现的工具
            if (!client.getTools().isEmpty()) {
                sb.append("\n  Tools:\n");
                for (McpClient.McpTool tool : client.getTools()) {
                    sb.append("    • ").append(tool.name());
                    if (!tool.description().isEmpty()) {
                        sb.append(AnsiStyle.dim(" - " + truncate(tool.description(), 60)));
                    }
                    sb.append("\n");
                }
            }

            return sb.toString();
        } catch (McpException e) {
            return AnsiStyle.red("  ❌ Connection failed: " + e.getMessage());
        }
    }

    /**
     * 处理 /mcp disconnect 子命令。
     */
    private String handleDisconnect(McpManager manager, String args) {
        if (args.isEmpty()) {
            return AnsiStyle.red("  Usage: /mcp disconnect <name>");
        }

        String name = args.split("\\s+")[0];
        try {
            manager.disconnect(name);
            return AnsiStyle.green("  ✅ Disconnected MCP server: " + name);
        } catch (McpException e) {
            return AnsiStyle.red("  ❌ Disconnect failed: " + e.getMessage());
        }
    }

    /**
     * 处理 /mcp tools 子命令。
     */
    private String handleTools(McpManager manager, String args) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n");
        sb.append(AnsiStyle.bold("  🛠️  MCP Tools\n"));
        sb.append("  ").append("─".repeat(50)).append("\n\n");

        String serverFilter = args.isEmpty() ? null : args.split("\\s+")[0];

        List<McpClient.McpTool> tools;
        if (serverFilter != null) {
            tools = manager.getServerTools(serverFilter);
            if (tools.isEmpty()) {
                return sb + "  Server '" + serverFilter + "' has no tools or does not exist\n";
            }
            sb.append(AnsiStyle.dim("  Server: " + serverFilter)).append("\n\n");
        } else {
            tools = manager.getAllTools();
            if (tools.isEmpty()) {
                return sb + "  No available MCP tools\n";
            }
        }

        for (McpClient.McpTool tool : tools) {
            sb.append("  • ").append(AnsiStyle.bold(tool.name())).append("\n");
            if (!tool.description().isEmpty()) {
                sb.append("    ").append(AnsiStyle.dim(tool.description())).append("\n");
            }
            if (tool.inputSchema() != null) {
                sb.append("    ").append(AnsiStyle.dim("Schema: " +
                        truncate(tool.inputSchema().toString(), 80))).append("\n");
            }
            sb.append("\n");
        }

        sb.append(AnsiStyle.dim("  Total " + tools.size() + " tools")).append("\n");
        return sb.toString();
    }

    /**
     * 处理 /mcp resources 子命令。
     */
    private String handleResources(McpManager manager, String args) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n");
        sb.append(AnsiStyle.bold("  📦 MCP Resources\n"));
        sb.append("  ").append("─".repeat(50)).append("\n\n");

        String serverFilter = args.isEmpty() ? null : args.split("\\s+")[0];

        List<McpClient.McpResource> resourceList;
        if (serverFilter != null) {
            resourceList = manager.getServerResources(serverFilter);
            if (resourceList.isEmpty()) {
                return sb + "  Server '" + serverFilter + "' has no resources or does not exist\n";
            }
            sb.append(AnsiStyle.dim("  Server: " + serverFilter)).append("\n\n");
        } else {
            resourceList = manager.getAllResources();
            if (resourceList.isEmpty()) {
                return sb + "  No available MCP resources\n";
            }
        }

        for (McpClient.McpResource resource : resourceList) {
            sb.append("  • ").append(AnsiStyle.bold(resource.name())).append("\n");
            sb.append("    URI: ").append(AnsiStyle.cyan(resource.uri())).append("\n");
            if (!resource.description().isEmpty()) {
                sb.append("    ").append(AnsiStyle.dim(resource.description())).append("\n");
            }
            sb.append("    MIME: ").append(AnsiStyle.dim(resource.mimeType())).append("\n\n");
        }

        sb.append(AnsiStyle.dim("  Total " + resourceList.size() + " resources")).append("\n");
        return sb.toString();
    }

    /**
     * 处理 /mcp reload 子命令。
     */
    private String handleReload(McpManager manager, CommandContext context) {
        try {
            manager.reload();

            // 重新桥接所有工具
            for (Map.Entry<String, McpClient> entry : manager.getClients().entrySet()) {
                registerBridgedTools(entry.getValue(), entry.getKey(), context);
            }

            return AnsiStyle.green("  ✅ MCP config reloaded: "
                    + manager.getClients().size() + " servers, "
                    + manager.getAllTools().size() + " tools");
        } catch (Exception e) {
            return AnsiStyle.red("  ❌ Reload failed: " + e.getMessage());
        }
    }

    /**
     * 将 MCP 工具桥接注册到工具注册表。
     */
    private void registerBridgedTools(McpClient client, String serverName, CommandContext context) {
        if (context.toolRegistry() == null) {
            return;
        }

        ToolRegistry registry = context.toolRegistry();
        List<McpToolBridge> bridges = McpToolBridge.createBridges(serverName, client.getTools());
        for (McpToolBridge bridge : bridges) {
            registry.register(bridge);
        }
    }

    /**
     * 显示帮助信息。
     */
    private String showHelp() {
        return """
                
                  \033[1m🔌 MCP Command Help\033[0m
                  ──────────────────────────────────────
                
                  /mcp                                List all MCP server status
                  /mcp connect <name> <cmd> [args]    Connect to MCP server
                  /mcp disconnect <name>              Disconnect MCP server
                  /mcp tools [server]                 List MCP tools
                  /mcp resources [server]             List MCP resources
                  /mcp reload                         Reload from config file
                  /mcp help                           Show this help
                
                  Config files:
                    Project: .mcp.json
                    Global:  ~/.claude-code-java/mcp.json
                """;
    }

    /**
     * 截断字符串。
     */
    private static String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() <= maxLen ? s : s.substring(0, maxLen - 3) + "...";
    }

    // ========== McpManager 持有者（简单单例，供命令和其他组件访问） ==========

    /**
     * MCP 管理器全局持有者 —— 用于在命令和工具间共享 McpManager 实例。
     * <p>
     * 在应用启动时通过 {@link #setInstance(McpManager)} 注入。
     * 这是一种简单的服务定位器模式，后续可迁移到 Spring DI。
     */
    public static final class McpManagerHolder {

        private static volatile McpManager instance;

        private McpManagerHolder() {
        }

        /** 设置全局 MCP 管理器实例（应用启动时调用） */
        public static void setInstance(McpManager manager) {
            instance = manager;
        }

        /** 获取全局 MCP 管理器实例 */
        public static McpManager getInstance() {
            return instance;
        }
    }
}
