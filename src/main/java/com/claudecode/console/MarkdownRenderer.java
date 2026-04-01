package com.claudecode.console;

import java.io.PrintStream;

/**
 * Markdown 简易渲染器 —— 对应 claude-code/src/renderers/markdown.ts。
 * <p>
 * 将 AI 回复中的 Markdown 格式转换为终端 ANSI 样式输出。
 * 这是一个简化版，支持常见格式。
 */
public class MarkdownRenderer {

    private final PrintStream out;

    public MarkdownRenderer(PrintStream out) {
        this.out = out;
    }

    /** 渲染 Markdown 文本 */
    public void render(String markdown) {
        if (markdown == null || markdown.isBlank()) return;

        boolean inCodeBlock = false;
        String codeBlockLang = "";

        for (String line : markdown.lines().toList()) {
            // 代码块
            if (line.stripLeading().startsWith("```")) {
                if (!inCodeBlock) {
                    codeBlockLang = line.stripLeading().substring(3).strip();
                    inCodeBlock = true;
                    out.println(AnsiStyle.dim("  ┌─" + (codeBlockLang.isEmpty() ? "code" : codeBlockLang) + "─"));
                    continue;
                } else {
                    inCodeBlock = false;
                    out.println(AnsiStyle.dim("  └─────"));
                    continue;
                }
            }

            if (inCodeBlock) {
                out.println(AnsiStyle.BRIGHT_GREEN + "  │ " + line + AnsiStyle.RESET);
                continue;
            }

            // 标题
            if (line.startsWith("### ")) {
                out.println(AnsiStyle.bold(AnsiStyle.CYAN + "  " + line.substring(4)) + AnsiStyle.RESET);
            } else if (line.startsWith("## ")) {
                out.println(AnsiStyle.bold(AnsiStyle.BLUE + "  " + line.substring(3)) + AnsiStyle.RESET);
            } else if (line.startsWith("# ")) {
                out.println(AnsiStyle.bold(AnsiStyle.MAGENTA + "  " + line.substring(2)) + AnsiStyle.RESET);
            }
            // 列表项
            else if (line.stripLeading().startsWith("- ") || line.stripLeading().startsWith("* ")) {
                out.println("  " + AnsiStyle.CYAN + "•" + AnsiStyle.RESET + " " + renderInline(line.stripLeading().substring(2)));
            }
            // 分隔线
            else if (line.strip().matches("^-{3,}$") || line.strip().matches("^\\*{3,}$")) {
                out.println(AnsiStyle.dim("  ─────────────────────────────────────────"));
            }
            // 普通文本
            else {
                out.println("  " + renderInline(line));
            }
        }
    }

    /** 行内格式渲染 */
    private String renderInline(String text) {
        // 粗体 **text**
        text = text.replaceAll("\\*\\*(.+?)\\*\\*", AnsiStyle.BOLD + "$1" + AnsiStyle.RESET);
        // 行内代码 `text`
        text = text.replaceAll("`(.+?)`", AnsiStyle.BRIGHT_GREEN + "$1" + AnsiStyle.RESET);
        // 斜体 *text*
        text = text.replaceAll("\\*(.+?)\\*", AnsiStyle.ITALIC + "$1" + AnsiStyle.RESET);
        return text;
    }
}
