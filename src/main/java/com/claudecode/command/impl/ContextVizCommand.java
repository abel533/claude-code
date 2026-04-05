package com.claudecode.command.impl;

import com.claudecode.command.CommandContext;
import com.claudecode.command.CommandUtils;
import com.claudecode.command.SlashCommand;
import com.claudecode.console.AnsiStyle;
import com.claudecode.core.TokenEstimationService;

import java.util.List;

/**
 * /ctx-viz 命令 —— 上下文可视化（token 分布、消息结构）。
 */
public class ContextVizCommand implements SlashCommand {

    @Override
    public String name() { return "ctx-viz"; }

    @Override
    public String description() { return "Visualize context window token distribution"; }

    @Override
    public List<String> aliases() { return List.of("context", "ctx"); }

    @Override
    public String execute(String args, CommandContext context) {
        StringBuilder sb = new StringBuilder();
        sb.append(CommandUtils.header("📊", "Context Window Visualization"));

        if (context.agentLoop() == null) {
            return sb.append("  No active agent loop\n").toString();
        }

        var toolCtx = context.agentLoop().getToolContext();

        // Get or create token estimation service
        TokenEstimationService estimator = new TokenEstimationService();

        // Model context limit
        String modelName = "claude-sonnet-4-20250514";
        Object modelObj = toolCtx.get("MODEL_NAME");
        if (modelObj instanceof String m) modelName = m;

        int contextLimit = getContextLimit(modelName);

        // Estimate system prompt tokens
        int systemPromptTokens = 0;
        Object sysMsgObj = toolCtx.get("SYSTEM_PROMPT_CACHE");
        if (sysMsgObj instanceof String sysMsg) {
            systemPromptTokens = estimator.estimateTokens(sysMsg);
        } else {
            systemPromptTokens = 4000; // typical estimate
        }

        // Tool definitions estimate
        int toolDefTokens = 0;
        Object toolCountObj = toolCtx.get("TOOL_REGISTRY");
        if (toolCountObj != null) {
            toolDefTokens = 2000; // ~65 tokens per tool × 30 tools
        }

        // Token tracker data
        long inputTokens = 0;
        long outputTokens = 0;
        var tokenTracker = context.agentLoop().getTokenTracker();
        if (tokenTracker != null) {
            inputTokens = tokenTracker.getInputTokens();
            outputTokens = tokenTracker.getOutputTokens();
        }

        // Calculate remaining
        long usedTokens = systemPromptTokens + toolDefTokens + inputTokens;
        long remainingTokens = Math.max(0, contextLimit - usedTokens);
        double usagePercent = (double) usedTokens / contextLimit * 100;

        // Display context bar
        sb.append(AnsiStyle.bold("  Context Usage\n"));
        int barWidth = 40;
        int filled = (int) (usagePercent / 100 * barWidth);
        filled = Math.min(filled, barWidth);

        String barColor;
        if (usagePercent > 90) barColor = AnsiStyle.red("█".repeat(filled));
        else if (usagePercent > 70) barColor = AnsiStyle.yellow("█".repeat(filled));
        else barColor = AnsiStyle.green("█".repeat(filled));

        sb.append("  [").append(barColor).append("░".repeat(barWidth - filled)).append("] ");
        sb.append(String.format("%.1f%%\n\n", usagePercent));

        // Token breakdown
        sb.append(AnsiStyle.bold("  Token Breakdown\n"));
        sb.append("  ┌────────────────────────┬────────────┬───────┐\n");
        sb.append("  │ Component              │ Tokens     │ %     │\n");
        sb.append("  ├────────────────────────┼────────────┼───────┤\n");

        appendRow(sb, "System Prompt", systemPromptTokens, contextLimit);
        appendRow(sb, "Tool Definitions", toolDefTokens, contextLimit);
        appendRow(sb, "Conversation (input)", (int) inputTokens, contextLimit);
        appendRow(sb, "Generated (output)", (int) outputTokens, contextLimit);
        sb.append("  ├────────────────────────┼────────────┼───────┤\n");
        appendRow(sb, "Total Used", (int) usedTokens, contextLimit);
        appendRow(sb, "Remaining", (int) remainingTokens, contextLimit);
        sb.append("  └────────────────────────┴────────────┴───────┘\n\n");

        // Model info
        sb.append(AnsiStyle.bold("  Model Info\n"));
        sb.append("  Model:     ").append(modelName).append("\n");
        sb.append("  Context:   ").append(estimator.formatTokenCount(contextLimit)).append(" tokens\n");
        sb.append("  Cost est:  $").append(String.format("%.4f",
                estimator.estimateCost(inputTokens, outputTokens, modelName))).append("\n");

        // Recommendations
        if (usagePercent > 80) {
            sb.append("\n  ⚠️  ").append(AnsiStyle.yellow("Context is getting full. Consider /compact to free space.")).append("\n");
        }

        return sb.toString();
    }

    private void appendRow(StringBuilder sb, String label, int tokens, int total) {
        double pct = (double) tokens / total * 100;
        sb.append(String.format("  │ %-22s │ %10s │ %5.1f │\n",
                label, formatTokens(tokens), pct));
    }

    private String formatTokens(int tokens) {
        if (tokens >= 1_000_000) return String.format("%.1fM", tokens / 1_000_000.0);
        if (tokens >= 1_000) return String.format("%.1fK", tokens / 1_000.0);
        return String.valueOf(tokens);
    }

    private int getContextLimit(String model) {
        if (model.contains("opus")) return 200_000;
        if (model.contains("haiku")) return 200_000;
        return 200_000; // All Claude 3+ models: 200K
    }
}
