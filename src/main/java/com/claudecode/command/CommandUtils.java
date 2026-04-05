package com.claudecode.command;

import com.claudecode.console.AnsiStyle;

/**
 * 命令输出格式化工具 —— 消除命令实现中的重复格式化代码。
 * <p>
 * 提供标准化的命令输出格式：标题、分隔线、成功/失败消息、
 * 字节/时间格式化、进度条等。
 */
public final class CommandUtils {

    private CommandUtils() {}

    // ==================== 标题和分隔线 ====================

    /**
     * 格式化命令标题行。
     * 输出: "\n  ⚡ Title\n  ──────────\n\n"
     */
    public static String header(String emoji, String title) {
        return "\n" + AnsiStyle.bold("  " + emoji + " " + title + "\n")
                + separator(40) + "\n\n";
    }

    /**
     * 格式化命令标题行（无 emoji）。
     */
    public static String header(String title) {
        return "\n" + AnsiStyle.bold("  " + title + "\n")
                + separator(40) + "\n\n";
    }

    /**
     * 生成分隔线。
     */
    public static String separator(int width) {
        return "  " + "─".repeat(width);
    }

    // ==================== 状态消息 ====================

    /**
     * 成功消息（绿色 ✓）。
     */
    public static String success(String message) {
        return "  " + AnsiStyle.green("✓") + " " + message;
    }

    /**
     * 错误消息（红色 ✗）。
     */
    public static String error(String message) {
        return "  " + AnsiStyle.red("✗") + " " + message;
    }

    /**
     * 警告消息（黄色 ⚠）。
     */
    public static String warning(String message) {
        return "  " + AnsiStyle.yellow("⚠") + " " + message;
    }

    /**
     * 信息消息（蓝色 ℹ）。
     */
    public static String info(String message) {
        return "  " + AnsiStyle.cyan("ℹ") + " " + message;
    }

    /**
     * 子标题（粗体缩进）。
     */
    public static String subtitle(String title) {
        return AnsiStyle.bold("  " + title);
    }

    /**
     * 键值对行。
     */
    public static String keyValue(String key, Object value) {
        return String.format("  %-12s %s", key + ":", value);
    }

    /**
     * 键值对行（自定义宽度）。
     */
    public static String keyValue(String key, Object value, int keyWidth) {
        return String.format("  %-" + keyWidth + "s %s", key + ":", value);
    }

    // ==================== 格式化工具 ====================

    /**
     * 格式化字节数为人类可读形式。
     */
    public static String formatBytes(long bytes) {
        if (bytes < 0) return "N/A";
        if (bytes >= 1_073_741_824) return String.format("%.1fGB", bytes / 1_073_741_824.0);
        if (bytes >= 1_048_576) return String.format("%.1fMB", bytes / 1_048_576.0);
        if (bytes >= 1_024) return String.format("%.0fKB", bytes / 1_024.0);
        return bytes + "B";
    }

    /**
     * 格式化秒数为人类可读时长。
     */
    public static String formatDuration(long seconds) {
        if (seconds < 0) return "N/A";
        if (seconds < 60) return seconds + "s";
        if (seconds < 3600) return (seconds / 60) + "m " + (seconds % 60) + "s";
        return (seconds / 3600) + "h " + ((seconds % 3600) / 60) + "m";
    }

    /**
     * 格式化毫秒为人类可读时长。
     */
    public static String formatMillis(long ms) {
        if (ms < 1000) return ms + "ms";
        return formatDuration(ms / 1000);
    }

    /**
     * 截断字符串到最大长度。
     */
    public static String truncate(String text, int maxLen) {
        if (text == null) return "";
        if (text.length() <= maxLen) return text;
        return text.substring(0, maxLen - 3) + "...";
    }

    // ==================== 进度条 ====================

    /**
     * 生成带颜色的进度条。
     */
    public static String progressBar(double ratio, int width) {
        ratio = Math.max(0, Math.min(1.0, ratio));
        int filled = (int) (ratio * width);
        String bar = "█".repeat(filled);
        String empty = "░".repeat(width - filled);

        String colored;
        if (ratio > 0.8) colored = AnsiStyle.red(bar);
        else if (ratio > 0.5) colored = AnsiStyle.yellow(bar);
        else colored = AnsiStyle.green(bar);

        return "[" + colored + empty + "] " + String.format("%.0f%%", ratio * 100);
    }

    // ==================== 参数解析 ====================

    /**
     * 安全解析命令参数（去空、null→空字符串）。
     */
    public static String parseArgs(String args) {
        return (args == null) ? "" : args.strip();
    }
}
