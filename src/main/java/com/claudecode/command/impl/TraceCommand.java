package com.claudecode.command.impl;

import com.claudecode.command.CommandContext;
import com.claudecode.command.CommandUtils;
import com.claudecode.command.SlashCommand;
import com.claudecode.console.AnsiStyle;

import java.time.Instant;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * /trace 命令 —— 请求/响应追踪。
 * 显示 API 调用追踪信息（模型调用、tool 调用链等）。
 */
public class TraceCommand implements SlashCommand {

    @Override
    public String name() { return "trace"; }

    @Override
    public String description() { return "Show request/response tracing"; }

    @Override
    public String execute(String args, CommandContext context) {
        String trimmed = CommandUtils.parseArgs(args);

        StringBuilder sb = new StringBuilder();
        sb.append(CommandUtils.header("🔍", "Request Tracing"));

        if (context.agentLoop() == null) {
            return sb.append("  No active agent loop\n").toString();
        }

        var toolCtx = context.agentLoop().getToolContext();

        if (trimmed.equals("on") || trimmed.equals("enable")) {
            toolCtx.set("TRACE_ENABLED", true);
            sb.append("  Tracing: ").append(AnsiStyle.green("ENABLED")).append("\n");
            sb.append("  API calls and tool executions will be traced.\n");

        } else if (trimmed.equals("off") || trimmed.equals("disable")) {
            toolCtx.set("TRACE_ENABLED", false);
            sb.append("  Tracing: ").append(AnsiStyle.red("DISABLED")).append("\n");

        } else if (trimmed.equals("clear")) {
            toolCtx.set("TRACE_LOG", null);
            sb.append("  Trace log cleared.\n");

        } else {
            // Show current trace info
            boolean traceOn = Boolean.TRUE.equals(toolCtx.get("TRACE_ENABLED"));
            sb.append("  Status: ").append(traceOn
                    ? AnsiStyle.green("ENABLED") : AnsiStyle.dim("disabled")).append("\n\n");

            // Show thread info
            sb.append(AnsiStyle.bold("  Active Threads\n"));
            Thread.getAllStackTraces().entrySet().stream()
                    .filter(e -> e.getKey().getName().startsWith("agent")
                            || e.getKey().getName().contains("tool")
                            || e.getKey().getName().contains("http"))
                    .limit(10)
                    .forEach(e -> {
                        Thread t = e.getKey();
                        sb.append("  ").append(String.format("%-30s", t.getName()))
                                .append(AnsiStyle.dim(t.getState().toString())).append("\n");
                    });

            // Show recent conversation turns
            sb.append("\n").append(AnsiStyle.bold("  Conversation State\n"));
            sb.append("  Session ID: ").append(context.agentLoop().getToolContext()
                    .get("SESSION_ID") != null ? toolCtx.get("SESSION_ID") : "default").append("\n");

            sb.append("\n").append(AnsiStyle.bold("  Subcommands\n"));
            sb.append("  /trace on       Enable tracing\n");
            sb.append("  /trace off      Disable tracing\n");
            sb.append("  /trace clear    Clear trace log\n");
        }

        return sb.toString();
    }
}
