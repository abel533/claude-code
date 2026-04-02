package com.claudecode.console;

import com.claudecode.core.TokenTracker;

import java.io.PrintStream;

/**
 * 底部状态行渲染器 —— 对应 claude-code 的 StatusLine 组件。
 * <p>
 * 在终端底部持续显示：模型名、Token 用量/费用、工作目录等状态信息。
 * 使用 ANSI 转义序列控制光标位置，在每次输出后刷新状态行。
 * <p>
 * 注意：仅在非 dumb 终端下启用，dumb 终端不支持光标控制。
 */
public class StatusLine {

    private final PrintStream out;
    private volatile boolean enabled = false;
    private volatile String modelName = "";
    private volatile TokenTracker tokenTracker;
    private volatile String workDir = "";

    public StatusLine(PrintStream out) {
        this.out = out;
    }

    /** 启用状态行 */
    public void enable(String model, TokenTracker tracker) {
        this.modelName = model;
        this.tokenTracker = tracker;
        this.workDir = abbreviatePath(System.getProperty("user.dir"));
        this.enabled = true;
    }

    /** 禁用状态行 */
    public void disable() {
        this.enabled = false;
        clearStatusLine();
    }

    /**
     * 刷新底部状态行显示。
     * <p>
     * 使用 ANSI 转义序列：
     * - 保存光标位置
     * - 移动到屏幕底部
     * - 输出状态信息
     * - 恢复光标位置
     */
    public void refresh() {
        if (!enabled || tokenTracker == null) return;

        String status = buildStatusText();

        // 保存光标 → 移到最后一行 → 清行 → 写状态 → 恢复光标
        out.print("\033[s");              // 保存光标
        out.print("\033[999;1H");         // 移到最后一行
        out.print("\033[2K");             // 清除该行
        out.print(status);
        out.print("\033[u");              // 恢复光标
        out.flush();
    }

    /**
     * 渲染一行式状态摘要（不使用光标控制，适合在提示符之前显示）。
     * 这是一种更安全的替代方案，不会干扰终端滚动。
     */
    public String renderInline() {
        if (!enabled || tokenTracker == null) return "";
        return buildStatusText();
    }

    private String buildStatusText() {
        long inputTokens = tokenTracker.getInputTokens();
        long outputTokens = tokenTracker.getOutputTokens();
        double cost = tokenTracker.estimateCost();
        long apiCalls = tokenTracker.getApiCallCount();

        StringBuilder sb = new StringBuilder();

        // 反色背景的状态栏
        sb.append(AnsiStyle.DIM);

        // 模型名
        sb.append(" ").append(modelName);

        // Token 用量 + 上下文窗口占比
        sb.append("  │  ↑").append(TokenTracker.formatTokens(inputTokens));
        sb.append(" ↓").append(TokenTracker.formatTokens(outputTokens));

        // 上下文窗口使用百分比（带颜色）
        double usagePct = tokenTracker.getUsagePercentage();
        if (usagePct > 0) {
            String pctStr = String.format(" %.0f%%", usagePct * 100);
            var warningState = tokenTracker.getTokenWarningState();
            sb.append(AnsiStyle.RESET); // 先重置再着色
            sb.append(switch (warningState) {
                case NORMAL -> AnsiStyle.DIM + AnsiStyle.GREEN + pctStr;
                case WARNING -> AnsiStyle.BOLD + AnsiStyle.YELLOW + pctStr;
                case ERROR -> AnsiStyle.BOLD + AnsiStyle.RED + pctStr;
                case BLOCKING -> AnsiStyle.BOLD + AnsiStyle.RED + "⚠" + pctStr;
            });
            sb.append(AnsiStyle.RESET).append(AnsiStyle.DIM);
        }

        // 费用
        if (cost > 0) {
            sb.append(String.format("  $%.4f", cost));
        }

        // API 调用次数
        sb.append("  │  ").append(apiCalls).append(" calls");

        // 工作目录
        sb.append("  │  ").append(workDir);

        sb.append(AnsiStyle.RESET);

        return sb.toString();
    }

    /** 清除状态行 */
    private void clearStatusLine() {
        out.print("\033[s\033[999;1H\033[2K\033[u");
        out.flush();
    }

    /** 缩写路径：将 home 目录替换为 ~ */
    private String abbreviatePath(String path) {
        if (path == null) return "";
        String home = System.getProperty("user.home");
        if (path.startsWith(home)) {
            return "~" + path.substring(home.length());
        }
        // 过长时截断
        if (path.length() > 40) {
            return "..." + path.substring(path.length() - 37);
        }
        return path;
    }

    public boolean isEnabled() {
        return enabled;
    }
}
