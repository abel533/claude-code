package com.claudecode.tool.impl;

import com.claudecode.tool.Tool;
import com.claudecode.tool.ToolContext;
import com.claudecode.worktree.WorktreeManager;
import com.claudecode.worktree.WorktreeManager.WorktreeCreateResult;
import com.claudecode.worktree.WorktreeManager.WorktreeSession;

import java.nio.file.Path;
import java.util.Map;

/**
 * 进入 Git Worktree 工具 —— 对应 claude-code/src/tools/EnterWorktreeTool。
 * <p>
 * 为当前会话创建隔离的 git worktree，切换工作目录。
 * 用于并行工作场景，每个 worktree 有独立的分支和文件系统视图。
 * <p>
 * 输入参数:
 * <ul>
 *   <li>name — worktree 名称 (slug)，可选，默认自动生成</li>
 *   <li>base_branch — 基准分支，可选</li>
 * </ul>
 */
public class EnterWorktreeTool implements Tool {

    public static final String WORKTREE_MANAGER_KEY = "__worktree_manager__";

    @Override
    public String name() {
        return "EnterWorktree";
    }

    @Override
    public String description() {
        return """
                Create and enter a git worktree for isolated parallel work. \
                This creates a new branch and working directory, allowing you \
                to make changes without affecting the main working tree. \
                Use ExitWorktree to leave and optionally clean up.""";
    }

    @Override
    public String inputSchema() {
        return """
                {
                  "type": "object",
                  "properties": {
                    "name": {
                      "type": "string",
                      "description": "Worktree name (slug). Max 64 chars, [a-zA-Z0-9._-/] only. If not provided, a name will be generated."
                    },
                    "base_branch": {
                      "type": "string",
                      "description": "Base branch to create worktree from. Defaults to current HEAD."
                    }
                  }
                }""";
    }

    @Override
    public String execute(Map<String, Object> input, ToolContext context) {
        try {
            WorktreeManager manager = getOrCreateManager(context);

            // 检查是否已在 worktree 中
            if (manager.getCurrentSession() != null) {
                return "Error: Already in a worktree session: " + manager.getCurrentSession().slug() +
                        ". Use ExitWorktree first.";
            }

            // 检测 Git 根目录
            Path gitRoot = WorktreeManager.findGitRoot(context.getWorkDir());
            if (gitRoot == null) {
                return "Error: Not in a git repository. Worktree requires a git repo.";
            }

            String slug = input != null ? (String) input.get("name") : null;
            if (slug == null || slug.isBlank()) {
                // 自动生成 slug
                slug = "session-" + System.currentTimeMillis() % 100000;
            }

            String baseBranch = input != null ? (String) input.get("base_branch") : null;

            // 创建/恢复 worktree
            WorktreeSession session = manager.enterWorktree(slug, gitRoot, baseBranch);

            // 更新工作目录（ToolContext 层面）
            // 注意：Java 不像 Node.js 那样能轻易切换 process CWD，
            // 我们通过 ToolContext 来管理"逻辑工作目录"
            context.set("__original_work_dir__", context.getWorkDir().toString());

            StringBuilder sb = new StringBuilder();
            sb.append("✓ Entered worktree: ").append(session.slug()).append("\n");
            sb.append("  Path: ").append(session.worktreePath()).append("\n");
            sb.append("  Branch: ").append(session.worktreeBranch()).append("\n");
            if (session.headCommit() != null) {
                sb.append("  HEAD: ").append(session.headCommit(), 0,
                        Math.min(8, session.headCommit().length())).append("\n");
            }
            if (session.worktreePath().equals(session.worktreePath())) {
                sb.append("  Status: ").append(
                        session.createdAt() != null ? "created" : "resumed").append("\n");
            }
            sb.append("\nAll file operations will now work in the worktree directory.");
            sb.append("\nUse ExitWorktree to return to the original directory.");

            return sb.toString();
        } catch (IllegalArgumentException e) {
            return "Error: Invalid worktree name — " + e.getMessage();
        } catch (IllegalStateException e) {
            return "Error: " + e.getMessage();
        } catch (Exception e) {
            return "Error creating worktree: " + e.getMessage();
        }
    }

    private WorktreeManager getOrCreateManager(ToolContext context) {
        WorktreeManager manager = context.get(WORKTREE_MANAGER_KEY);
        if (manager == null) {
            manager = new WorktreeManager();
            context.set(WORKTREE_MANAGER_KEY, manager);
        }
        return manager;
    }
}
