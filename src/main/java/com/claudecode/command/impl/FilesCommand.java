package com.claudecode.command.impl;

import com.claudecode.command.CommandContext;
import com.claudecode.command.SlashCommand;
import com.claudecode.console.AnsiStyle;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

/**
 * /files 命令 —— 列出当前工作目录的文件结构。
 * <p>
 * 对应 claude-code/src/commands/files.ts。
 * 显示项目目录树（默认2层深度）。
 */
public class FilesCommand implements SlashCommand {

    @Override
    public String name() {
        return "files";
    }

    @Override
    public String description() {
        return "List project files. Use /files [depth] to control depth (default: 2)";
    }

    @Override
    public String execute(String args, CommandContext context) {
        Path projectDir = Path.of(System.getProperty("user.dir"));
        int maxDepth = 2;

        if (args != null && !args.isBlank()) {
            try {
                maxDepth = Integer.parseInt(args.strip());
                maxDepth = Math.max(1, Math.min(maxDepth, 5));
            } catch (NumberFormatException ignored) {
                // Treat as path filter
                return listFiltered(projectDir, args.strip());
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append("\n  ").append(AnsiStyle.bold("📁 " + projectDir.getFileName())).append("\n");
        sb.append("  ").append("─".repeat(50)).append("\n");

        try {
            int[] counts = {0, 0}; // [files, dirs]
            listDirectory(sb, projectDir, "", maxDepth, 0, counts);
            sb.append("  ").append("─".repeat(50)).append("\n");
            sb.append("  ").append(AnsiStyle.dim(counts[1] + " directories, " + counts[0] + " files")).append("\n");
        } catch (IOException e) {
            sb.append("  ").append(AnsiStyle.red("Error: " + e.getMessage())).append("\n");
        }

        return sb.toString();
    }

    private void listDirectory(StringBuilder sb, Path dir, String indent, int maxDepth, int depth, int[] counts) throws IOException {
        if (depth >= maxDepth) return;

        try (Stream<Path> stream = Files.list(dir).sorted()) {
            var entries = stream
                    .filter(p -> !isHidden(p))
                    .toList();

            for (int i = 0; i < entries.size(); i++) {
                Path entry = entries.get(i);
                boolean isLast = (i == entries.size() - 1);
                String connector = isLast ? "└── " : "├── ";
                String childIndent = indent + (isLast ? "    " : "│   ");
                String name = entry.getFileName().toString();

                if (Files.isDirectory(entry)) {
                    counts[1]++;
                    sb.append("  ").append(indent).append(connector)
                            .append(AnsiStyle.CYAN).append(name).append("/").append(AnsiStyle.RESET).append("\n");
                    listDirectory(sb, entry, childIndent, maxDepth, depth + 1, counts);
                } else {
                    counts[0]++;
                    String sizeStr = formatSize(Files.size(entry));
                    sb.append("  ").append(indent).append(connector).append(name)
                            .append(AnsiStyle.dim(" (" + sizeStr + ")")).append("\n");
                }
            }
        }
    }

    private String listFiltered(Path dir, String pattern) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n  ").append(AnsiStyle.bold("Files matching \"" + pattern + "\":")).append("\n\n");

        try (Stream<Path> walk = Files.walk(dir, 5)) {
            var matches = walk
                    .filter(Files::isRegularFile)
                    .filter(p -> !isHiddenPath(p, dir))
                    .filter(p -> p.getFileName().toString().contains(pattern))
                    .toList();

            if (matches.isEmpty()) {
                sb.append("  ").append(AnsiStyle.dim("No files found matching \"" + pattern + "\"")).append("\n");
            } else {
                for (Path match : matches) {
                    sb.append("  ").append(dir.relativize(match)).append("\n");
                }
                sb.append("\n  ").append(AnsiStyle.dim(matches.size() + " file(s) found")).append("\n");
            }
        } catch (IOException e) {
            sb.append("  ").append(AnsiStyle.red("Error: " + e.getMessage())).append("\n");
        }

        return sb.toString();
    }

    private boolean isHidden(Path p) {
        String name = p.getFileName().toString();
        return name.startsWith(".") || name.equals("node_modules") || name.equals("target")
                || name.equals("build") || name.equals("__pycache__") || name.equals(".git");
    }

    private boolean isHiddenPath(Path p, Path root) {
        Path rel = root.relativize(p);
        for (Path part : rel) {
            if (isHidden(part)) return true;
        }
        return false;
    }

    private String formatSize(long bytes) {
        if (bytes < 1024) return bytes + "B";
        if (bytes < 1024 * 1024) return String.format("%.1fK", bytes / 1024.0);
        return String.format("%.1fM", bytes / (1024.0 * 1024));
    }
}
