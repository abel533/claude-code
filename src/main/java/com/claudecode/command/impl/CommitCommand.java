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
 * /commit 命令 —— 创建 Git commit。
 * <p>
 * 支持多种模式：
 * <ul>
 *   <li>/commit —— 自动生成 AI commit message（基于 git diff）</li>
 *   <li>/commit [message] —— 使用指定的 commit message</li>
 *   <li>/commit --all —— 添加所有文件并提交</li>
 * </ul>
 */
public class CommitCommand implements SlashCommand {

    @Override
    public String name() {
        return "commit";
    }

    @Override
    public String description() {
        return "Create a git commit (with optional AI-generated message)";
    }

    @Override
    public String execute(String args, CommandContext context) {
        Path projectDir = Path.of(System.getProperty("user.dir"));
        if (!Files.isDirectory(projectDir.resolve(".git"))) {
            return AnsiStyle.yellow("  ⚠ Current directory is not a Git repository");
        }

        args = args == null ? "" : args.strip();

        try {
            boolean addAll = args.contains("--all") || args.contains("-a");
            String message = args.replaceAll("--all|-a", "").strip();

            // --all 模式：先执行 git add -A
            if (addAll) {
                String addResult = runGit(projectDir, "add", "-A");
                if (addResult == null) {
                    return AnsiStyle.red("  ✗ git add failed");
                }
            }

            // 检查是否有已暂存的变更
            String staged = runGit(projectDir, "diff", "--cached", "--stat");
            if (staged == null || staged.isBlank()) {
                String status = runGit(projectDir, "status", "--short");
                if (status != null && !status.isBlank()) {
                    return AnsiStyle.yellow("  ⚠ No staged changes\n")
                            + AnsiStyle.dim("  Use /commit --all to add all files\n")
                            + AnsiStyle.dim("  Or run git add manually first");
                }
                return AnsiStyle.green("  ✓ Working directory clean, nothing to commit");
            }

            // 如果没有指定 message，使用 AI 生成
            if (message.isEmpty()) {
                message = generateCommitMessage(projectDir, context);
                if (message == null || message.isBlank()) {
                    return AnsiStyle.red("  ✗ Failed to generate commit message");
                }
            }

            // 执行 git commit
            String commitResult = runGit(projectDir, "commit", "-m", message);
            if (commitResult == null) {
                return AnsiStyle.red("  ✗ git commit failed");
            }

            StringBuilder sb = new StringBuilder();
            sb.append("\n").append(AnsiStyle.green("  ✓ Commit successful\n"));
            sb.append("  ").append("─".repeat(50)).append("\n");
            sb.append("  ").append(AnsiStyle.bold("Message: ")).append(message).append("\n");

            // 显示提交摘要
            commitResult.lines().forEach(line -> sb.append("  ").append(AnsiStyle.dim(line)).append("\n"));

            return sb.toString();

        } catch (Exception e) {
            return AnsiStyle.red("  ✗ Commit failed: " + e.getMessage());
        }
    }

    /** 使用 AI 分析 git diff 生成 commit message */
    private String generateCommitMessage(Path projectDir, CommandContext context) {
        try {
            // 获取暂存区的 diff
            String diff = runGit(projectDir, "diff", "--cached");
            if (diff == null || diff.isBlank()) return null;

            // 截断过长的 diff
            if (diff.length() > 4000) {
                diff = diff.substring(0, 4000) + "\n... (diff truncated)";
            }

            // 使用 ChatModel 生成 commit message
            String prompt = """
                    Analyze the following git diff and generate a concise commit message.
                    Requirements:
                    1. Use conventional commits format (feat/fix/docs/refactor/chore prefix)
                    2. First line should not exceed 72 characters
                    3. For multiple changes, add details after a blank line
                    4. Return only the commit message text, no additional explanation
                    
                    Git diff:
                    ```
                    %s
                    ```
                    """.formatted(diff);

            var chatModel = context.agentLoop().getChatModel();
            var response = chatModel.call(
                    new org.springframework.ai.chat.prompt.Prompt(prompt));

            String generated = response.getResult().getOutput().getText();
            if (generated != null) {
                // 清理：去除可能的引号和多余空行
                generated = generated.strip()
                        .replaceAll("^[\"'`]+|[\"'`]+$", "")
                        .strip();
            }
            return generated;

        } catch (Exception e) {
            // AI 生成失败时返回默认消息
            return null;
        }
    }

    private String runGit(Path dir, String... args) {
        try {
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

            boolean finished = process.waitFor(30, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return null;
            }

            return output.toString().stripTrailing();
        } catch (Exception e) {
            return null;
        }
    }
}
