package com.claudecode.tool.impl;

import com.claudecode.tool.Tool;
import com.claudecode.tool.ToolContext;
import com.claudecode.worktree.WorktreeManager;
import com.claudecode.worktree.WorktreeManager.WorktreeSession;

import java.util.Map;

/**
 * 退出 Git Worktree 工具 —— 对应 claude-code/src/tools/ExitWorktreeTool。
 * <p>
 * 退出当前 worktree，可选择保留或删除。
 * <ul>
 *   <li>keep — 保留 worktree 在磁盘上（可后续恢复）</li>
 *   <li>remove — 删除 worktree 和对应分支</li>
 * </ul>
 */
public class ExitWorktreeTool implements Tool {

    @Override
    public String name() {
        return "ExitWorktree";
    }

    @Override
    public String description() {
        return """
                Exit the current git worktree session. Choose to 'keep' the \
                worktree on disk for later resumption, or 'remove' it to clean \
                up the worktree directory and branch. If removing with uncommitted \
                changes, set discard_changes to true.""";
    }

    @Override
    public String inputSchema() {
        return """
                {
                  "type": "object",
                  "properties": {
                    "action": {
                      "type": "string",
                      "enum": ["keep", "remove"],
                      "description": "'keep' preserves worktree on disk, 'remove' deletes it and the branch"
                    },
                    "discard_changes": {
                      "type": "boolean",
                      "description": "When true, force removal even with uncommitted changes"
                    }
                  },
                  "required": ["action"]
                }""";
    }

    @Override
    public String execute(Map<String, Object> input, ToolContext context) {
        try {
            WorktreeManager manager = context.get(EnterWorktreeTool.WORKTREE_MANAGER_KEY);
            if (manager == null || manager.getCurrentSession() == null) {
                return "Error: Not currently in a worktree session. Use EnterWorktree first.";
            }

            WorktreeSession session = manager.getCurrentSession();
            String action = input != null ? (String) input.get("action") : "keep";
            boolean discardChanges = input != null &&
                    Boolean.TRUE.equals(input.get("discard_changes"));

            StringBuilder sb = new StringBuilder();

            if ("remove".equals(action)) {
                boolean removed = manager.cleanupWorktree(discardChanges);
                if (removed) {
                    sb.append("✓ Worktree removed: ").append(session.slug()).append("\n");
                    sb.append("  Deleted: ").append(session.worktreePath()).append("\n");
                    sb.append("  Branch deleted: ").append(session.worktreeBranch()).append("\n");
                } else {
                    return "Error: Failed to remove worktree. " +
                            "Try with discard_changes=true to force removal.";
                }
            } else {
                manager.keepWorktree();
                sb.append("✓ Exited worktree: ").append(session.slug()).append("\n");
                sb.append("  Preserved at: ").append(session.worktreePath()).append("\n");
                sb.append("  Branch: ").append(session.worktreeBranch()).append("\n");
                sb.append("  You can re-enter later with EnterWorktree name=")
                        .append(session.slug()).append("\n");
            }

            // 恢复原始工作目录
            String originalDir = context.get("__original_work_dir__");
            if (originalDir != null) {
                sb.append("\nRestored to: ").append(originalDir);
            }

            return sb.toString();
        } catch (IllegalStateException e) {
            return "Error: " + e.getMessage() +
                    "\nSet discard_changes=true to force removal.";
        } catch (Exception e) {
            return "Error exiting worktree: " + e.getMessage();
        }
    }
}
