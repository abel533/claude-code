package com.claudecode.worktree;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Git Worktree 生命周期管理 —— 对应 claude-code/src/utils/worktree.ts。
 * <p>
 * 管理 worktree 的创建、恢复、清理和验证。
 * 支持以下工作流:
 * <ul>
 *   <li>EnterWorktreeTool 创建隔离的工作分支</li>
 *   <li>ExitWorktreeTool 退出并保留/删除 worktree</li>
 *   <li>Agent 自动创建独立 worktree（并行隔离）</li>
 *   <li>会话恢复时重新进入 worktree</li>
 * </ul>
 */
public class WorktreeManager {

    private static final Logger log = LoggerFactory.getLogger(WorktreeManager.class);

    /** Slug 验证：每个段只允许字母数字和 ._- */
    private static final Pattern VALID_SLUG_SEGMENT = Pattern.compile("^[a-zA-Z0-9._-]+$");
    private static final int MAX_SLUG_LENGTH = 64;
    private static final String WORKTREE_BRANCH_PREFIX = "worktree-";

    /** 当前活跃的 worktree 会话（每个 JVM 实例最多一个用户级 worktree） */
    private volatile WorktreeSession currentSession;

    // ==================== Worktree 会话数据 ====================

    public record WorktreeSession(
            String slug,
            Path worktreePath,
            String worktreeBranch,
            String headCommit,
            Path originalCwd,
            Path gitRoot,
            Instant createdAt
    ) {}

    public record WorktreeCreateResult(
            Path worktreePath,
            String worktreeBranch,
            String headCommit,
            Path gitRoot,
            boolean existed
    ) {}

    // ==================== Slug 验证 ====================

    /**
     * 验证 worktree slug 格式和安全性。
     * <p>
     * 规则：
     * <ul>
     *   <li>最大 64 字符</li>
     *   <li>每段只允许 [a-zA-Z0-9._-]</li>
     *   <li>不允许 . 或 .. 段（防路径遍历）</li>
     *   <li>不允许前导/尾随 /</li>
     *   <li>/ 在分支名和目录名中映射为 +</li>
     * </ul>
     *
     * @throws IllegalArgumentException 验证失败时
     */
    public static void validateSlug(String slug) {
        if (slug == null || slug.isBlank()) {
            throw new IllegalArgumentException("Worktree slug cannot be empty");
        }
        if (slug.length() > MAX_SLUG_LENGTH) {
            throw new IllegalArgumentException(
                    "Worktree slug too long (max " + MAX_SLUG_LENGTH + "): " + slug.length());
        }
        if (slug.startsWith("/") || slug.endsWith("/")) {
            throw new IllegalArgumentException("Worktree slug cannot start/end with /");
        }

        String[] segments = slug.split("/");
        for (String segment : segments) {
            if (segment.isEmpty()) {
                throw new IllegalArgumentException("Worktree slug has empty segment");
            }
            if (".".equals(segment) || "..".equals(segment)) {
                throw new IllegalArgumentException("Worktree slug cannot contain . or .. segments");
            }
            if (!VALID_SLUG_SEGMENT.matcher(segment).matches()) {
                throw new IllegalArgumentException(
                        "Invalid characters in slug segment '" + segment +
                                "': only [a-zA-Z0-9._-] allowed");
            }
        }
    }

    /**
     * 将 slug 中的 / 映射为 + 防止 D/F 冲突。
     */
    public static String flattenSlug(String slug) {
        return slug.replace('/', '+');
    }

    /**
     * 从 slug 生成分支名。
     */
    public static String branchName(String slug) {
        return WORKTREE_BRANCH_PREFIX + flattenSlug(slug);
    }

    // ==================== Worktree 创建 ====================

    /**
     * 为会话创建或恢复 worktree。
     *
     * @param slug     worktree 名称
     * @param gitRoot  Git 仓库根目录
     * @param baseBranch 基准分支（null 时使用 HEAD）
     * @return 创建结果
     */
    public WorktreeCreateResult createOrResume(String slug, Path gitRoot, String baseBranch) throws IOException {
        validateSlug(slug);

        String flat = flattenSlug(slug);
        String branch = branchName(slug);
        Path worktreeDir = gitRoot.resolve(".claude").resolve("worktrees").resolve(flat);

        // 快速恢复：已存在则直接返回
        if (Files.isDirectory(worktreeDir)) {
            String headCommit = readWorktreeHead(worktreeDir);
            log.info("Resuming existing worktree: {} at {}", slug, worktreeDir);
            // 更新 mtime 防止被 GC
            try {
                Files.setLastModifiedTime(worktreeDir, java.nio.file.attribute.FileTime.from(Instant.now()));
            } catch (IOException ignored) {}
            return new WorktreeCreateResult(worktreeDir, branch, headCommit, gitRoot, true);
        }

        // 确保父目录存在
        Files.createDirectories(worktreeDir.getParent());

        // 获取基准分支
        if (baseBranch == null || baseBranch.isBlank()) {
            baseBranch = execGit(gitRoot, "rev-parse", "--abbrev-ref", "HEAD").trim();
        }

        // git worktree add -B <branch> <path> <baseBranch>
        String result = execGit(gitRoot,
                "worktree", "add", "-B", branch,
                worktreeDir.toString(), baseBranch);
        log.info("Created worktree: {} → {} (branch: {})", slug, worktreeDir, branch);

        String headCommit = readWorktreeHead(worktreeDir);
        return new WorktreeCreateResult(worktreeDir, branch, headCommit, gitRoot, false);
    }

    /**
     * 为 Agent 创建轻量级 worktree（不修改全局状态）。
     */
    public WorktreeCreateResult createAgentWorktree(String slug, Path gitRoot) throws IOException {
        validateSlug(slug);
        return createOrResume(slug, gitRoot, null);
    }

    // ==================== Worktree 退出 ====================

    /**
     * 退出 worktree 但保留在磁盘上。
     */
    public void keepWorktree() {
        if (currentSession == null) return;
        log.info("Keeping worktree: {} at {}", currentSession.slug, currentSession.worktreePath);
        currentSession = null;
    }

    /**
     * 退出 worktree 并清理（删除 worktree + 分支）。
     *
     * @param discardChanges true 时强制删除（即使有未提交的修改）
     */
    public boolean cleanupWorktree(boolean discardChanges) throws IOException {
        if (currentSession == null) return false;

        WorktreeSession session = currentSession;

        // 检查是否有未提交修改
        if (!discardChanges && hasUncommittedChanges(session.worktreePath)) {
            throw new IllegalStateException(
                    "Worktree has uncommitted changes. Use discard_changes=true to force removal.");
        }

        return removeWorktree(session.worktreePath, session.worktreeBranch, session.gitRoot);
    }

    /**
     * 删除指定的 worktree 和分支。
     */
    public boolean removeWorktree(Path worktreePath, String branch, Path gitRoot) {
        try {
            // git worktree remove --force <path>
            execGit(gitRoot, "worktree", "remove", "--force", worktreePath.toString());

            // 等待 git 锁释放
            Thread.sleep(100);

            // git branch -D <branch>
            if (branch != null) {
                try {
                    execGit(gitRoot, "branch", "-D", branch);
                } catch (IOException e) {
                    log.debug("Branch deletion failed (may not exist): {}", e.getMessage());
                }
            }

            currentSession = null;
            log.info("Removed worktree: {} (branch: {})", worktreePath, branch);
            return true;
        } catch (Exception e) {
            log.error("Failed to remove worktree: {}", worktreePath, e);
            return false;
        }
    }

    /**
     * 删除 Agent 的轻量级 worktree。
     */
    public boolean removeAgentWorktree(Path worktreePath, String branch, Path gitRoot) {
        return removeWorktree(worktreePath, branch, gitRoot);
    }

    // ==================== 会话管理 ====================

    /**
     * 进入 worktree（设置为当前会话）。
     */
    public WorktreeSession enterWorktree(String slug, Path gitRoot, String baseBranch) throws IOException {
        if (currentSession != null) {
            throw new IllegalStateException("Already in a worktree session: " + currentSession.slug);
        }

        Path originalCwd = Path.of(System.getProperty("user.dir"));
        WorktreeCreateResult result = createOrResume(slug, gitRoot, baseBranch);

        currentSession = new WorktreeSession(
                slug, result.worktreePath, result.worktreeBranch,
                result.headCommit, originalCwd, gitRoot, Instant.now()
        );

        return currentSession;
    }

    /**
     * 恢复之前的 worktree 会话（--resume 时使用）。
     */
    public void restoreSession(WorktreeSession session) {
        this.currentSession = session;
    }

    public WorktreeSession getCurrentSession() {
        return currentSession;
    }

    // ==================== Symlink 大目录 ====================

    /**
     * 为大型目录创建符号链接（如 node_modules, .next, dist）。
     */
    public void symlinkDirectories(Path repoRoot, Path worktreePath, List<String> dirs) {
        if (dirs == null || dirs.isEmpty()) return;

        for (String dir : dirs) {
            if (dir.contains("..") || dir.startsWith("/")) {
                log.warn("Skipping unsafe symlink directory: {}", dir);
                continue;
            }

            Path source = repoRoot.resolve(dir);
            Path target = worktreePath.resolve(dir);

            try {
                if (!Files.isDirectory(source)) {
                    log.debug("Symlink source doesn't exist yet: {}", source);
                    continue;
                }
                if (Files.exists(target)) {
                    log.debug("Symlink target already exists: {}", target);
                    continue;
                }
                Files.createDirectories(target.getParent());
                Files.createSymbolicLink(target, source);
                log.info("Symlinked: {} → {}", target, source);
            } catch (IOException e) {
                log.warn("Failed to create symlink {} → {}: {}", target, source, e.getMessage());
            }
        }
    }

    // ==================== 过期清理（GC） ====================

    /** Agent worktree 的临时命名模式 */
    private static final Pattern EPHEMERAL_PATTERN = Pattern.compile(
            "^(agent-a[0-9a-f]{7}|wf_[0-9a-f]{8}-[0-9a-f]{3}-\\d+|bridge-.+|job-.+-[0-9a-f]{8})$");

    /**
     * 清理过期的 Agent worktree（GC）。
     *
     * @param cutoff 截止时间，mtime 早于此时间的才会被清理
     * @return 清理的 worktree 数量
     */
    public int cleanupStaleWorktrees(Path gitRoot, Instant cutoff) {
        Path worktreeBase = gitRoot.resolve(".claude").resolve("worktrees");
        if (!Files.isDirectory(worktreeBase)) return 0;

        int cleaned = 0;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(worktreeBase)) {
            for (Path entry : stream) {
                if (!Files.isDirectory(entry)) continue;

                String name = entry.getFileName().toString();

                // 只清理临时命名的 worktree
                if (!EPHEMERAL_PATTERN.matcher(name).matches()) continue;

                // 跳过当前会话的 worktree
                if (currentSession != null && entry.equals(currentSession.worktreePath)) continue;

                // 检查 mtime
                Instant mtime = Files.getLastModifiedTime(entry).toInstant();
                if (mtime.isAfter(cutoff)) continue;

                // 检查是否有未提交的修改
                if (hasUncommittedChanges(entry)) {
                    log.debug("Skipping stale worktree with changes: {}", name);
                    continue;
                }

                // 安全删除
                String branch = WORKTREE_BRANCH_PREFIX + name;
                if (removeWorktree(entry, branch, gitRoot)) {
                    cleaned++;
                }
            }
        } catch (IOException e) {
            log.error("Failed to scan worktree directory: {}", worktreeBase, e);
        }

        if (cleaned > 0) {
            log.info("Cleaned up {} stale agent worktrees", cleaned);
        }
        return cleaned;
    }

    // ==================== Git 工具方法 ====================

    /**
     * 读取 worktree 的 HEAD commit SHA。
     */
    private String readWorktreeHead(Path worktreePath) {
        try {
            // 尝试直接读取 .git 文件获取 HEAD
            Path gitFile = worktreePath.resolve(".git");
            if (Files.isRegularFile(gitFile)) {
                String content = Files.readString(gitFile).trim();
                if (content.startsWith("gitdir:")) {
                    Path gitDir = Path.of(content.substring("gitdir:".length()).trim());
                    if (!gitDir.isAbsolute()) {
                        gitDir = worktreePath.resolve(gitDir);
                    }
                    Path headFile = gitDir.resolve("HEAD");
                    if (Files.isRegularFile(headFile)) {
                        String headContent = Files.readString(headFile).trim();
                        if (headContent.startsWith("ref:")) {
                            // 解析 symbolic ref
                            String ref = headContent.substring(4).trim();
                            Path refFile = gitDir.resolve(ref);
                            if (Files.isRegularFile(refFile)) {
                                return Files.readString(refFile).trim();
                            }
                        } else {
                            return headContent; // 直接是 SHA
                        }
                    }
                }
            }
            // 回退到 git rev-parse
            return execGit(worktreePath, "rev-parse", "HEAD").trim();
        } catch (Exception e) {
            log.debug("Failed to read HEAD for worktree {}: {}", worktreePath, e.getMessage());
            return null;
        }
    }

    /**
     * 检查 worktree 是否有未提交的修改。
     */
    private boolean hasUncommittedChanges(Path worktreePath) {
        try {
            String status = execGit(worktreePath, "status", "--porcelain");
            return !status.isBlank();
        } catch (IOException e) {
            // Git 命令失败 → fail-closed（假设有修改）
            return true;
        }
    }

    /**
     * 检测 git 仓库根目录。
     */
    public static Path findGitRoot(Path dir) {
        try {
            String result = execGit(dir, "rev-parse", "--show-toplevel");
            return Path.of(result.trim());
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * 执行 git 命令并返回 stdout。
     */
    private static String execGit(Path workDir, String... args) throws IOException {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.addAll(Arrays.asList(args));

        ProcessBuilder pb = new ProcessBuilder(command)
                .directory(workDir.toFile())
                .redirectErrorStream(false);
        pb.environment().put("GIT_TERMINAL_PROMPT", "0");

        Process process;
        try {
            process = pb.start();
        } catch (IOException e) {
            throw new IOException("Failed to start git: " + e.getMessage(), e);
        }

        String stdout, stderr;
        try {
            stdout = new String(process.getInputStream().readAllBytes()).trim();
            stderr = new String(process.getErrorStream().readAllBytes()).trim();

            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IOException("Git command timed out: " + String.join(" ", args));
            }
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IOException("Git command interrupted", e);
        }

        if (process.exitValue() != 0) {
            throw new IOException("Git command failed (exit " + process.exitValue() + "): " +
                    String.join(" ", args) + "\n" + stderr);
        }

        return stdout;
    }
}
