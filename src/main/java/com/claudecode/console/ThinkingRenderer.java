package com.claudecode.console;

import java.io.PrintStream;

/**
 * Thinking 内容渲染器 —— 对应 claude-code/src/components/Thinking.tsx。
 * <p>
 * 显示 AI 模型的思考过程（extended thinking）。
 */
public class ThinkingRenderer {

    private final PrintStream out;

    public ThinkingRenderer(PrintStream out) {
        this.out = out;
    }

    /** 渲染 thinking 内容块 */
    public void render(String thinkingContent) {
        if (thinkingContent == null || thinkingContent.isBlank()) {
            return;
        }

        out.println();
        out.println(AnsiStyle.DIM + AnsiStyle.ITALIC + "  💭 Thinking..." + AnsiStyle.RESET);

        // 显示 thinking 内容（缩进并用暗色）
        for (String line : thinkingContent.lines().toList()) {
            out.println(AnsiStyle.DIM + "  │ " + line + AnsiStyle.RESET);
        }
        out.println();
    }

    /** 渲染 thinking 开始标记 */
    public void renderStart() {
        out.print(AnsiStyle.DIM + AnsiStyle.ITALIC + "  💭 Thinking..." + AnsiStyle.RESET);
    }

    /** 渲染 thinking 结束标记 */
    public void renderEnd() {
        out.println(AnsiStyle.clearLine());
    }
}
