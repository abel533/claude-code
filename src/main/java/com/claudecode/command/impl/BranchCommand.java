package com.claudecode.command.impl;

import com.claudecode.command.CommandContext;
import com.claudecode.command.SlashCommand;
import com.claudecode.console.AnsiStyle;
import org.springframework.ai.chat.messages.Message;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * /branch 命令 —— 管理对话分支。
 * <p>
 * 允许用户保存当前对话状态为命名分支，并在不同分支之间切换。
 * 分支数据存储在静态 Map 中，在 JVM 生命周期内持久化。
 * <p>
 * 支持的子命令：
 * <ul>
 *   <li>{@code /branch save <name>} —— 将当前对话保存为分支</li>
 *   <li>{@code /branch load <name>} —— 恢复到指定分支的对话状态</li>
 *   <li>{@code /branch list} —— 列出所有已保存的分支</li>
 *   <li>{@code /branch delete <name>} —— 删除指定分支</li>
 * </ul>
 */
public class BranchCommand implements SlashCommand {

    /** 静态分支存储：分支名称 -> 消息快照 */
    private static final Map<String, BranchSnapshot> branches = new LinkedHashMap<>();

    @Override
    public String name() {
        return "branch";
    }

    @Override
    public String description() {
        return "Manage conversation branches";
    }

    @Override
    public String execute(String args, CommandContext context) {
        if (context.agentLoop() == null) {
            return AnsiStyle.red("  ✗ AgentLoop unavailable.");
        }

        String trimmedArgs = args != null ? args.trim() : "";

        if (trimmedArgs.isEmpty()) {
            return showUsage();
        }

        // 解析子命令和参数
        String[] parts = trimmedArgs.split("\\s+", 2);
        String subCommand = parts[0].toLowerCase();
        String branchName = parts.length > 1 ? parts[1].trim() : "";

        return switch (subCommand) {
            case "save" -> saveBranch(branchName, context);
            case "load" -> loadBranch(branchName, context);
            case "list" -> listBranches(context);
            case "delete" -> deleteBranch(branchName);
            default -> AnsiStyle.red("  ✗ Unknown subcommand: " + subCommand) + "\n" + showUsage();
        };
    }

    /**
     * 保存当前对话为命名分支。
     *
     * @param branchName 分支名称
     * @param context    命令上下文
     * @return 操作结果信息
     */
    private String saveBranch(String branchName, CommandContext context) {
        if (branchName.isEmpty()) {
            return AnsiStyle.red("  ✗ Please specify branch name.") + "\n"
                    + AnsiStyle.dim("  Usage: /branch save <name>");
        }

        List<Message> currentHistory = context.agentLoop().getMessageHistory();

        // 创建消息快照（深拷贝列表引用）
        List<Message> snapshot = new ArrayList<>(currentHistory);
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        branches.put(branchName, new BranchSnapshot(snapshot, timestamp));

        return AnsiStyle.green("  ✓ Branch saved: ") + AnsiStyle.bold(branchName) + "\n"
                + AnsiStyle.dim("    Messages: " + snapshot.size() + "  Time: " + timestamp);
    }

    /**
     * 恢复到指定分支的对话状态。
     *
     * @param branchName 分支名称
     * @param context    命令上下文
     * @return 操作结果信息
     */
    private String loadBranch(String branchName, CommandContext context) {
        if (branchName.isEmpty()) {
            return AnsiStyle.red("  ✗ Please specify branch name.") + "\n"
                    + AnsiStyle.dim("  Usage: /branch load <name>");
        }

        BranchSnapshot snapshot = branches.get(branchName);
        if (snapshot == null) {
            return AnsiStyle.red("  ✗ Branch not found: " + branchName) + "\n"
                    + AnsiStyle.dim("  Use /branch list to see all available branches.");
        }

        // 恢复对话历史
        context.agentLoop().replaceHistory(new ArrayList<>(snapshot.messages()));

        return AnsiStyle.green("  ✓ Restored to branch: ") + AnsiStyle.bold(branchName) + "\n"
                + AnsiStyle.dim("    Loaded " + snapshot.messages().size() + " messages (saved at " + snapshot.timestamp() + ")");
    }

    /**
     * 列出所有已保存的分支。
     *
     * @param context 命令上下文
     * @return 分支列表信息
     */
    private String listBranches(CommandContext context) {
        if (branches.isEmpty()) {
            return AnsiStyle.dim("  No saved branches.") + "\n"
                    + AnsiStyle.dim("  Use /branch save <name> to save current conversation.");
        }

        int currentSize = context.agentLoop().getMessageHistory().size();

        StringBuilder sb = new StringBuilder();
        sb.append(AnsiStyle.bold("\n  🌿 Conversation Branches\n\n"));

        for (Map.Entry<String, BranchSnapshot> entry : branches.entrySet()) {
            String name = entry.getKey();
            BranchSnapshot snapshot = entry.getValue();
            int msgCount = snapshot.messages().size();

            // 标记与当前对话大小相同的分支
            String marker = (msgCount == currentSize) ? AnsiStyle.green(" ◀ current size") : "";

            sb.append("  • ")
                    .append(AnsiStyle.bold(name))
                    .append(AnsiStyle.dim("  (" + msgCount + " messages, " + snapshot.timestamp() + ")"))
                    .append(marker)
                    .append("\n");
        }

        sb.append("\n").append(AnsiStyle.dim("  Total " + branches.size() + " branches.")).append("\n");
        return sb.toString();
    }

    /**
     * 删除指定分支。
     *
     * @param branchName 分支名称
     * @return 操作结果信息
     */
    private String deleteBranch(String branchName) {
        if (branchName.isEmpty()) {
            return AnsiStyle.red("  ✗ Please specify branch name.") + "\n"
                    + AnsiStyle.dim("  Usage: /branch delete <name>");
        }

        BranchSnapshot removed = branches.remove(branchName);
        if (removed == null) {
            return AnsiStyle.red("  ✗ Branch not found: " + branchName);
        }

        return AnsiStyle.green("  ✓ Branch deleted: ") + AnsiStyle.bold(branchName);
    }

    /**
     * 显示用法帮助信息。
     *
     * @return 用法说明文本
     */
    private String showUsage() {
        StringBuilder sb = new StringBuilder();
        sb.append(AnsiStyle.bold("\n  🌿 Branch — Conversation branch management\n\n"));
        sb.append("  ").append(AnsiStyle.cyan("/branch save <name>")).append("    Save current conversation as branch\n");
        sb.append("  ").append(AnsiStyle.cyan("/branch load <name>")).append("    Restore to specified branch\n");
        sb.append("  ").append(AnsiStyle.cyan("/branch list")).append("            List all branches\n");
        sb.append("  ").append(AnsiStyle.cyan("/branch delete <name>")).append("  Delete specified branch\n");
        return sb.toString();
    }

    /**
     * 分支快照记录 —— 保存消息列表和创建时间。
     *
     * @param messages  消息快照
     * @param timestamp 创建时间戳
     */
    private record BranchSnapshot(List<Message> messages, String timestamp) {}
}
