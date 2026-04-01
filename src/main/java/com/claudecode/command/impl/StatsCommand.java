package com.claudecode.command.impl;

import com.claudecode.command.CommandContext;
import com.claudecode.command.SlashCommand;
import com.claudecode.console.AnsiStyle;
import com.claudecode.core.TokenTracker;

import java.lang.management.ManagementFactory;
import java.lang.management.RuntimeMXBean;
import java.util.concurrent.TimeUnit;

/**
 * /stats 命令 —— 显示当前会话的使用统计信息。
 * <p>
 * 展示内容包括：
 * <ul>
 *   <li>近似对话轮数（消息历史大小 / 2）</li>
 *   <li>API 调用总次数</li>
 *   <li>Token 使用量（输入/输出/总计）</li>
 *   <li>估算费用（美元）</li>
 *   <li>每次调用平均 Token 数</li>
 *   <li>当前使用的模型名称</li>
 *   <li>JVM 运行时长</li>
 * </ul>
 */
public class StatsCommand implements SlashCommand {

    @Override
    public String name() {
        return "stats";
    }

    @Override
    public String description() {
        return "Show usage statistics";
    }

    @Override
    public String execute(String args, CommandContext context) {
        if (context.agentLoop() == null) {
            return AnsiStyle.red("  ✗ AgentLoop 不可用。");
        }

        TokenTracker tracker = context.agentLoop().getTokenTracker();
        if (tracker == null) {
            return AnsiStyle.yellow("  ⚠ Token 追踪器不可用。");
        }

        // 收集统计数据
        int messageCount = context.agentLoop().getMessageHistory().size();
        int conversationRounds = messageCount / 2;  // 近似对话轮数
        long apiCalls = tracker.getApiCallCount();
        long inputTokens = tracker.getInputTokens();
        long outputTokens = tracker.getOutputTokens();
        long totalTokens = tracker.getTotalTokens();
        double estimatedCost = tracker.estimateCost();
        String modelName = tracker.getModelName();

        // 计算每次调用平均 Token 数
        long avgTokensPerCall = apiCalls > 0 ? totalTokens / apiCalls : 0;

        // 计算 JVM 运行时长
        String uptime = formatUptime();

        // 构建输出
        StringBuilder sb = new StringBuilder();
        sb.append(AnsiStyle.bold("\n  📊 Session Statistics\n"));
        sb.append("\n");

        // 模型信息
        sb.append(formatRow("Model", AnsiStyle.cyan(modelName)));
        sb.append(formatRow("Uptime", uptime));
        sb.append("\n");

        // 对话统计
        sb.append(AnsiStyle.bold("  ── Conversation ──\n"));
        sb.append(formatRow("Messages", String.valueOf(messageCount)));
        sb.append(formatRow("Conversations (approx)", String.valueOf(conversationRounds)));
        sb.append(formatRow("API Calls", String.valueOf(apiCalls)));
        sb.append("\n");

        // Token 统计
        sb.append(AnsiStyle.bold("  ── Token Usage ──\n"));
        sb.append(formatRow("Input Tokens", TokenTracker.formatTokens(inputTokens)));
        sb.append(formatRow("Output Tokens", TokenTracker.formatTokens(outputTokens)));
        sb.append(formatRow("Total Tokens", AnsiStyle.bold(TokenTracker.formatTokens(totalTokens))));
        sb.append(formatRow("Avg Tokens/Call", TokenTracker.formatTokens(avgTokensPerCall)));
        sb.append("\n");

        // 费用统计
        sb.append(AnsiStyle.bold("  ── Cost ──\n"));
        sb.append(formatRow("Estimated Cost", formatCost(estimatedCost)));
        sb.append("\n");

        return sb.toString();
    }

    /**
     * 格式化表格行（左对齐标签 + 右对齐值）。
     *
     * @param label 标签名
     * @param value 值
     * @return 格式化的行字符串
     */
    private String formatRow(String label, String value) {
        return String.format("  %-24s %s%n", AnsiStyle.dim(label), value);
    }

    /**
     * 格式化费用值（保留 4 位小数）。
     * <p>
     * 根据费用高低使用不同颜色：
     * <ul>
     *   <li>$0 以下（即免费）：绿色</li>
     *   <li>$0.01 ~ $1.00：黄色</li>
     *   <li>$1.00 以上：红色</li>
     * </ul>
     *
     * @param cost 费用（美元）
     * @return 带颜色的费用字符串
     */
    private String formatCost(double cost) {
        String formatted = String.format("$%.4f", cost);
        if (cost < 0.01) {
            return AnsiStyle.green(formatted);
        } else if (cost < 1.0) {
            return AnsiStyle.yellow(formatted);
        } else {
            return AnsiStyle.red(formatted);
        }
    }

    /**
     * 格式化 JVM 运行时长。
     * <p>
     * 使用 {@link ManagementFactory#getRuntimeMXBean()} 获取 JVM 启动时间，
     * 并计算至今的运行时长，格式为 "Xh Ym Zs"。
     *
     * @return 格式化的运行时长字符串
     */
    private String formatUptime() {
        RuntimeMXBean runtimeMXBean = ManagementFactory.getRuntimeMXBean();
        long uptimeMillis = runtimeMXBean.getUptime();

        long hours = TimeUnit.MILLISECONDS.toHours(uptimeMillis);
        long minutes = TimeUnit.MILLISECONDS.toMinutes(uptimeMillis) % 60;
        long seconds = TimeUnit.MILLISECONDS.toSeconds(uptimeMillis) % 60;

        if (hours > 0) {
            return String.format("%dh %dm %ds", hours, minutes, seconds);
        } else if (minutes > 0) {
            return String.format("%dm %ds", minutes, seconds);
        } else {
            return String.format("%ds", seconds);
        }
    }
}
