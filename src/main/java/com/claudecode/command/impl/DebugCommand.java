package com.claudecode.command.impl;

import com.claudecode.command.CommandContext;
import com.claudecode.command.CommandUtils;
import com.claudecode.command.SlashCommand;
import com.claudecode.console.AnsiStyle;
import com.claudecode.core.InternalLogger;

import java.util.List;

/**
 * /debug 命令 —— 调试模式开关 + 工具调用追踪。
 */
public class DebugCommand implements SlashCommand {

    @Override
    public String name() { return "debug"; }

    @Override
    public String description() { return "Toggle debug mode and view internal logs"; }

    @Override
    public List<String> aliases() { return List.of("dbg"); }

    @Override
    public String execute(String args, CommandContext context) {
        String trimmed = CommandUtils.parseArgs(args);

        StringBuilder sb = new StringBuilder();
        sb.append(CommandUtils.header("🐛", "Debug Mode"));

        if (context.agentLoop() == null) {
            return sb.append("  No active agent loop\n").toString();
        }

        var toolCtx = context.agentLoop().getToolContext();

        if (trimmed.equals("on") || trimmed.equals("enable")) {
            toolCtx.set("DEBUG_MODE", true);
            sb.append("  Debug mode: ").append(AnsiStyle.green("ENABLED")).append("\n");
            sb.append("  Tool call tracing is now active.\n");

            // Set InternalLogger to DEBUG level if available
            Object loggerObj = toolCtx.get("INTERNAL_LOGGER");
            if (loggerObj instanceof InternalLogger logger) {
                logger.setLevel(InternalLogger.Level.DEBUG);
                sb.append("  Internal log level: DEBUG\n");
            }

        } else if (trimmed.equals("off") || trimmed.equals("disable")) {
            toolCtx.set("DEBUG_MODE", false);
            sb.append("  Debug mode: ").append(AnsiStyle.red("DISABLED")).append("\n");

            Object loggerObj = toolCtx.get("INTERNAL_LOGGER");
            if (loggerObj instanceof InternalLogger logger) {
                logger.setLevel(InternalLogger.Level.NORMAL);
                sb.append("  Internal log level: NORMAL\n");
            }

        } else if (trimmed.startsWith("log")) {
            // /debug logs [N] — show recent internal logs
            int count = 20;
            String[] parts = trimmed.split("\\s+");
            if (parts.length > 1) {
                try { count = Integer.parseInt(parts[1]); } catch (NumberFormatException ignored) {}
            }

            Object loggerObj = toolCtx.get("INTERNAL_LOGGER");
            if (loggerObj instanceof InternalLogger logger) {
                sb.append(AnsiStyle.bold("  Recent Logs (last " + count + ")\n\n"));
                String logs = logger.getRecent(count);
                if (logs.isEmpty()) {
                    sb.append("  No logs recorded yet.\n");
                } else {
                    sb.append(AnsiStyle.dim(logs));
                }
            } else {
                sb.append("  InternalLogger not available.\n");
            }

        } else if (trimmed.equals("tools")) {
            // /debug tools — show tool call stats from ToolContext
            sb.append(AnsiStyle.bold("  Tool Call Tracing\n\n"));
            Object metrics = toolCtx.get("METRICS_COLLECTOR");
            if (metrics != null) {
                sb.append("  Use /performance for detailed tool stats.\n");
            } else {
                sb.append("  No metrics collector available.\n");
            }

        } else {
            // Status
            boolean debugOn = Boolean.TRUE.equals(toolCtx.get("DEBUG_MODE"));
            sb.append("  Status: ").append(debugOn
                    ? AnsiStyle.green("ENABLED") : AnsiStyle.dim("disabled")).append("\n\n");

            sb.append(AnsiStyle.bold("  Subcommands\n"));
            sb.append("  /debug on       Enable debug mode\n");
            sb.append("  /debug off      Disable debug mode\n");
            sb.append("  /debug logs     Show recent internal logs\n");
            sb.append("  /debug logs 50  Show last 50 log entries\n");
            sb.append("  /debug tools    Show tool call tracing info\n");
        }

        return sb.toString();
    }
}
