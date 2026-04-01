package com.claudecode.console;

import java.io.PrintStream;

/**
 * 工具调用状态渲染器 —— 对应 claude-code/src/components/ToolStatus.tsx。
 * <p>
 * 在终端中显示工具调用的进度和结果。
 */
public class ToolStatusRenderer {

    private final PrintStream out;

    public ToolStatusRenderer(PrintStream out) {
        this.out = out;
    }

    /** 渲染工具调用开始 */
    public void renderStart(String toolName, String args) {
        out.println(AnsiStyle.dim("  ─────────────────────────────────────────"));
        out.print(AnsiStyle.YELLOW + "  ⚙ " + AnsiStyle.BOLD + toolName + AnsiStyle.RESET);
        out.println(AnsiStyle.dim("  running..."));
        // 如果有简短参数，显示
        if (args != null && !args.isBlank()) {
            String summary = extractSummary(toolName, args);
            if (summary != null) {
                out.println(AnsiStyle.dim("    " + summary));
            }
        }
    }

    /** 渲染工具调用完成 */
    public void renderEnd(String toolName, String result) {
        // 截断长结果
        String display = result;
        if (display != null && display.length() > 500) {
            display = display.substring(0, 497) + "...";
        }

        out.println(AnsiStyle.GREEN + "  ✓ " + AnsiStyle.BOLD + toolName + AnsiStyle.RESET
                + AnsiStyle.dim("  done"));
        if (display != null && !display.isBlank()) {
            // 缩进输出每一行
            for (String line : display.lines().toList()) {
                out.println(AnsiStyle.dim("    " + line));
            }
        }
        out.println(AnsiStyle.dim("  ─────────────────────────────────────────"));
    }

    /** 渲染工具错误 */
    public void renderError(String toolName, String error) {
        out.println(AnsiStyle.RED + "  ✗ " + AnsiStyle.BOLD + toolName + AnsiStyle.RESET
                + AnsiStyle.red("  error"));
        if (error != null) {
            out.println(AnsiStyle.red("    " + error));
        }
    }

    /** 从 JSON 参数中提取人类可读的摘要 */
    private String extractSummary(String toolName, String args) {
        try {
            // 简单提取关键字段
            if (args.contains("\"command\"")) {
                int start = args.indexOf("\"command\"");
                int valStart = args.indexOf("\"", start + 10) + 1;
                int valEnd = args.indexOf("\"", valStart);
                if (valStart > 0 && valEnd > valStart) {
                    String cmd = args.substring(valStart, Math.min(valEnd, valStart + 80));
                    return "$ " + cmd;
                }
            }
            if (args.contains("\"file_path\"")) {
                int start = args.indexOf("\"file_path\"");
                int valStart = args.indexOf("\"", start + 12) + 1;
                int valEnd = args.indexOf("\"", valStart);
                if (valStart > 0 && valEnd > valStart) {
                    return args.substring(valStart, valEnd);
                }
            }
            if (args.contains("\"pattern\"")) {
                int start = args.indexOf("\"pattern\"");
                int valStart = args.indexOf("\"", start + 10) + 1;
                int valEnd = args.indexOf("\"", valStart);
                if (valStart > 0 && valEnd > valStart) {
                    return "pattern: " + args.substring(valStart, valEnd);
                }
            }
        } catch (Exception e) {
            // 忽略解析错误
        }
        return null;
    }
}
