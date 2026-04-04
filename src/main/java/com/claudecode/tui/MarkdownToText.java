package com.claudecode.tui;

import io.mybatis.jink.component.Renderable;
import io.mybatis.jink.component.Text;
import io.mybatis.jink.style.Color;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 简化版 Markdown → jink Text 转换器。
 * <p>
 * 支持：
 * - 标题（# ## ###）
 * - 粗体（**text**）
 * - 行内代码（`code`）
 * - 代码块（```...```）
 * - 列表项（- item, * item, 数字列表）
 * <p>
 * 注意：jink 的 VirtualScreen 会 strip ANSI，所以不能用 ANSI 预渲染。
 * 所有样式通过 jink Text API 设置。
 */
public class MarkdownToText {

    private static final Pattern HEADER_PATTERN = Pattern.compile("^(#{1,6})\\s+(.+)$");
    private static final Pattern BOLD_PATTERN = Pattern.compile("\\*\\*(.+?)\\*\\*");
    private static final Pattern INLINE_CODE_PATTERN = Pattern.compile("`([^`]+)`");
    private static final Pattern UNORDERED_LIST_PATTERN = Pattern.compile("^\\s*[-*]\\s+(.+)$");
    private static final Pattern ORDERED_LIST_PATTERN = Pattern.compile("^\\s*(\\d+)\\.\\s+(.+)$");

    /**
     * 将 Markdown 文本转换为 jink Text 行列表。
     * 每个元素代表渲染后的一行。
     */
    public static List<Renderable> convert(String markdown) {
        List<Renderable> result = new ArrayList<>();
        String[] lines = markdown.split("\n", -1);
        boolean inCodeBlock = false;
        String codeLanguage = "";

        for (String line : lines) {
            // 代码块开始/结束
            if (line.trim().startsWith("```")) {
                if (inCodeBlock) {
                    inCodeBlock = false;
                    codeLanguage = "";
                } else {
                    inCodeBlock = true;
                    codeLanguage = line.trim().substring(3).trim();
                    if (!codeLanguage.isEmpty()) {
                        result.add(Text.of("  ┌─ " + codeLanguage).color(Color.BRIGHT_BLACK));
                    } else {
                        result.add(Text.of("  ┌─").color(Color.BRIGHT_BLACK));
                    }
                }
                continue;
            }

            if (inCodeBlock) {
                result.add(Text.of("  │ " + line).color(Color.BRIGHT_YELLOW));
                continue;
            }

            // 空行
            if (line.isBlank()) {
                result.add(Text.of(" "));
                continue;
            }

            // 标题
            Matcher headerMatcher = HEADER_PATTERN.matcher(line);
            if (headerMatcher.matches()) {
                int level = headerMatcher.group(1).length();
                String content = headerMatcher.group(2);
                String prefix = switch (level) {
                    case 1 -> "▌ ";
                    case 2 -> "  ▸ ";
                    default -> "    ▹ ";
                };
                Color color = switch (level) {
                    case 1 -> Color.BRIGHT_CYAN;
                    case 2 -> Color.BRIGHT_GREEN;
                    default -> Color.BRIGHT_YELLOW;
                };
                result.add(Text.of(
                        Text.of(prefix).color(color),
                        Text.of(content).color(color).bold()
                ));
                continue;
            }

            // 无序列表
            Matcher ulMatcher = UNORDERED_LIST_PATTERN.matcher(line);
            if (ulMatcher.matches()) {
                result.add(renderInline("  • " + ulMatcher.group(1)));
                continue;
            }

            // 有序列表
            Matcher olMatcher = ORDERED_LIST_PATTERN.matcher(line);
            if (olMatcher.matches()) {
                result.add(renderInline("  " + olMatcher.group(1) + ". " + olMatcher.group(2)));
                continue;
            }

            // 普通文本行（处理行内格式）
            result.add(renderInline(line));
        }

        return result;
    }

    /**
     * 渲染行内格式（粗体、行内代码）
     */
    private static Text renderInline(String text) {
        List<Object> parts = new ArrayList<>();

        // 交替匹配 **bold** 和 `code`
        Pattern combined = Pattern.compile("(\\*\\*(.+?)\\*\\*)|(`([^`]+)`)");
        Matcher m = combined.matcher(text);

        int lastEnd = 0;
        while (m.find()) {
            // 匹配前的普通文本
            if (m.start() > lastEnd) {
                parts.add(Text.of(text.substring(lastEnd, m.start())).color(Color.WHITE));
            }

            if (m.group(2) != null) {
                // 粗体
                parts.add(Text.of(m.group(2)).color(Color.WHITE).bold());
            } else if (m.group(4) != null) {
                // 行内代码
                parts.add(Text.of(m.group(4)).color(Color.BRIGHT_YELLOW));
            }

            lastEnd = m.end();
        }

        // 剩余文本
        if (lastEnd < text.length()) {
            parts.add(Text.of(text.substring(lastEnd)).color(Color.WHITE));
        }

        if (parts.isEmpty()) {
            return Text.of(text).color(Color.WHITE);
        }

        return Text.of(parts.toArray());
    }
}
