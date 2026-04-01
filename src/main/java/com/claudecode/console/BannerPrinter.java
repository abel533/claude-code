package com.claudecode.console;

import java.io.PrintStream;
import java.util.regex.Pattern;

/**
 * Banner 打印器 —— 对应 claude-code/src/components/Banner.tsx。
 * <p>
 * 在启动时打印带边框的 Logo 和版本信息。
 * 参考 Copilot CLI / Claude Code 的边框样式。
 */
public class BannerPrinter {

    private static final String VERSION = "0.1.0-SNAPSHOT";

    // 匹配 ANSI 转义序列的正则
    private static final Pattern ANSI_PATTERN = Pattern.compile("\033\\[[0-9;]*m");

    /**
     * 打印带边框的启动 Banner。
     */
    public static void printBoxed(PrintStream out, String provider, String model,
                                   String baseUrl, String workDir,
                                   int toolCount, int cmdCount, String termInfo) {
        // 内容宽度（不含左右边框 │）
        int innerWidth = 88;

        // Logo（ASCII 冒烟咖啡杯 — 每行精确 20 字符宽，含左右空格）
        String[] logo = {
                "        ) ) )       ",
                "     ╭────────╮     ",
                "     │ ~~~~~~ │─╮   ",
                "     │ CLAUDE │ │   ",
                "     │  CODE  │─╯   ",
                "     ╰─┬────┬─╯     "
        };
        int logoVisualWidth = 20;

        // 右侧信息（纯可见文本 + ANSI 颜色）
        String[] rightInfo = {
                "",
                AnsiStyle.BOLD + "Welcome!" + AnsiStyle.RESET,
                "",
                AnsiStyle.DIM + "Provider: " + AnsiStyle.RESET + AnsiStyle.CYAN + provider.toUpperCase() + AnsiStyle.RESET
                        + AnsiStyle.DIM + "  Model: " + AnsiStyle.RESET + AnsiStyle.CYAN + model + AnsiStyle.RESET,
                AnsiStyle.DIM + "Work Dir: " + workDir + AnsiStyle.RESET,
                AnsiStyle.DIM + "Tools: " + toolCount + " | Commands: " + cmdCount + " | " + termInfo + AnsiStyle.RESET,
        };

        // ── 顶部边框 ──
        // 格式: ╭─── Claude Code Java v0.1.0-SNAPSHOT ───...───╮
        String title = "Claude Code Java";
        String versionStr = " v" + VERSION + " ";
        String prefix = "─── ";
        // 可见宽度: prefix + title + versionStr + 补充的 ─
        int titleVisibleLen = prefix.length() + title.length() + versionStr.length();
        int trailingDashes = innerWidth - titleVisibleLen;
        if (trailingDashes < 1) trailingDashes = 1;

        out.println();
        out.println("  ╭" + prefix
                + AnsiStyle.BOLD + AnsiStyle.BRIGHT_CYAN + title + AnsiStyle.RESET
                + AnsiStyle.DIM + versionStr + AnsiStyle.RESET
                + "─".repeat(trailingDashes) + "╮");

        // ── 内容行（双列布局） ──
        // 每行格式: │ [logo] │ [rightInfo] ... padding ... │
        // 中间分隔符 " │ " 占 3 个可见字符
        int separatorWidth = 3;
        int rightAreaWidth = innerWidth - logoVisualWidth - separatorWidth;

        int maxRows = Math.max(logo.length, rightInfo.length);
        for (int i = 0; i < maxRows; i++) {
            String leftPart = i < logo.length ? logo[i] : "";
            String rightPart = i < rightInfo.length ? rightInfo[i] : "";

            // 左侧 logo（纯文本，直接 pad）
            String paddedLeft = padRight(leftPart, logoVisualWidth);

            // 右侧信息需要计算可见长度，补齐空格到 rightAreaWidth
            int rightVisible = visibleLength(rightPart);
            int rightPadding = rightAreaWidth - rightVisible;
            if (rightPadding < 0) rightPadding = 0;

            out.println("  │"
                    + AnsiStyle.BRIGHT_CYAN + paddedLeft + AnsiStyle.RESET
                    + AnsiStyle.DIM + " │ " + AnsiStyle.RESET
                    + rightPart + " ".repeat(rightPadding)
                    + "│");
        }

        // ── 底部边框 ──
        out.println("  ╰" + "─".repeat(innerWidth) + "╯");
    }

    /**
     * 精简版 banner（用于窄终端或 Scanner 模式）。
     */
    public static void printCompact(PrintStream out) {
        out.println();
        out.println(AnsiStyle.BRIGHT_CYAN + AnsiStyle.BOLD + "  ◆ Claude Code (Java)" + AnsiStyle.RESET
                + AnsiStyle.dim("  v" + VERSION));
        out.println(AnsiStyle.dim("  Type /help for commands  •  Ctrl+D to exit"));
        out.println();
    }

    /** 计算去除 ANSI 转义后的可见字符宽度 */
    private static int visibleLength(String s) {
        if (s == null || s.isEmpty()) return 0;
        return ANSI_PATTERN.matcher(s).replaceAll("").length();
    }

    /** 右侧补空格到指定视觉宽度 */
    private static String padRight(String s, int width) {
        int len = s.length();
        if (len >= width) return s;
        return s + " ".repeat(width - len);
    }

    /** 获取版本号 */
    public static String getVersion() {
        return VERSION;
    }
}
