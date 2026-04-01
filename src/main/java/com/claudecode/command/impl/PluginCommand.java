package com.claudecode.command.impl;

import com.claudecode.command.CommandContext;
import com.claudecode.command.SlashCommand;
import com.claudecode.console.AnsiStyle;
import com.claudecode.plugin.Plugin;
import com.claudecode.plugin.PluginManager;
import com.claudecode.plugin.PluginManager.PluginInfo;
import com.claudecode.tool.Tool;

import java.nio.file.Path;
import java.util.List;

/**
 * /plugin 命令 —— 管理已加载的插件。
 * <p>
 * 子命令：
 * <ul>
 *   <li>{@code /plugin} —— 列出所有已加载插件</li>
 *   <li>{@code /plugin load <path>} —— 从 JAR 路径加载插件</li>
 *   <li>{@code /plugin unload <id>} —— 卸载指定插件</li>
 *   <li>{@code /plugin reload} —— 重载所有插件</li>
 *   <li>{@code /plugin info <id>} —— 显示插件详细信息</li>
 * </ul>
 * <p>
 * 通过 {@link com.claudecode.tool.ToolContext} 中 key 为
 * {@code "PLUGIN_MANAGER"} 的共享状态获取 {@link PluginManager} 实例。
 */
public class PluginCommand implements SlashCommand {

    @Override
    public String name() {
        return "plugin";
    }

    @Override
    public String description() {
        return "Manage loaded plugins";
    }

    @Override
    public List<String> aliases() {
        return List.of("plugins");
    }

    @Override
    public String execute(String args, CommandContext context) {
        PluginManager manager = getPluginManager(context);
        if (manager == null) {
            return AnsiStyle.red("  ✗ 插件系统未初始化");
        }

        String trimmed = (args == null) ? "" : args.trim();

        // 无参数：列出所有插件
        if (trimmed.isEmpty()) {
            return listPlugins(manager);
        }

        // 解析子命令
        String[] parts = trimmed.split("\\s+", 2);
        String subCommand = parts[0].toLowerCase();
        String subArgs = (parts.length > 1) ? parts[1].trim() : "";

        return switch (subCommand) {
            case "load" -> loadPlugin(manager, subArgs);
            case "unload" -> unloadPlugin(manager, subArgs);
            case "reload" -> reloadPlugins(manager);
            case "info" -> pluginInfo(manager, subArgs);
            default -> AnsiStyle.yellow("  未知子命令: " + subCommand) + "\n"
                    + usageHelp();
        };
    }

    /**
     * 列出所有已加载的插件。
     */
    private String listPlugins(PluginManager manager) {
        List<PluginInfo> plugins = manager.getPlugins();
        StringBuilder sb = new StringBuilder();
        sb.append("\n");
        sb.append(AnsiStyle.bold("  🔌 Loaded Plugins")).append("\n");
        sb.append("  ").append("─".repeat(50)).append("\n\n");

        if (plugins.isEmpty()) {
            sb.append(AnsiStyle.dim("  No plugins loaded.")).append("\n");
            sb.append(AnsiStyle.dim("  Place JAR files in ~/.claude-code-java/plugins/ to load them.")).append("\n");
        } else {
            for (PluginInfo info : plugins) {
                Plugin p = info.plugin();
                String scopeBadge = scopeColor(info.scope());
                sb.append(String.format("  %s %s %s%n",
                        AnsiStyle.bold(p.name()),
                        AnsiStyle.dim("v" + p.version()),
                        scopeBadge));
                sb.append(String.format("    ID: %s | %s%n",
                        AnsiStyle.cyan(p.id()),
                        p.description()));
                sb.append(String.format("    工具: %d | 命令: %d%n",
                        p.getTools().size(),
                        p.getCommands().size()));
                sb.append("\n");
            }
            sb.append(AnsiStyle.dim(String.format("  共 %d 个插件", plugins.size()))).append("\n");
        }
        return sb.toString();
    }

    /**
     * 从 JAR 路径加载插件。
     */
    private String loadPlugin(PluginManager manager, String pathStr) {
        if (pathStr.isEmpty()) {
            return AnsiStyle.yellow("  用法: /plugin load <jar-path>");
        }
        Path jarPath = Path.of(pathStr);
        boolean success = manager.loadPlugin(jarPath);
        if (success) {
            return AnsiStyle.green("  ✓ 插件加载成功: " + jarPath.getFileName());
        } else {
            return AnsiStyle.red("  ✗ 插件加载失败: " + jarPath.getFileName())
                    + "\n" + AnsiStyle.dim("  请检查 JAR 是否包含有效的 Plugin-Class 属性");
        }
    }

    /**
     * 卸载指定 ID 的插件。
     */
    private String unloadPlugin(PluginManager manager, String pluginId) {
        if (pluginId.isEmpty()) {
            return AnsiStyle.yellow("  用法: /plugin unload <plugin-id>");
        }
        boolean success = manager.unload(pluginId);
        if (success) {
            return AnsiStyle.green("  ✓ 插件已卸载: " + pluginId);
        } else {
            return AnsiStyle.red("  ✗ 未找到插件: " + pluginId);
        }
    }

    /**
     * 重载所有插件（先全部卸载，再重新扫描加载）。
     */
    private String reloadPlugins(PluginManager manager) {
        int beforeCount = manager.getPlugins().size();
        manager.shutdown();
        manager.loadAll();
        int afterCount = manager.getPlugins().size();
        return AnsiStyle.green(
                String.format("  ✓ 插件已重载（之前: %d，现在: %d）", beforeCount, afterCount));
    }

    /**
     * 显示指定插件的详细信息。
     */
    private String pluginInfo(PluginManager manager, String pluginId) {
        if (pluginId.isEmpty()) {
            return AnsiStyle.yellow("  用法: /plugin info <plugin-id>");
        }

        PluginInfo info = manager.findPlugin(pluginId);
        if (info == null) {
            return AnsiStyle.red("  ✗ 未找到插件: " + pluginId);
        }

        Plugin p = info.plugin();
        StringBuilder sb = new StringBuilder();
        sb.append("\n");
        sb.append(AnsiStyle.bold("  🔌 Plugin Details")).append("\n");
        sb.append("  ").append("─".repeat(40)).append("\n\n");

        sb.append("  ").append(AnsiStyle.bold("Name:        ")).append(p.name()).append("\n");
        sb.append("  ").append(AnsiStyle.bold("ID:          ")).append(AnsiStyle.cyan(p.id())).append("\n");
        sb.append("  ").append(AnsiStyle.bold("Version:     ")).append(p.version()).append("\n");
        sb.append("  ").append(AnsiStyle.bold("Description: ")).append(p.description()).append("\n");
        sb.append("  ").append(AnsiStyle.bold("Scope:       ")).append(scopeColor(info.scope())).append("\n");
        sb.append("  ").append(AnsiStyle.bold("JAR:         "))
                .append(AnsiStyle.dim(info.jarPath() != null ? info.jarPath().toString() : "built-in"))
                .append("\n");

        // 工具列表
        List<Tool> tools = p.getTools();
        sb.append("\n  ").append(AnsiStyle.bold("Tools (" + tools.size() + "):")).append("\n");
        if (tools.isEmpty()) {
            sb.append(AnsiStyle.dim("    (none)")).append("\n");
        } else {
            for (Tool tool : tools) {
                sb.append("    • ").append(AnsiStyle.cyan(tool.name()))
                        .append(" - ").append(tool.description()).append("\n");
            }
        }

        // 命令列表
        List<SlashCommand> commands = p.getCommands();
        sb.append("\n  ").append(AnsiStyle.bold("Commands (" + commands.size() + "):")).append("\n");
        if (commands.isEmpty()) {
            sb.append(AnsiStyle.dim("    (none)")).append("\n");
        } else {
            for (SlashCommand cmd : commands) {
                sb.append("    • ").append(AnsiStyle.green("/" + cmd.name()))
                        .append(" - ").append(cmd.description()).append("\n");
            }
        }

        return sb.toString();
    }

    /**
     * 从 CommandContext 获取 PluginManager 实例。
     * <p>
     * 通过 AgentLoop → ToolContext → 共享状态（key: "PLUGIN_MANAGER"）获取。
     *
     * @param context 命令执行上下文
     * @return PluginManager 实例，未找到时返回 null
     */
    private PluginManager getPluginManager(CommandContext context) {
        if (context.agentLoop() == null) {
            return null;
        }
        try {
            Object manager = context.agentLoop().getToolContext().get("PLUGIN_MANAGER");
            if (manager instanceof PluginManager pm) {
                return pm;
            }
        } catch (Exception ignored) {
            // ToolContext 中可能未注册 PLUGIN_MANAGER
        }
        return null;
    }

    /**
     * 为作用域标签着色。
     */
    private String scopeColor(String scope) {
        return switch (scope) {
            case "global" -> AnsiStyle.blue("[global]");
            case "project" -> AnsiStyle.green("[project]");
            case "dynamic" -> AnsiStyle.magenta("[dynamic]");
            default -> AnsiStyle.dim("[" + scope + "]");
        };
    }

    /**
     * 使用帮助文本。
     */
    private String usageHelp() {
        return AnsiStyle.dim("""
                  用法:
                    /plugin              列出所有插件
                    /plugin load <path>  加载 JAR 插件
                    /plugin unload <id>  卸载插件
                    /plugin reload       重载所有插件
                    /plugin info <id>    查看插件详情""");
    }
}
