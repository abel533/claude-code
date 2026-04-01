package com.claudecode.command.impl;

import com.claudecode.command.CommandContext;
import com.claudecode.command.SlashCommand;
import com.claudecode.console.AnsiStyle;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * /diff 命令 —— 显示 Git 未提交的变更。
 * <p>
 * 对应 claude-code 的 /diff 命令，展示工作区的变更内容：
 * <ul>
 *   <li>无参数：显示所有未暂存变更</li>
 *   <li>--staged：显示已暂存变更</li>
 *   <li>--stat：仅显示文件统计（不含详细diff）</li>
 * </ul>
 */
public class DiffCommand implements SlashCommand {

    @Override
    public String name() {
        return "diff";
    }

    @Override
    public String description() {
        return "Show uncommitted git changes";
    }

    @Override
    public String execute(String args, CommandContext context) {
        Path projectDir = Path.of(System.getProperty("user.dir"));
        if (!Files.isDirectory(projectDir.resolve(".git"))) {
            return AnsiStyle.yellow("  ⚠ Current directory is not a Git repository");
        }

        args = args == null ? "" : args.strip();

        try {
            String diffOutput;
            String header;

            if (args.contains("--staged")) {
                diffOutput = runGit(projectDir, "diff", "--staged", "--color=always");
                header = "Staged Changes";
            } else if (args.contains("--stat")) {
                diffOutput = runGit(projectDir, "diff", "--stat", "--color=always");
                header = "Changes (stat)";
            } else {
                // 默认：显示所有变更（未暂存 + 已暂存的统计 + 未跟踪文件）
                String unstaged = runGit(projectDir, "diff", "--color=always");
                String staged = runGit(projectDir, "diff", "--staged", "--stat");
                String untracked = runGit(projectDir, "ls-files", "--others", "--exclude-standard");

                StringBuilder sb = new StringBuilder();
                sb.append("\n").append(AnsiStyle.bold("  📋 Git Diff\n"));
                sb.append("  ").append("─".repeat(50)).append("\n");

                if (!staged.isBlank()) {
                    sb.append("\n").append(AnsiStyle.green("  ▸ Staged:\n"));
                    staged.lines().forEach(l -> sb.append("    ").append(l).append("\n"));
                }

                if (!unstaged.isBlank()) {
                    sb.append("\n").append(AnsiStyle.yellow("  ▸ Unstaged changes:\n"));
                    // 限制行数避免输出过长
                    long lineCount = unstaged.lines().count();
                    if (lineCount > 100) {
                        unstaged.lines().limit(100).forEach(l -> sb.append("    ").append(l).append("\n"));
                        sb.append(AnsiStyle.dim("    ... (" + lineCount + " lines total, showing first 100)\n"));
                    } else {
                        unstaged.lines().forEach(l -> sb.append("    ").append(l).append("\n"));
                    }
                }

                if (!untracked.isBlank()) {
                    sb.append("\n").append(AnsiStyle.red("  ▸ Untracked files:\n"));
                    untracked.lines().forEach(l -> sb.append("    ").append(l).append("\n"));
                }

                if (staged.isBlank() && unstaged.isBlank() && untracked.isBlank()) {
                    sb.append("\n").append(AnsiStyle.green("  ✓ Working directory clean, no changes\n"));
                }

                return sb.toString();
            }

            // --staged 或 --stat 模式
            StringBuilder sb = new StringBuilder();
            sb.append("\n").append(AnsiStyle.bold("  📋 " + header + "\n"));
            sb.append("  ").append("─".repeat(50)).append("\n\n");

            if (diffOutput.isBlank()) {
                sb.append(AnsiStyle.green("  ✓ No changes\n"));
            } else {
                long lineCount = diffOutput.lines().count();
                if (lineCount > 100) {
                    diffOutput.lines().limit(100).forEach(l -> sb.append("  ").append(l).append("\n"));
                    sb.append(AnsiStyle.dim("  ... (" + lineCount + " lines)\n"));
                } else {
                    diffOutput.lines().forEach(l -> sb.append("  ").append(l).append("\n"));
                }
            }

            return sb.toString();

        } catch (Exception e) {
            return AnsiStyle.red("  ✗ Git diff failed: " + e.getMessage());
        }
    }

    private String runGit(Path dir, String... args) throws Exception {
        var command = new java.util.ArrayList<String>();
        command.add("git");
        command.add("--no-pager");
        command.addAll(java.util.List.of(args));

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(dir.toFile());
        pb.redirectErrorStream(true);

        Process process = pb.start();
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }
        }

        process.waitFor(10, TimeUnit.SECONDS);
        return output.toString().stripTrailing();
    }
}
