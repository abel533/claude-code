package com.claudecode.tool.impl;

import com.claudecode.tool.Tool;
import com.claudecode.tool.ToolContext;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Grep 搜索工具 —— 对应 claude-code/src/tools/grep/GrepTool.ts。
 * <p>
 * 在文件中搜索文本模式（正则），优先使用 ripgrep（rg），降级为系统 grep。
 * 支持多种输出模式、大小写、上下文行、多行匹配等参数。
 */
public class GrepTool implements Tool {

    private static final int DEFAULT_MAX_RESULTS = 100;

    @Override
    public String name() {
        return "Grep";
    }

    @Override
    public String description() {
        return """
            A powerful search tool built on ripgrep. Searches for a regex pattern in file contents \
            and returns matching lines with file paths and line numbers.

            IMPORTANT: ALWAYS use this Grep tool for searching file content. NEVER invoke `grep`, \
            `rg`, or `findstr` as a Bash command — using this dedicated tool allows the user to \
            better understand and review your searches.

            Uses ripgrep (rg) if available, falls back to system grep/findstr. Supports full regex \
            syntax. Pattern syntax uses ripgrep — literal braces need escaping (e.g., interface\\{\\}).

            Output modes:
            - "content": Shows matching lines with context (default). Supports context flags.
            - "files_with_matches": Shows only file paths containing matches. Use for broad discovery.
            - "count": Shows match counts per file.

            When doing open-ended searches requiring multiple rounds, use the Agent tool instead.""";
    }

    @Override
    public String inputSchema() {
        return """
            {
              "type": "object",
              "properties": {
                "pattern": {
                  "type": "string",
                  "description": "Regular expression pattern to search for"
                },
                "path": {
                  "type": "string",
                  "description": "Directory or file to search in (default: working directory)"
                },
                "include": {
                  "type": "string",
                  "description": "File glob pattern to include (e.g., '*.java')"
                },
                "type": {
                  "type": "string",
                  "description": "File type filter (e.g., 'java', 'py', 'ts', 'js'). Only works with ripgrep."
                },
                "output_mode": {
                  "type": "string",
                  "enum": ["content", "files_with_matches", "count"],
                  "description": "Output format. 'content' shows matching lines (default), 'files_with_matches' shows only file paths, 'count' shows match counts per file."
                },
                "case_insensitive": {
                  "type": "boolean",
                  "description": "Case insensitive search (default: false)"
                },
                "multiline": {
                  "type": "boolean",
                  "description": "Enable multiline mode where patterns can span lines (default: false)"
                },
                "context_before": {
                  "type": "integer",
                  "description": "Lines of context before each match"
                },
                "context_after": {
                  "type": "integer",
                  "description": "Lines of context after each match"
                },
                "head_limit": {
                  "type": "integer",
                  "description": "Limit output to first N results"
                }
              },
              "required": ["pattern"]
            }""";
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public String execute(Map<String, Object> input, ToolContext context) {
        String pattern = (String) input.get("pattern");
        String searchPath = (String) input.getOrDefault("path", ".");
        String include = (String) input.getOrDefault("include", null);
        String type = (String) input.getOrDefault("type", null);
        String outputMode = (String) input.getOrDefault("output_mode", "content");
        boolean caseInsensitive = Boolean.TRUE.equals(input.get("case_insensitive"));
        boolean multiline = Boolean.TRUE.equals(input.get("multiline"));
        Integer contextBefore = getInt(input, "context_before");
        Integer contextAfter = getInt(input, "context_after");
        int headLimit = getInt(input, "head_limit") != null
                ? getInt(input, "head_limit") : DEFAULT_MAX_RESULTS;

        Path baseDir = context.getWorkDir().resolve(searchPath).normalize();

        try {
            List<String> cmd = buildCommand(pattern, baseDir.toString(), include, type,
                    outputMode, caseInsensitive, multiline, contextBefore, contextAfter);
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(context.getWorkDir().toFile());
            pb.redirectErrorStream(true);

            Process process = pb.start();
            List<String> lines = new ArrayList<>();

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null && lines.size() < headLimit) {
                    lines.add(line);
                }
            }

            process.waitFor(30, TimeUnit.SECONDS);

            if (lines.isEmpty()) {
                return "No matches found for pattern: " + pattern;
            }

            StringBuilder sb = new StringBuilder();
            for (String line : lines) {
                sb.append(line).append("\n");
            }
            if (lines.size() >= headLimit) {
                sb.append("... (results truncated at ").append(headLimit).append(")\n");
            }
            return sb.toString().stripTrailing();

        } catch (Exception e) {
            return "Error searching: " + e.getMessage();
        }
    }

    /** 构建搜索命令（优先 rg，降级 grep/findstr） */
    private List<String> buildCommand(String pattern, String path, String include,
                                       String type, String outputMode, boolean caseInsensitive,
                                       boolean multiline, Integer contextBefore, Integer contextAfter) {
        boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");
        List<String> cmd = new ArrayList<>();

        if (isCommandAvailable("rg")) {
            cmd.add("rg");
            cmd.add("--no-heading");
            cmd.add("--color=never");

            // Output mode
            switch (outputMode) {
                case "files_with_matches" -> cmd.add("--files-with-matches");
                case "count" -> cmd.add("--count");
                default -> cmd.add("--line-number");
            }

            // Case insensitive
            if (caseInsensitive) {
                cmd.add("--ignore-case");
            }

            // Multiline
            if (multiline) {
                cmd.add("--multiline");
            }

            // Context lines (only for content mode)
            if ("content".equals(outputMode)) {
                if (contextBefore != null) {
                    cmd.add("--before-context=" + contextBefore);
                }
                if (contextAfter != null) {
                    cmd.add("--after-context=" + contextAfter);
                }
            }

            // File type filter
            if (type != null) {
                cmd.add("--type=" + type);
            }

            // Include glob
            if (include != null) {
                cmd.add("--glob=" + include);
            }

            cmd.add(pattern);
            cmd.add(path);

        } else if (isWindows) {
            // Windows fallback: findstr (limited functionality)
            cmd.add("findstr");
            cmd.add("/s");
            cmd.add("/n");
            cmd.add("/r");
            if (caseInsensitive) {
                cmd.add("/i");
            }
            cmd.add(pattern);
            if (include != null) {
                cmd.add(path + "\\" + include);
            } else {
                cmd.add(path + "\\*");
            }
        } else {
            // Unix fallback: grep
            cmd.add("grep");
            cmd.add("-rn");
            cmd.add("--color=never");

            if (caseInsensitive) {
                cmd.add("-i");
            }

            if ("files_with_matches".equals(outputMode)) {
                cmd.add("-l");
            } else if ("count".equals(outputMode)) {
                cmd.add("-c");
            }

            if ("content".equals(outputMode)) {
                if (contextBefore != null) {
                    cmd.add("-B" + contextBefore);
                }
                if (contextAfter != null) {
                    cmd.add("-A" + contextAfter);
                }
            }

            if (include != null) {
                cmd.add("--include=" + include);
            }
            cmd.add(pattern);
            cmd.add(path);
        }

        return cmd;
    }

    private static Integer getInt(Map<String, Object> input, String key) {
        Object val = input.get(key);
        if (val instanceof Number n) {
            return n.intValue();
        }
        return null;
    }

    private boolean isCommandAvailable(String command) {
        try {
            boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");
            Process p;
            if (isWindows) {
                p = new ProcessBuilder("where", command).start();
            } else {
                p = new ProcessBuilder("which", command).start();
            }
            return p.waitFor(5, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public String activityDescription(Map<String, Object> input) {
        return "🔍 Searching for '" + input.getOrDefault("pattern", "...") + "'";
    }
}
