package com.claudecode.command.impl;

import com.claudecode.command.CommandContext;
import com.claudecode.command.CommandUtils;
import com.claudecode.command.SlashCommand;
import com.claudecode.console.AnsiStyle;
import com.claudecode.plugin.*;
import com.claudecode.plugin.PluginManager.PluginInfo;
import com.claudecode.tool.Tool;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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
 *   <li>{@code /plugin install <url|id>} —— 从市场或 URL 安装插件</li>
 *   <li>{@code /plugin remove <id>} —— 卸载并删除插件</li>
 *   <li>{@code /plugin update [id]} —— 检查/安装更新</li>
 *   <li>{@code /plugin search <query>} —— 搜索市场插件</li>
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
            return AnsiStyle.red("  ✗ Plugin system not initialized");
        }

        String trimmed = CommandUtils.parseArgs(args);

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
            case "install" -> installPlugin(context, subArgs);
            case "remove" -> removePlugin(context, manager, subArgs);
            case "update" -> checkUpdates(context, subArgs);
            case "search" -> searchMarketplace(context, subArgs);
            default -> AnsiStyle.yellow("  Unknown subcommand: " + subCommand) + "\n"
                    + usageHelp();
        };
    }

    /**
     * 列出所有已加载的插件。
     */
    private String listPlugins(PluginManager manager) {
        List<PluginInfo> plugins = manager.getPlugins();
        StringBuilder sb = new StringBuilder();
        sb.append(CommandUtils.header("🔌", "Loaded Plugins"));

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
                sb.append(String.format("    Tools: %d | Commands: %d%n",
                        p.getTools().size(),
                        p.getCommands().size()));
                sb.append("\n");
            }
            sb.append(AnsiStyle.dim(String.format("  Total %d plugins", plugins.size()))).append("\n");
        }
        return sb.toString();
    }

    /**
     * 从 JAR 路径加载插件。
     */
    private String loadPlugin(PluginManager manager, String pathStr) {
        if (pathStr.isEmpty()) {
            return AnsiStyle.yellow("  Usage: /plugin load <jar-path>");
        }
        Path jarPath = Path.of(pathStr);
        boolean success = manager.loadPlugin(jarPath);
        if (success) {
            return AnsiStyle.green("  ✓ Plugin loaded: " + jarPath.getFileName());
        } else {
            return AnsiStyle.red("  ✗ Plugin load failed: " + jarPath.getFileName())
                    + "\n" + AnsiStyle.dim("  Please check if JAR contains a valid Plugin-Class attribute");
        }
    }

    /**
     * 卸载指定 ID 的插件。
     */
    private String unloadPlugin(PluginManager manager, String pluginId) {
        if (pluginId.isEmpty()) {
            return AnsiStyle.yellow("  Usage: /plugin unload <plugin-id>");
        }
        boolean success = manager.unload(pluginId);
        if (success) {
            return AnsiStyle.green("  ✓ Plugin unloaded: " + pluginId);
        } else {
            return AnsiStyle.red("  ✗ Plugin not found: " + pluginId);
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
                String.format("  ✓ Plugins reloaded (before: %d, now: %d)", beforeCount, afterCount));
    }

    /**
     * 显示指定插件的详细信息。
     */
    private String pluginInfo(PluginManager manager, String pluginId) {
        if (pluginId.isEmpty()) {
            return AnsiStyle.yellow("  Usage: /plugin info <plugin-id>");
        }

        PluginInfo info = manager.findPlugin(pluginId);
        if (info == null) {
            return AnsiStyle.red("  ✗ Plugin not found: " + pluginId);
        }

        Plugin p = info.plugin();
        StringBuilder sb = new StringBuilder();
        sb.append(CommandUtils.header("🔌", "Plugin Details"));

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

    // ==================== Marketplace subcommands ====================

    /**
     * 从市场或 URL 安装插件。
     */
    private String installPlugin(CommandContext context, String target) {
        if (target.isEmpty()) {
            return AnsiStyle.yellow("  Usage: /plugin install <url|plugin-id>");
        }

        PluginInstaller installer = getInstaller(context);
        if (installer == null) {
            return AnsiStyle.red("  ✗ Plugin installer not available");
        }

        String downloadUrl;
        if (target.startsWith("http://") || target.startsWith("https://")) {
            downloadUrl = target;
        } else {
            // 从市场查找
            MarketplaceManager marketplace = getMarketplace(context);
            if (marketplace == null) {
                return AnsiStyle.red("  ✗ Marketplace not available. Use URL instead: /plugin install <url>");
            }
            Optional<PluginManifest.MarketplaceEntry> entry = marketplace.getPlugin(target);
            if (entry.isEmpty()) {
                return AnsiStyle.red("  ✗ Plugin not found in marketplace: " + target);
            }
            downloadUrl = entry.get().downloadUrl();
            if (downloadUrl == null || downloadUrl.isBlank()) {
                return AnsiStyle.red("  ✗ No download URL for plugin: " + target);
            }
        }

        var result = installer.install(downloadUrl, "user");
        if (result.success()) {
            return AnsiStyle.green("  ✓ " + result.message()) + "\n"
                    + AnsiStyle.dim("  Run /plugin reload to activate");
        } else {
            return AnsiStyle.red("  ✗ " + result.message());
        }
    }

    /**
     * 卸载并删除插件。
     */
    private String removePlugin(CommandContext context, PluginManager manager, String pluginId) {
        if (pluginId.isEmpty()) {
            return AnsiStyle.yellow("  Usage: /plugin remove <plugin-id>");
        }

        // 先从运行时卸载
        manager.unload(pluginId);

        // 再从磁盘删除
        PluginInstaller installer = getInstaller(context);
        if (installer != null) {
            boolean deleted = installer.uninstall(pluginId, "user")
                    || installer.uninstall(pluginId, "project");
            if (deleted) {
                return AnsiStyle.green("  ✓ Plugin removed: " + pluginId);
            }
        }
        return AnsiStyle.yellow("  ⚠ Plugin unloaded from runtime but JAR not found on disk");
    }

    /**
     * 检查/安装更新。
     */
    private String checkUpdates(CommandContext context, String pluginId) {
        PluginAutoUpdate autoUpdate = getAutoUpdate(context);
        if (autoUpdate == null) {
            return AnsiStyle.red("  ✗ Auto-update not available");
        }

        if (pluginId.isEmpty()) {
            // 检查所有
            var results = autoUpdate.checkForUpdates();
            if (results.isEmpty()) {
                return AnsiStyle.green("  ✓ All plugins are up to date");
            }
            StringBuilder sb = new StringBuilder();
            sb.append("\n").append(AnsiStyle.bold("  📦 Available Updates")).append("\n\n");
            for (var result : results.values()) {
                sb.append(String.format("  %s: %s → %s%n",
                        AnsiStyle.cyan(result.pluginId()),
                        AnsiStyle.dim(result.currentVersion()),
                        AnsiStyle.green(result.latestVersion())));
            }
            sb.append("\n").append(AnsiStyle.dim("  Run /plugin install <url> to update")).append("\n");
            return sb.toString();
        } else {
            // 检查指定插件
            var pending = autoUpdate.getPendingUpdates().get(pluginId);
            if (pending != null && pending.hasUpdate()) {
                return String.format("  %s: %s → %s\n  Download: %s",
                        AnsiStyle.cyan(pluginId),
                        pending.currentVersion(), pending.latestVersion(),
                        pending.downloadUrl());
            }
            return AnsiStyle.green("  ✓ " + pluginId + " is up to date");
        }
    }

    /**
     * 搜索市场插件。
     */
    private String searchMarketplace(CommandContext context, String query) {
        if (query.isEmpty()) {
            return AnsiStyle.yellow("  Usage: /plugin search <query>");
        }

        MarketplaceManager marketplace = getMarketplace(context);
        if (marketplace == null) {
            return AnsiStyle.red("  ✗ Marketplace not available");
        }

        var results = marketplace.search(query);
        if (results.isEmpty()) {
            return AnsiStyle.dim("  No plugins found for: " + query);
        }

        StringBuilder sb = new StringBuilder();
        sb.append("\n").append(AnsiStyle.bold("  🔍 Search Results")).append(" (")
                .append(results.size()).append(")\n\n");

        for (var entry : results.stream().limit(10).toList()) {
            sb.append(String.format("  %s %s  %s%n",
                    AnsiStyle.bold(entry.name()),
                    AnsiStyle.dim("v" + entry.version()),
                    AnsiStyle.dim("by " + entry.author())));
            sb.append(String.format("    %s  ⬇ %d%n",
                    entry.description(),
                    entry.downloads()));
            sb.append(String.format("    %s%n%n",
                    AnsiStyle.cyan("/plugin install " + entry.id())));
        }

        if (results.size() > 10) {
            sb.append(AnsiStyle.dim("  ... and " + (results.size() - 10) + " more"));
        }

        return sb.toString();
    }

    // ==================== Context helpers ====================

    private PluginInstaller getInstaller(CommandContext context) {
        try {
            Object obj = context.agentLoop().getToolContext().get("PLUGIN_INSTALLER");
            if (obj instanceof PluginInstaller pi) return pi;
        } catch (Exception ignored) {}
        return null;
    }

    private MarketplaceManager getMarketplace(CommandContext context) {
        try {
            Object obj = context.agentLoop().getToolContext().get("MARKETPLACE_MANAGER");
            if (obj instanceof MarketplaceManager mm) return mm;
        } catch (Exception ignored) {}
        return null;
    }

    private PluginAutoUpdate getAutoUpdate(CommandContext context) {
        try {
            Object obj = context.agentLoop().getToolContext().get("PLUGIN_AUTO_UPDATE");
            if (obj instanceof PluginAutoUpdate pau) return pau;
        } catch (Exception ignored) {}
        return null;
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
                  Usage:
                    /plugin              List all plugins
                    /plugin load <path>  Load JAR plugin
                    /plugin unload <id>  Unload plugin
                    /plugin reload       Reload all plugins
                    /plugin info <id>    View plugin details
                    /plugin install <x>  Install from URL or marketplace
                    /plugin remove <id>  Uninstall plugin
                    /plugin update [id]  Check for updates
                    /plugin search <q>   Search marketplace""");
    }
}
