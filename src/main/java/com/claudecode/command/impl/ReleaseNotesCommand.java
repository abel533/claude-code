package com.claudecode.command.impl;

import com.claudecode.command.CommandContext;
import com.claudecode.command.CommandUtils;
import com.claudecode.command.SlashCommand;
import com.claudecode.console.AnsiStyle;

import java.util.List;

/**
 * /release-notes 命令 —— 显示版本更新日志。
 */
public class ReleaseNotesCommand implements SlashCommand {

    @Override
    public String name() { return "release-notes"; }

    @Override
    public String description() { return "Show version release notes"; }

    @Override
    public List<String> aliases() {
        return List.of("changelog", "whatsnew");
    }

    @Override
    public String execute(String args, CommandContext context) {
        StringBuilder sb = new StringBuilder();
        sb.append(CommandUtils.header("📋", "Release Notes"));

        sb.append(AnsiStyle.bold("  v0.4.0 — Phase 4: Commands, Tools & Services\n"));
        sb.append(CommandUtils.separator(45)).append("\n");
        sb.append("  • LSPTool: code navigation via Language Server Protocol\n");
        sb.append("  • BriefTool: output verbosity control\n");
        sb.append("  • NotificationTool: cross-platform desktop notifications\n");
        sb.append("  • 12 new commands: /brief, /vim, /theme, /usage, /tips, etc.\n");
        sb.append("  • RateLimiter, TokenEstimation, InternalLogger services\n");
        sb.append("  • Debug commands: /debug, /heapdump, /trace, /ctx-viz\n\n");

        sb.append(AnsiStyle.bold("  v0.3.0 — Phase 3: Advanced Infrastructure\n"));
        sb.append(CommandUtils.separator(45)).append("\n");
        sb.append("  • Server Mode: WebSocket direct connect for SDK integration\n");
        sb.append("  • Git Worktree: parallel branch isolation for agent tasks\n");
        sb.append("  • LSP Integration: JSON-RPC client, multi-server, diagnostics\n");
        sb.append("  • Telemetry: Feature flags, metrics, feature gates\n");
        sb.append("  • Plugin Marketplace: install, search, auto-update plugins\n\n");

        sb.append(AnsiStyle.bold("  v0.2.0 — Phase 2: Core Features\n"));
        sb.append(CommandUtils.separator(45)).append("\n");
        sb.append("  • Plan Mode for multi-step task planning\n");
        sb.append("  • Skills execution system with /skill command\n");
        sb.append("  • Session Memory with CLAUDE.md auto-persist\n");
        sb.append("  • Coordinator Mode with multi-agent messaging\n");
        sb.append("  • MCP enhancements: HTTP+SSE, resources, env vars\n\n");

        sb.append(AnsiStyle.bold("  v0.1.0 — Phase 1: Foundation\n"));
        sb.append(CommandUtils.separator(45)).append("\n");
        sb.append("  • Enhanced system prompts (7 security/style sections)\n");
        sb.append("  • 8 tool description improvements\n");
        sb.append("  • New tools: TaskStop, TaskOutput, Sleep, ToolSearch\n");
        sb.append("  • Command enhancements: /help search, /compact stats\n");
        sb.append("  • UI: markdown tables, spinner styles, tool status\n");

        return sb.toString();
    }
}
