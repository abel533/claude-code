package com.claudecode.context;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * Git 上下文收集器 —— 收集当前项目的 Git 信息。
 * <p>
 * 提取分支名、状态、最近提交等信息，注入系统提示词中，
 * 帮助 AI 理解当前代码版本和变更状态。
 */
public class GitContext {

    private static final Logger log = LoggerFactory.getLogger(GitContext.class);
    private static final int CMD_TIMEOUT = 5; // 秒

    private final Path projectDir;
    private String branch;
    private String status;
    private String recentCommits;
    private boolean isGitRepo;

    public GitContext(Path projectDir) {
        this.projectDir = projectDir;
        this.isGitRepo = Files.isDirectory(projectDir.resolve(".git"));
    }

    /**
     * 收集 Git 上下文信息
     */
    public GitContext collect() {
        if (!isGitRepo) {
            log.debug("Current directory is not a Git repository: {}", projectDir);
            return this;
        }

        this.branch = runGitCommand("rev-parse", "--abbrev-ref", "HEAD");
        this.status = runGitCommand("status", "--short", "--branch");
        this.recentCommits = runGitCommand("log", "--oneline", "-5", "--no-decorate");

        return this;
    }

    /**
     * 构建 Git 上下文摘要（注入系统提示词）
     */
    public String buildSummary() {
        if (!isGitRepo) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("# Git Context\n\n");

        if (branch != null && !branch.isBlank()) {
            sb.append("- Branch: ").append(branch).append("\n");
        }

        if (status != null && !status.isBlank()) {
            // 提取修改文件数
            long modifiedCount = status.lines()
                    .filter(l -> !l.startsWith("##"))
                    .count();
            if (modifiedCount > 0) {
                sb.append("- Modified files: ").append(modifiedCount).append("\n");
                sb.append("- Status:\n```\n").append(status).append("\n```\n");
            } else {
                sb.append("- Working tree: clean\n");
            }
        }

        if (recentCommits != null && !recentCommits.isBlank()) {
            sb.append("- Recent commits:\n```\n").append(recentCommits).append("\n```\n");
        }

        return sb.toString();
    }

    /**
     * 执行 Git 命令
     */
    private String runGitCommand(String... args) {
        try {
            var command = new java.util.ArrayList<String>();
            command.add("git");
            command.addAll(java.util.List.of(args));

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.directory(projectDir.toFile());
            pb.redirectErrorStream(true);

            Process process = pb.start();
            StringBuilder output = new StringBuilder();

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            }

            boolean finished = process.waitFor(CMD_TIMEOUT, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return "";
            }

            return output.toString().stripTrailing();

        } catch (Exception e) {
            log.debug("Git command execution failed: {}", e.getMessage());
            return "";
        }
    }

    // Getters
    public boolean isGitRepo() { return isGitRepo; }
    public String getBranch() { return branch; }
    public String getStatus() { return status; }
    public String getRecentCommits() { return recentCommits; }
}
