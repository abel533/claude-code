package com.claudecode.tool.impl;

import com.claudecode.tool.Tool;
import com.claudecode.tool.ToolContext;

import java.io.BufferedReader;
import java.io.IOException;
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
 */
public class GrepTool implements Tool {

    private static final int MAX_RESULTS = 100;

    @Override
    public String name() {
        return "Grep";
    }

    @Override
    public String description() {
        return """
            Search for a pattern in file contents using regex. Returns matching lines with \
            file paths and line numbers. Uses ripgrep (rg) if available, falls back to grep.""";
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
        Path baseDir = context.getWorkDir().resolve(searchPath).normalize();

        try {
            List<String> cmd = buildCommand(pattern, baseDir.toString(), include);
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(context.getWorkDir().toFile());
            pb.redirectErrorStream(true);

            Process process = pb.start();
            List<String> lines = new ArrayList<>();

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null && lines.size() < MAX_RESULTS) {
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
            if (lines.size() >= MAX_RESULTS) {
                sb.append("... (results truncated at ").append(MAX_RESULTS).append(")\n");
            }
            return sb.toString().stripTrailing();

        } catch (Exception e) {
            return "Error searching: " + e.getMessage();
        }
    }

    /** 构建搜索命令（优先 rg，降级 grep/findstr） */
    private List<String> buildCommand(String pattern, String path, String include) {
        boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");
        List<String> cmd = new ArrayList<>();

        // 尝试 ripgrep
        if (isCommandAvailable("rg")) {
            cmd.add("rg");
            cmd.add("--no-heading");
            cmd.add("--line-number");
            cmd.add("--color=never");
            cmd.add("--max-count=100");
            if (include != null) {
                cmd.add("--glob=" + include);
            }
            cmd.add(pattern);
            cmd.add(path);
        } else if (isWindows) {
            // Windows 降级到 findstr（功能有限）
            cmd.add("findstr");
            cmd.add("/s");
            cmd.add("/n");
            cmd.add("/r");
            cmd.add(pattern);
            if (include != null) {
                cmd.add(path + "\\" + include);
            } else {
                cmd.add(path + "\\*");
            }
        } else {
            cmd.add("grep");
            cmd.add("-rn");
            cmd.add("--color=never");
            if (include != null) {
                cmd.add("--include=" + include);
            }
            cmd.add(pattern);
            cmd.add(path);
        }

        return cmd;
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
