package com.claudecode.console;

import java.io.PrintStream;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Markdown 渲染器（增强版） —— 对应 claude-code/src/renderers/markdown.ts。
 * <p>
 * 将 AI 回复中的 Markdown 格式转换为终端 ANSI 样式输出。
 * 支持代码块语法高亮、有序列表、引用块、表格等。
 */
public class MarkdownRenderer {

    private final PrintStream out;

    // 各语言的关键字集合，用于代码高亮
    private static final Set<String> JAVA_KEYWORDS = Set.of(
            "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char",
            "class", "const", "continue", "default", "do", "double", "else", "enum",
            "extends", "final", "finally", "float", "for", "goto", "if", "implements",
            "import", "instanceof", "int", "interface", "long", "native", "new", "package",
            "private", "protected", "public", "return", "short", "static", "strictfp",
            "super", "switch", "synchronized", "this", "throw", "throws", "transient",
            "try", "void", "volatile", "while", "var", "record", "sealed", "permits",
            "yield", "when");

    private static final Set<String> JS_KEYWORDS = Set.of(
            "async", "await", "break", "case", "catch", "class", "const", "continue",
            "debugger", "default", "delete", "do", "else", "export", "extends", "false",
            "finally", "for", "from", "function", "if", "import", "in", "instanceof",
            "let", "new", "null", "of", "return", "super", "switch", "this", "throw",
            "true", "try", "typeof", "undefined", "var", "void", "while", "with", "yield");

    private static final Set<String> PYTHON_KEYWORDS = Set.of(
            "and", "as", "assert", "async", "await", "break", "class", "continue",
            "def", "del", "elif", "else", "except", "False", "finally", "for", "from",
            "global", "if", "import", "in", "is", "lambda", "None", "nonlocal", "not",
            "or", "pass", "raise", "return", "True", "try", "while", "with", "yield");

    private static final Set<String> SHELL_KEYWORDS = Set.of(
            "if", "then", "else", "elif", "fi", "for", "while", "do", "done", "case",
            "esac", "function", "return", "exit", "echo", "export", "source", "set",
            "unset", "local", "readonly", "declare", "cd", "pwd", "ls", "cat", "grep",
            "sed", "awk", "find", "mkdir", "rm", "cp", "mv", "chmod", "chown");

    private static final Set<String> SQL_KEYWORDS = Set.of(
            "SELECT", "FROM", "WHERE", "INSERT", "INTO", "VALUES", "UPDATE", "SET",
            "DELETE", "CREATE", "TABLE", "ALTER", "DROP", "INDEX", "JOIN", "LEFT",
            "RIGHT", "INNER", "OUTER", "ON", "AND", "OR", "NOT", "NULL", "IS",
            "IN", "LIKE", "BETWEEN", "ORDER", "BY", "GROUP", "HAVING", "LIMIT",
            "OFFSET", "AS", "DISTINCT", "COUNT", "SUM", "AVG", "MAX", "MIN");

    /** 语言到关键字集的映射 */
    private static final Map<String, Set<String>> LANG_KEYWORDS;
    static {
        var map = new java.util.HashMap<String, Set<String>>();
        map.put("java", JAVA_KEYWORDS);
        map.put("javascript", JS_KEYWORDS);
        map.put("js", JS_KEYWORDS);
        map.put("typescript", JS_KEYWORDS);
        map.put("ts", JS_KEYWORDS);
        map.put("python", PYTHON_KEYWORDS);
        map.put("py", PYTHON_KEYWORDS);
        map.put("bash", SHELL_KEYWORDS);
        map.put("sh", SHELL_KEYWORDS);
        map.put("shell", SHELL_KEYWORDS);
        map.put("sql", SQL_KEYWORDS);
        LANG_KEYWORDS = Map.copyOf(map);
    }

    // 高亮用的正则
    private static final Pattern STRING_PATTERN = Pattern.compile("(\"[^\"\\\\]*(\\\\.[^\"\\\\]*)*\"|'[^'\\\\]*(\\\\.[^'\\\\]*)*')");
    private static final Pattern NUMBER_PATTERN = Pattern.compile("\\b(\\d+\\.?\\d*[fFdDlL]?|0x[0-9a-fA-F]+)\\b");
    private static final Pattern SINGLE_LINE_COMMENT = Pattern.compile("(//.*|#.*)$");
    private static final Pattern ANNOTATION_PATTERN = Pattern.compile("(@\\w+)");

    public MarkdownRenderer(PrintStream out) {
        this.out = out;
    }

    /** 渲染 Markdown 文本 */
    public void render(String markdown) {
        if (markdown == null || markdown.isBlank()) return;

        boolean inCodeBlock = false;
        String codeBlockLang = "";
        boolean inTable = false;
        java.util.List<String> tableLines = new java.util.ArrayList<>();

        for (String line : markdown.lines().toList()) {
            // 代码块
            if (line.stripLeading().startsWith("```")) {
                // Flush any pending table
                if (inTable) {
                    renderTable(tableLines);
                    tableLines.clear();
                    inTable = false;
                }
                if (!inCodeBlock) {
                    codeBlockLang = line.stripLeading().substring(3).strip().toLowerCase();
                    inCodeBlock = true;
                    String langLabel = codeBlockLang.isEmpty() ? "code" : codeBlockLang;
                    out.println(AnsiStyle.dim("  ┌─" + langLabel + "─" + "─".repeat(Math.max(0, 40 - langLabel.length()))));
                    continue;
                } else {
                    inCodeBlock = false;
                    out.println(AnsiStyle.dim("  └" + "─".repeat(42)));
                    codeBlockLang = "";
                    continue;
                }
            }

            if (inCodeBlock) {
                out.println("  " + AnsiStyle.DIM + "│" + AnsiStyle.RESET + " " + highlightCode(line, codeBlockLang));
                continue;
            }

            // Table detection: lines containing | separators
            String stripped = line.stripLeading();
            if (stripped.contains("|") && (stripped.startsWith("|") || isTableLine(stripped))) {
                if (!inTable) {
                    inTable = true;
                }
                tableLines.add(stripped);
                continue;
            } else if (inTable) {
                renderTable(tableLines);
                tableLines.clear();
                inTable = false;
            }

            // 标题
            if (line.startsWith("### ")) {
                out.println(AnsiStyle.bold(AnsiStyle.CYAN + "  " + line.substring(4)) + AnsiStyle.RESET);
            } else if (line.startsWith("## ")) {
                out.println(AnsiStyle.bold(AnsiStyle.BLUE + "  " + line.substring(3)) + AnsiStyle.RESET);
            } else if (line.startsWith("# ")) {
                out.println(AnsiStyle.bold(AnsiStyle.MAGENTA + "  " + line.substring(2)) + AnsiStyle.RESET);
            }
            // 引用块
            else if (stripped.startsWith("> ")) {
                String quoteText = stripped.substring(2);
                out.println("  " + AnsiStyle.DIM + "┃" + AnsiStyle.RESET + " " + AnsiStyle.ITALIC + renderInline(quoteText) + AnsiStyle.RESET);
            }
            // 有序列表
            else if (stripped.matches("^\\d+\\.\\s+.*")) {
                Matcher m = Pattern.compile("^(\\s*)(\\d+)\\.\\s+(.*)").matcher(line);
                if (m.matches()) {
                    String indent = m.group(1);
                    String num = m.group(2);
                    String text = m.group(3);
                    out.println("  " + indent + AnsiStyle.CYAN + num + "." + AnsiStyle.RESET + " " + renderInline(text));
                } else {
                    out.println("  " + renderInline(line));
                }
            }
            // 无序列表
            else if (stripped.startsWith("- ") || stripped.startsWith("* ")) {
                int indent = line.length() - stripped.length();
                String prefix = " ".repeat(indent);
                out.println("  " + prefix + AnsiStyle.CYAN + "•" + AnsiStyle.RESET + " " + renderInline(stripped.substring(2)));
            }
            // 复选框列表
            else if (stripped.startsWith("- [ ] ")) {
                out.println("  " + AnsiStyle.DIM + "☐" + AnsiStyle.RESET + " " + renderInline(stripped.substring(6)));
            } else if (stripped.startsWith("- [x] ") || stripped.startsWith("- [X] ")) {
                out.println("  " + AnsiStyle.GREEN + "☑" + AnsiStyle.RESET + " " + renderInline(stripped.substring(6)));
            }
            // 分隔线
            else if (stripped.matches("^[-*]{3,}$")) {
                out.println(AnsiStyle.dim("  " + "─".repeat(42)));
            }
            // 普通文本
            else {
                out.println("  " + renderInline(line));
            }
        }

        // Flush any remaining table
        if (inTable) {
            renderTable(tableLines);
        }
    }

    // ==================== 表格渲染 ====================

    /**
     * 检测行是否是表格行（包含至少一个 | 且有文本内容）。
     */
    private boolean isTableLine(String line) {
        if (!line.contains("|")) return false;
        // Separator lines like |---|---|
        if (line.matches("^\\|?[\\s:-]+\\|[\\s|:-]*$")) return true;
        // Content lines
        String[] parts = line.split("\\|");
        return parts.length >= 2;
    }

    /**
     * 渲染 Markdown 表格为对齐的终端输出。
     * 支持标准 Markdown 表格格式（带/不带首尾 |）。
     */
    private void renderTable(java.util.List<String> tableLines) {
        if (tableLines.isEmpty()) return;

        // Parse all rows
        var rows = new java.util.ArrayList<String[]>();
        int separatorIdx = -1;

        for (int i = 0; i < tableLines.size(); i++) {
            String line = tableLines.get(i).strip();
            // Remove leading/trailing |
            if (line.startsWith("|")) line = line.substring(1);
            if (line.endsWith("|")) line = line.substring(0, line.length() - 1);

            // Check if separator line
            if (line.matches("[\\s:-]+\\|[\\s|:-]*") || line.matches("[\\s:-]+")) {
                separatorIdx = i;
                continue;
            }

            String[] cells = line.split("\\|");
            for (int j = 0; j < cells.length; j++) {
                cells[j] = cells[j].strip();
            }
            rows.add(cells);
        }

        if (rows.isEmpty()) return;

        // Calculate column widths
        int maxCols = rows.stream().mapToInt(r -> r.length).max().orElse(0);
        int[] widths = new int[maxCols];
        for (String[] row : rows) {
            for (int j = 0; j < row.length && j < maxCols; j++) {
                widths[j] = Math.max(widths[j], stripAnsi(row[j]).length());
            }
        }
        // Ensure minimum width
        for (int j = 0; j < widths.length; j++) {
            widths[j] = Math.max(widths[j], 3);
        }

        // Render
        boolean isHeader = true;
        for (String[] row : rows) {
            StringBuilder sb = new StringBuilder("  ");
            if (isHeader) {
                // Header with bold
                sb.append("│ ");
                for (int j = 0; j < maxCols; j++) {
                    String cell = j < row.length ? row[j] : "";
                    sb.append(AnsiStyle.bold(padRight(cell, widths[j])));
                    if (j < maxCols - 1) sb.append(" │ ");
                }
                sb.append(" │");
                out.println(sb);

                // Separator
                StringBuilder sep = new StringBuilder("  ├─");
                for (int j = 0; j < maxCols; j++) {
                    sep.append("─".repeat(widths[j]));
                    if (j < maxCols - 1) sep.append("─┼─");
                }
                sep.append("─┤");
                out.println(AnsiStyle.dim(sep.toString()));
                isHeader = false;
            } else {
                sb.append("│ ");
                for (int j = 0; j < maxCols; j++) {
                    String cell = j < row.length ? row[j] : "";
                    sb.append(padRight(renderInline(cell), widths[j] + (renderInline(cell).length() - stripAnsi(renderInline(cell)).length())));
                    if (j < maxCols - 1) sb.append(" │ ");
                }
                sb.append(" │");
                out.println(sb);
            }
        }
    }

    /** Strip ANSI escape codes for width calculation */
    private String stripAnsi(String s) {
        return s.replaceAll("\u001B\\[[;\\d]*m", "");
    }

    /** Pad string to target width (considering visible length) */
    private String padRight(String s, int width) {
        int visible = stripAnsi(s).length();
        int padding = width - visible;
        if (padding <= 0) return s;
        return s + " ".repeat(padding);
    }

    // ==================== 代码语法高亮 ====================

    /**
     * 基于语言的代码行高亮。
     * 着色优先级：注释 > 字符串 > 注解 > 关键字 > 数字。
     */
    private String highlightCode(String line, String lang) {
        if (lang.isEmpty() || !LANG_KEYWORDS.containsKey(lang)) {
            // 未知语言：仅着绿色
            return AnsiStyle.BRIGHT_GREEN + line + AnsiStyle.RESET;
        }

        Set<String> keywords = LANG_KEYWORDS.get(lang);
        StringBuilder result = new StringBuilder();

        // 简单的逐行高亮：先检测注释和字符串区间，再对非特殊区间着色关键字
        // 为简化实现，采用分段替换策略

        String processed = line;

        // 1. 注释（行末 // 或 #）—— 灰色斜体
        Matcher commentMatcher = SINGLE_LINE_COMMENT.matcher(processed);
        if (commentMatcher.find()) {
            String beforeComment = processed.substring(0, commentMatcher.start());
            String comment = commentMatcher.group();
            return highlightNonComment(beforeComment, keywords, lang)
                    + AnsiStyle.BRIGHT_BLACK + AnsiStyle.ITALIC + comment + AnsiStyle.RESET;
        }

        return highlightNonComment(processed, keywords, lang);
    }

    /** 对非注释部分进行高亮 */
    private String highlightNonComment(String code, Set<String> keywords, String lang) {
        // 用占位符保护字符串字面量
        var stringRanges = new java.util.ArrayList<int[]>();
        Matcher strMatcher = STRING_PATTERN.matcher(code);
        while (strMatcher.find()) {
            stringRanges.add(new int[]{strMatcher.start(), strMatcher.end()});
        }

        StringBuilder result = new StringBuilder();
        int pos = 0;

        for (int[] range : stringRanges) {
            // 高亮字符串之前的部分
            if (range[0] > pos) {
                result.append(highlightSegment(code.substring(pos, range[0]), keywords, lang));
            }
            // 字符串本身着黄色
            result.append(AnsiStyle.YELLOW).append(code, range[0], range[1]).append(AnsiStyle.RESET);
            pos = range[1];
        }

        // 最后一段
        if (pos < code.length()) {
            result.append(highlightSegment(code.substring(pos), keywords, lang));
        }

        return result.toString();
    }

    /** 对普通代码段（无字符串）进行关键字和数字高亮 */
    private String highlightSegment(String segment, Set<String> keywords, String lang) {
        // 注解（@Annotation）— 仅 Java/Python
        if (lang.equals("java") || lang.equals("python") || lang.equals("py")) {
            Matcher annMatcher = ANNOTATION_PATTERN.matcher(segment);
            segment = annMatcher.replaceAll(AnsiStyle.BRIGHT_YELLOW + "$1" + AnsiStyle.RESET);
        }

        // 关键字着色 — 使用 word boundary 匹配
        for (String kw : keywords) {
            // SQL 关键字大小写不敏感
            if (lang.equals("sql")) {
                segment = segment.replaceAll("(?i)\\b(" + Pattern.quote(kw) + ")\\b",
                        AnsiStyle.BRIGHT_CYAN + "$1" + AnsiStyle.RESET);
            } else {
                segment = segment.replaceAll("\\b(" + Pattern.quote(kw) + ")\\b",
                        AnsiStyle.BRIGHT_CYAN + "$1" + AnsiStyle.RESET);
            }
        }

        // 数字着色
        Matcher numMatcher = NUMBER_PATTERN.matcher(segment);
        segment = numMatcher.replaceAll(AnsiStyle.BRIGHT_MAGENTA + "$1" + AnsiStyle.RESET);

        // true/false/null 着色
        segment = segment.replaceAll("\\b(true|false|null|None|nil)\\b",
                AnsiStyle.BRIGHT_RED + "$1" + AnsiStyle.RESET);

        return segment;
    }

    // ==================== 行内格式 ====================

    /** 行内格式渲染 */
    private String renderInline(String text) {
        // 粗体 **text**
        text = text.replaceAll("\\*\\*(.+?)\\*\\*", AnsiStyle.BOLD + "$1" + AnsiStyle.RESET);
        // 行内代码 `text`
        text = text.replaceAll("`(.+?)`", AnsiStyle.BRIGHT_GREEN + "$1" + AnsiStyle.RESET);
        // 斜体 *text*（需避免匹配粗体中的 *）
        text = text.replaceAll("(?<!\\*)\\*([^*]+?)\\*(?!\\*)", AnsiStyle.ITALIC + "$1" + AnsiStyle.RESET);
        // 删除线 ~~text~~
        text = text.replaceAll("~~(.+?)~~", AnsiStyle.DIM + "$1" + AnsiStyle.RESET);
        // 链接 [text](url) → text (url)
        text = text.replaceAll("\\[(.+?)]\\((.+?)\\)", AnsiStyle.UNDERLINE + "$1" + AnsiStyle.RESET + AnsiStyle.DIM + " ($2)" + AnsiStyle.RESET);
        return text;
    }

    // ==================== 流式 Markdown 渲染 ====================

    /**
     * 流式渲染状态 —— 跨行追踪代码块等多行结构。
     * 每次 REPL 对话轮次创建一个新实例。
     */
    public static class StreamState {
        boolean inCodeBlock = false;
        String codeLang = "";
    }

    /**
     * 渲染单行流式文本（无前缀缩进，由调用方处理）。
     * <p>
     * 支持：代码块（带语法高亮）、标题、列表、引用、行内格式（粗体/斜体/代码）。
     * 代码块状态通过 {@link StreamState} 跨行维护。
     *
     * @param line  一行完整文本（不含换行符）
     * @param state 跨行状态（代码块追踪）
     * @return 带 ANSI 样式的渲染结果
     */
    public String renderStreamingLine(String line, StreamState state) {
        String stripped = line.stripLeading();

        // 代码块边界
        if (stripped.startsWith("```")) {
            if (!state.inCodeBlock) {
                state.codeLang = stripped.substring(3).strip().toLowerCase();
                state.inCodeBlock = true;
                String langLabel = state.codeLang.isEmpty() ? "code" : state.codeLang;
                return AnsiStyle.dim("┌─" + langLabel + "─" + "─".repeat(Math.max(0, 40 - langLabel.length())));
            } else {
                state.inCodeBlock = false;
                state.codeLang = "";
                return AnsiStyle.dim("└" + "─".repeat(42));
            }
        }

        // 代码块内容（语法高亮）
        if (state.inCodeBlock) {
            return AnsiStyle.DIM + "│" + AnsiStyle.RESET + " " + highlightCode(line, state.codeLang);
        }

        // 标题
        if (stripped.startsWith("### ")) {
            return AnsiStyle.bold(AnsiStyle.CYAN + stripped.substring(4)) + AnsiStyle.RESET;
        } else if (stripped.startsWith("## ")) {
            return AnsiStyle.bold(AnsiStyle.BLUE + stripped.substring(3)) + AnsiStyle.RESET;
        } else if (stripped.startsWith("# ")) {
            return AnsiStyle.bold(AnsiStyle.MAGENTA + stripped.substring(2)) + AnsiStyle.RESET;
        }

        // 引用块
        if (stripped.startsWith("> ")) {
            return AnsiStyle.DIM + "┃" + AnsiStyle.RESET + " " + AnsiStyle.ITALIC + renderInline(stripped.substring(2)) + AnsiStyle.RESET;
        }

        // 复选框列表（在无序列表前检测）
        if (stripped.startsWith("- [ ] ")) {
            return AnsiStyle.DIM + "☐" + AnsiStyle.RESET + " " + renderInline(stripped.substring(6));
        }
        if (stripped.startsWith("- [x] ") || stripped.startsWith("- [X] ")) {
            return AnsiStyle.GREEN + "☑" + AnsiStyle.RESET + " " + renderInline(stripped.substring(6));
        }

        // 无序列表
        if (stripped.startsWith("- ") || stripped.startsWith("* ")) {
            int indent = line.length() - stripped.length();
            String prefix = " ".repeat(indent);
            return prefix + AnsiStyle.CYAN + "•" + AnsiStyle.RESET + " " + renderInline(stripped.substring(2));
        }

        // 有序列表
        if (stripped.matches("^\\d+\\.\\s+.*")) {
            Matcher m = Pattern.compile("^(\\s*)(\\d+)\\.\\s+(.*)").matcher(line);
            if (m.matches()) {
                return m.group(1) + AnsiStyle.CYAN + m.group(2) + "." + AnsiStyle.RESET + " " + renderInline(m.group(3));
            }
        }

        // 分隔线
        if (stripped.matches("^[-*]{3,}$")) {
            return AnsiStyle.dim("─".repeat(42));
        }

        // 普通文本（行内格式渲染）
        return renderInline(line);
    }
}
