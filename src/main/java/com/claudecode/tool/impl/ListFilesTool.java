package com.claudecode.tool.impl;

import com.claudecode.tool.Tool;
import com.claudecode.tool.ToolContext;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.stream.Stream;

/**
 * 目录列表工具 —— 对应 claude-code/src/tools/ls/LsTool.ts。
 * <p>
 * 列出指定目录的文件和子目录，支持递归深度控制。
 * 类似 Unix 的 ls / Windows 的 dir。
 */
public class ListFilesTool implements Tool {

    private static final int DEFAULT_DEPTH = 1;
    private static final int MAX_DEPTH = 5;
    private static final int MAX_ENTRIES = 500;

    @Override
    public String name() {
        return "ListFiles";
    }

    @Override
    public String description() {
        return """
            List files and directories in a given path. Returns a structured listing \
            with file types and sizes. Useful for exploring project structure. \
            By default lists the immediate contents (depth=1).""";
    }

    @Override
    public String inputSchema() {
        return """
            {
              "type": "object",
              "properties": {
                "path": {
                  "type": "string",
                  "description": "Directory path to list (default: working directory)"
                },
                "depth": {
                  "type": "integer",
                  "description": "Recursion depth (default: 1, max: 5)"
                },
                "includeHidden": {
                  "type": "boolean",
                  "description": "Whether to include hidden files/dirs (default: false)"
                }
              }
            }""";
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public String execute(Map<String, Object> input, ToolContext context) {
        String pathStr = (String) input.getOrDefault("path", ".");
        int depth = Math.min(
                input.containsKey("depth") ? ((Number) input.get("depth")).intValue() : DEFAULT_DEPTH,
                MAX_DEPTH);
        boolean includeHidden = Boolean.TRUE.equals(input.get("includeHidden"));

        Path baseDir = context.getWorkDir().resolve(pathStr).normalize();

        if (!Files.isDirectory(baseDir)) {
            return "Error: Not a directory: " + baseDir;
        }

        try {
            List<String> entries = new ArrayList<>();
            listRecursive(baseDir, baseDir, depth, includeHidden, entries);

            if (entries.isEmpty()) {
                return "Directory is empty: " + pathStr;
            }

            StringBuilder sb = new StringBuilder();
            sb.append("Directory: ").append(baseDir).append("\n");
            sb.append("Entries: ").append(entries.size());
            if (entries.size() >= MAX_ENTRIES) {
                sb.append(" (truncated)");
            }
            sb.append("\n\n");

            for (String entry : entries) {
                sb.append(entry).append("\n");
            }

            return sb.toString().stripTrailing();

        } catch (IOException e) {
            return "Error listing directory: " + e.getMessage();
        }
    }

    /**
     * 递归列出目录内容，带缩进和类型标记
     */
    private void listRecursive(Path baseDir, Path currentDir, int remainingDepth,
                               boolean includeHidden, List<String> entries) throws IOException {
        if (remainingDepth < 0 || entries.size() >= MAX_ENTRIES) {
            return;
        }

        try (Stream<Path> stream = Files.list(currentDir).sorted()) {
            List<Path> paths = stream.toList();
            for (Path path : paths) {
                if (entries.size() >= MAX_ENTRIES) break;

                String fileName = path.getFileName().toString();

                // 跳过隐藏文件
                if (!includeHidden && fileName.startsWith(".")) {
                    continue;
                }

                // 跳过常见忽略目录
                if (Files.isDirectory(path) && isIgnoredDir(fileName)) {
                    continue;
                }

                Path relative = baseDir.relativize(path);
                String relStr = relative.toString().replace('\\', '/');
                boolean isDir = Files.isDirectory(path);

                if (isDir) {
                    entries.add("📁 " + relStr + "/");
                    // 递归进入子目录
                    if (remainingDepth > 1) {
                        listRecursive(baseDir, path, remainingDepth - 1, includeHidden, entries);
                    }
                } else {
                    long size = Files.size(path);
                    entries.add("📄 " + relStr + "  (" + formatSize(size) + ")");
                }
            }
        }
    }

    /** 是否为应该忽略的目录 */
    private boolean isIgnoredDir(String name) {
        return name.equals("node_modules") || name.equals("target")
                || name.equals("build") || name.equals(".git")
                || name.equals("__pycache__") || name.equals(".idea")
                || name.equals(".vscode");
    }

    /** 友好的文件大小格式化 */
    private String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024));
    }

    @Override
    public String activityDescription(Map<String, Object> input) {
        return "📁 Listing " + input.getOrDefault("path", ".");
    }
}
