package com.claudecode.console;

import java.io.PrintStream;

/**
 * 工具调用状态渲染器 —— 对应 claude-code/src/components/ToolStatus.tsx。
 * <p>
 * 使用彩色 ● 圆点标识工具调用状态，配合 ⎿ 显示结果（参考 Claude Code 样式）。
 * 增强功能：进度条、权限对话框美化、计时器。
 */
public class ToolStatusRenderer {

    private final PrintStream out;

    public ToolStatusRenderer(PrintStream out) {
        this.out = out;
    }

    /** 渲染工具调用开始 */
    public void renderStart(String toolName, String args) {
        out.println();
        out.print(AnsiStyle.BRIGHT_BLUE + "  ● " + AnsiStyle.BOLD + toolName + AnsiStyle.RESET);

        // 提取并显示简短参数
        if (args != null && !args.isBlank()) {
            String summary = extractSummary(toolName, args);
            if (summary != null) {
                out.print(AnsiStyle.dim("(" + summary + ")"));
            }
        }
        out.println(AnsiStyle.dim("  running..."));
    }

    /** 渲染工具调用开始（带进度追踪） */
    public void renderStartWithTimer(String toolName, String args) {
        out.println();
        out.print(AnsiStyle.BRIGHT_BLUE + "  ● " + AnsiStyle.BOLD + toolName + AnsiStyle.RESET);

        if (args != null && !args.isBlank()) {
            String summary = extractSummary(toolName, args);
            if (summary != null) {
                out.print(AnsiStyle.dim(" " + summary));
            }
        }
        out.println(AnsiStyle.dim("  ⏱ running..."));
    }

    /** 渲染工具调用完成（带耗时） */
    public void renderEnd(String toolName, String result, long durationMs) {
        String timeStr = durationMs > 0 ? formatDuration(durationMs) : "";

        out.println(AnsiStyle.GREEN + "  ● " + AnsiStyle.BOLD + toolName + AnsiStyle.RESET
                + AnsiStyle.dim("  done")
                + (timeStr.isEmpty() ? "" : AnsiStyle.dim(" (" + timeStr + ")")));

        renderResultBlock(result);
    }

    /** 渲染工具调用完成 */
    public void renderEnd(String toolName, String result) {
        out.println(AnsiStyle.GREEN + "  ● " + AnsiStyle.BOLD + toolName + AnsiStyle.RESET
                + AnsiStyle.dim("  done"));

        renderResultBlock(result);
    }

    /** 渲染结果输出块 */
    private void renderResultBlock(String result) {
        if (result == null || result.isBlank()) return;

        String display = result;
        if (display.length() > 500) {
            display = display.substring(0, 497) + "...";
        }

        String[] lines = display.lines().toArray(String[]::new);
        for (int i = 0; i < lines.length; i++) {
            if (i == 0) {
                out.println(AnsiStyle.DIM + "  ⎿  " + lines[i] + AnsiStyle.RESET);
            } else {
                out.println(AnsiStyle.DIM + "     " + lines[i] + AnsiStyle.RESET);
            }
        }
    }

    /** 渲染工具错误 */
    public void renderError(String toolName, String error) {
        out.println(AnsiStyle.RED + "  ● " + AnsiStyle.BOLD + toolName + AnsiStyle.RESET
                + AnsiStyle.red("  error"));
        if (error != null) {
            out.println(AnsiStyle.DIM + "  ⎿  " + AnsiStyle.RED + error + AnsiStyle.RESET);
        }
    }

    /** 渲染权限请求对话框 */
    public void renderPermissionRequest(String toolName, String action, String detail) {
        out.println();
        out.println(AnsiStyle.YELLOW + "  ┌─────────────────────────────────────────────┐" + AnsiStyle.RESET);
        out.println(AnsiStyle.YELLOW + "  │" + AnsiStyle.RESET + " 🔐 " + AnsiStyle.bold("Permission Required")
                + "                       " + AnsiStyle.YELLOW + "│" + AnsiStyle.RESET);
        out.println(AnsiStyle.YELLOW + "  ├─────────────────────────────────────────────┤" + AnsiStyle.RESET);
        out.println(AnsiStyle.YELLOW + "  │" + AnsiStyle.RESET
                + " Tool: " + AnsiStyle.bold(toolName)
                + " ".repeat(Math.max(1, 37 - toolName.length()))
                + AnsiStyle.YELLOW + "│" + AnsiStyle.RESET);
        out.println(AnsiStyle.YELLOW + "  │" + AnsiStyle.RESET
                + " Action: " + truncPad(action, 35)
                + AnsiStyle.YELLOW + "│" + AnsiStyle.RESET);
        if (detail != null && !detail.isBlank()) {
            out.println(AnsiStyle.YELLOW + "  │" + AnsiStyle.RESET
                    + " Detail: " + AnsiStyle.dim(truncPad(detail, 35))
                    + AnsiStyle.YELLOW + "│" + AnsiStyle.RESET);
        }
        out.println(AnsiStyle.YELLOW + "  ├─────────────────────────────────────────────┤" + AnsiStyle.RESET);
        out.println(AnsiStyle.YELLOW + "  │" + AnsiStyle.RESET + " "
                + AnsiStyle.green("[y] Allow") + "  "
                + AnsiStyle.red("[n] Deny") + "  "
                + AnsiStyle.dim("[a] Always allow")
                + "  " + AnsiStyle.YELLOW + "│" + AnsiStyle.RESET);
        out.println(AnsiStyle.YELLOW + "  └─────────────────────────────────────────────┘" + AnsiStyle.RESET);
    }

    /** 渲染进度条 */
    public void renderProgress(String label, int current, int total) {
        if (total <= 0) return;
        int width = 30;
        int filled = (int) ((double) current / total * width);
        filled = Math.min(filled, width);

        StringBuilder bar = new StringBuilder();
        bar.append(AnsiStyle.BRIGHT_BLUE);
        bar.append("█".repeat(filled));
        bar.append(AnsiStyle.DIM);
        bar.append("░".repeat(width - filled));
        bar.append(AnsiStyle.RESET);

        String pct = String.format("%d%%", (int) ((double) current / total * 100));
        out.print(AnsiStyle.clearLine());
        out.print("  " + label + " " + bar + " " + AnsiStyle.dim(current + "/" + total + " " + pct));
        out.flush();
    }

    /** 渲染进度条（完成后换行） */
    public void renderProgressDone(String label, int total) {
        renderProgress(label, total, total);
        out.println();
    }

    /** 从 JSON 参数中提取人类可读的摘要 */
    private String extractSummary(String toolName, String args) {
        try {
            if (args.contains("\"command\"")) {
                int start = args.indexOf("\"command\"");
                int valStart = args.indexOf("\"", start + 10) + 1;
                int valEnd = args.indexOf("\"", valStart);
                if (valStart > 0 && valEnd > valStart) {
                    String cmd = args.substring(valStart, Math.min(valEnd, valStart + 60));
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
            if (args.contains("\"query\"")) {
                int start = args.indexOf("\"query\"");
                int valStart = args.indexOf("\"", start + 8) + 1;
                int valEnd = args.indexOf("\"", valStart);
                if (valStart > 0 && valEnd > valStart) {
                    return "\"" + args.substring(valStart, Math.min(valEnd, valStart + 60)) + "\"";
                }
            }
        } catch (Exception e) {
            // 忽略解析错误
        }
        return null;
    }

    private String formatDuration(long ms) {
        if (ms < 1000) return ms + "ms";
        if (ms < 60_000) return String.format("%.1fs", ms / 1000.0);
        return String.format("%dm%ds", ms / 60_000, (ms % 60_000) / 1000);
    }

    private String truncPad(String s, int width) {
        if (s == null) return " ".repeat(width);
        if (s.length() > width) return s.substring(0, width - 3) + "...";
        return s + " ".repeat(width - s.length());
    }
}
