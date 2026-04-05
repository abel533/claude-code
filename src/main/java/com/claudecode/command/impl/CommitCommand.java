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
 *   <li>/commit --push —— 提交后自动 push 到远程</li>
 *   <li>/commit --pr —— 提交 + push + 创建 PR（使用 gh CLI）</li>
 * </ul>
 */
public class CommitCommand implements SlashCommand {

    @Override
    public String name() {
        return "commit";
    }

    @Override
    public String description() {
        return "Create a git commit. Options: --all, --push, --pr";
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
            boolean push = args.contains("--push") || args.contains("-p");
            boolean pr = args.contains("--pr");
            String message = args.replaceAll("--(all|push|pr)|-[ap]", "").strip();

            // --all: add all files
            if (addAll) {
                String addResult = runGit(projectDir, "add", "-A");
                if (addResult == null) {
                    return AnsiStyle.red("  ✗ git add failed");
                }
            }

            // Check staged changes
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

            // Generate message if not provided
            if (message.isEmpty()) {
                message = generateCommitMessage(projectDir, context);
                if (message == null || message.isBlank()) {
                    return AnsiStyle.red("  ✗ Failed to generate commit message");
                }
            }

            // Execute commit
            String commitResult = runGit(projectDir, "commit", "-m", message);
            if (commitResult == null) {
                return AnsiStyle.red("  ✗ git commit failed");
            }

            StringBuilder sb = new StringBuilder();
            sb.append("\n").append(AnsiStyle.green("  ✓ Commit successful\n"));
            sb.append("  ").append("─".repeat(50)).append("\n");
            sb.append("  ").append(AnsiStyle.bold("Message: ")).append(message).append("\n");
            commitResult.lines().forEach(line -> sb.append("  ").append(AnsiStyle.dim(line)).append("\n"));

            // --push: push to remote
            if (push || pr) {
                String branch = runGit(projectDir, "rev-parse", "--abbrev-ref", "HEAD");
                if (branch == null || branch.isBlank()) branch = "HEAD";

                String pushResult = runGit(projectDir, "push", "origin", branch);
                if (pushResult != null) {
                    sb.append("\n").append(AnsiStyle.green("  ✓ Pushed to origin/" + branch)).append("\n");
                } else {
                    // Try push with --set-upstream for new branches
                    pushResult = runGit(projectDir, "push", "--set-upstream", "origin", branch);
                    if (pushResult != null) {
                        sb.append("\n").append(AnsiStyle.green("  ✓ Pushed to origin/" + branch + " (new branch)")).append("\n");
                    } else {
                        sb.append("\n").append(AnsiStyle.red("  ✗ Push failed")).append("\n");
                        push = false;
                        pr = false;
                    }
                }
            }

            // --pr: create PR using gh CLI
            if (pr) {
                String prResult = createPullRequest(projectDir, message);
                sb.append(prResult);
            }

            return sb.toString();

        } catch (Exception e) {
            return AnsiStyle.red("  ✗ Commit failed: " + e.getMessage());
        }
    }

    private String createPullRequest(Path projectDir, String commitMessage) {
        // Check if gh CLI is available
        try {
            String firstLine = commitMessage.lines().findFirst().orElse(commitMessage);
            String body = commitMessage.lines().skip(1)
                    .reduce("", (a, b) -> a + "\n" + b).strip();

            String ghResult = runCommand(projectDir, "gh", "pr", "create",
                    "--title", firstLine,
                    "--body", body.isEmpty() ? "Auto-generated PR" : body);

            if (ghResult != null && ghResult.contains("http")) {
                return "\n" + AnsiStyle.green("  ✓ PR created: ") + AnsiStyle.CYAN + ghResult.strip() + AnsiStyle.RESET + "\n";
            } else if (ghResult != null) {
                return "\n" + AnsiStyle.green("  ✓ PR: ") + ghResult.strip() + "\n";
            }
        } catch (Exception ignored) {}

        return "\n" + AnsiStyle.yellow("  ⚠ PR creation failed — is 'gh' CLI installed?") + "\n"
                + AnsiStyle.dim("  Install: https://cli.github.com/\n");
    }

    /** Use AI to generate commit message from git diff */
    private String generateCommitMessage(Path projectDir, CommandContext context) {
        try {
            String diff = runGit(projectDir, "diff", "--cached");
            if (diff == null || diff.isBlank()) return null;

            if (diff.length() > 4000) {
                diff = diff.substring(0, 4000) + "\n... (diff truncated)";
            }

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
                generated = generated.strip()
                        .replaceAll("^[\"'`]+|[\"'`]+$", "")
                        .strip();
            }
            return generated;

        } catch (Exception e) {
            return null;
        }
    }

    private String runGit(Path dir, String... args) {
        var command = new java.util.ArrayList<String>();
        command.add("git");
        command.add("--no-pager");
        command.addAll(java.util.List.of(args));
        return runCommand(dir, command.toArray(new String[0]));
    }

    private String runCommand(Path dir, String... command) {
        try {
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

            return process.exitValue() == 0 ? output.toString().stripTrailing() : null;
        } catch (Exception e) {
            return null;
        }
    }
}
