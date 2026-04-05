package com.claudecode.command.impl;

import com.claudecode.command.CommandContext;
import com.claudecode.command.CommandUtils;
import com.claudecode.command.SlashCommand;
import com.claudecode.console.AnsiStyle;
import com.claudecode.core.RateLimiter;

import java.util.List;

/**
 * /reset-limits 命令 —— 重置速率限制。
 */
public class ResetLimitsCommand implements SlashCommand {

    @Override
    public String name() { return "reset-limits"; }

    @Override
    public String description() { return "Reset rate limits and cooldowns"; }

    @Override
    public List<String> aliases() { return List.of("rl"); }

    @Override
    public String execute(String args, CommandContext context) {
        StringBuilder sb = new StringBuilder();
        sb.append(CommandUtils.header("🔄", "Rate Limit Reset"));

        if (context.agentLoop() == null) {
            return sb.append("  No active agent loop\n").toString();
        }

        var toolCtx = context.agentLoop().getToolContext();
        Object limiterObj = toolCtx.get("RATE_LIMITER");

        if (limiterObj instanceof RateLimiter limiter) {
            // Show current state first
            sb.append(AnsiStyle.bold("  Before Reset\n"));
            sb.append("  Remaining (api):     ").append(limiter.getRemaining("api")).append("\n");
            sb.append("  Remaining (tool):    ").append(limiter.getRemaining("tool")).append("\n\n");

            // Reset
            limiter.resetAll();

            sb.append(AnsiStyle.bold("  After Reset\n"));
            sb.append("  Remaining (api):     ").append(AnsiStyle.green(
                    String.valueOf(limiter.getRemaining("api")))).append("\n");
            sb.append("  Remaining (tool):    ").append(AnsiStyle.green(
                    String.valueOf(limiter.getRemaining("tool")))).append("\n");
            sb.append("  Concurrent slots:    ").append(AnsiStyle.green("all available")).append("\n\n");
            sb.append("  ✅ Rate limits have been reset.\n");
        } else {
            sb.append("  No rate limiter configured.\n");
            sb.append(AnsiStyle.dim("  Rate limiting is not active in the current session.\n"));
        }

        return sb.toString();
    }
}
