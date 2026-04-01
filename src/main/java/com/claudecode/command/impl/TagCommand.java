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
 * /tag 命令 —— 为当前对话位置打标签。
 * <p>
 * 标签记录当前消息历史的大小，可用于后续快速回溯到该位置。
 * 标签数据存储在静态 Map 中，在 JVM 生命周期内持久化。
 * <p>
 * 支持的子命令：
 * <ul>
 *   <li>{@code /tag <name>} —— 为当前位置打标签</li>
 *   <li>{@code /tag list} —— 列出所有标签</li>
 *   <li>{@code /tag goto <name>} —— 回溯到指定标签位置</li>
 * </ul>
 */
public class TagCommand implements SlashCommand {

    /** 静态标签存储：标签名称 -> 标签信息 */
    private static final Map<String, TagInfo> tags = new LinkedHashMap<>();

    @Override
    public String name() {
        return "tag";
    }

    @Override
    public String description() {
        return "Tag current conversation point with a label";
    }

    @Override
    public String execute(String args, CommandContext context) {
        if (context.agentLoop() == null) {
            return AnsiStyle.red("  ✗ AgentLoop 不可用。");
        }

        String trimmedArgs = args != null ? args.trim() : "";

        if (trimmedArgs.isEmpty()) {
            return showUsage();
        }

        // 解析子命令
        String[] parts = trimmedArgs.split("\\s+", 2);
        String firstArg = parts[0].toLowerCase();

        return switch (firstArg) {
            case "list" -> listTags(context);
            case "goto" -> {
                String tagName = parts.length > 1 ? parts[1].trim() : "";
                yield gotoTag(tagName, context);
            }
            default -> {
                // 第一个参数不是子命令，视为标签名称
                yield createTag(trimmedArgs, context);
            }
        };
    }

    /**
     * 创建标签，记录当前消息历史的大小。
     *
     * @param tagName 标签名称
     * @param context 命令上下文
     * @return 操作结果信息
     */
    private String createTag(String tagName, CommandContext context) {
        if (tagName.isEmpty()) {
            return AnsiStyle.red("  ✗ 请指定标签名称。");
        }

        // 标签名称不能与子命令冲突（虽然 list/goto 已在 switch 中处理）
        int position = context.agentLoop().getMessageHistory().size();
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));

        boolean isOverwrite = tags.containsKey(tagName);
        tags.put(tagName, new TagInfo(position, timestamp));

        String action = isOverwrite ? "已更新" : "已创建";
        return AnsiStyle.green("  ✓ 标签" + action + ": ") + AnsiStyle.bold(tagName) + "\n"
                + AnsiStyle.dim("    位置: 第 " + position + " 条消息  时间: " + timestamp);
    }

    /**
     * 列出所有已保存的标签。
     *
     * @param context 命令上下文
     * @return 标签列表信息
     */
    private String listTags(CommandContext context) {
        if (tags.isEmpty()) {
            return AnsiStyle.dim("  没有保存的标签。") + "\n"
                    + AnsiStyle.dim("  使用 /tag <name> 为当前位置打标签。");
        }

        int currentPosition = context.agentLoop().getMessageHistory().size();

        StringBuilder sb = new StringBuilder();
        sb.append(AnsiStyle.bold("\n  🏷️  Conversation Tags\n\n"));

        for (Map.Entry<String, TagInfo> entry : tags.entrySet()) {
            String name = entry.getKey();
            TagInfo info = entry.getValue();

            // 高亮当前位置匹配的标签
            String marker = (info.position() == currentPosition)
                    ? AnsiStyle.green(" ◀ current")
                    : "";

            sb.append("  • ")
                    .append(AnsiStyle.bold(name))
                    .append(AnsiStyle.dim("  (position=" + info.position() + ", " + info.timestamp() + ")"))
                    .append(marker)
                    .append("\n");
        }

        sb.append("\n")
                .append(AnsiStyle.dim("  当前位置: " + currentPosition + " 条消息  |  共 " + tags.size() + " 个标签"))
                .append("\n");
        return sb.toString();
    }

    /**
     * 回溯到指定标签对应的对话位置。
     * <p>
     * 将消息历史截断到标签记录的位置。如果当前消息数少于标签位置，
     * 则不做截断（标签位置可能超出当前对话长度）。
     *
     * @param tagName 标签名称
     * @param context 命令上下文
     * @return 操作结果信息
     */
    private String gotoTag(String tagName, CommandContext context) {
        if (tagName.isEmpty()) {
            return AnsiStyle.red("  ✗ 请指定标签名称。") + "\n"
                    + AnsiStyle.dim("  用法: /tag goto <name>");
        }

        TagInfo info = tags.get(tagName);
        if (info == null) {
            return AnsiStyle.red("  ✗ 标签不存在: " + tagName) + "\n"
                    + AnsiStyle.dim("  使用 /tag list 查看所有可用标签。");
        }

        List<Message> currentHistory = context.agentLoop().getMessageHistory();
        int currentSize = currentHistory.size();
        int targetPosition = info.position();

        if (targetPosition >= currentSize) {
            return AnsiStyle.yellow("  ⚠ 标签位置 (" + targetPosition + ") 不小于当前消息数 ("
                    + currentSize + ")，无需回溯。");
        }

        // 截断到标签位置
        List<Message> truncated = new ArrayList<>(currentHistory.subList(0, targetPosition));
        context.agentLoop().replaceHistory(truncated);

        int removedCount = currentSize - targetPosition;
        return AnsiStyle.green("  ✓ 已回溯到标签: ") + AnsiStyle.bold(tagName) + "\n"
                + AnsiStyle.dim("    移除了 " + removedCount + " 条消息，当前消息数: " + targetPosition);
    }

    /**
     * 显示用法帮助信息。
     *
     * @return 用法说明文本
     */
    private String showUsage() {
        StringBuilder sb = new StringBuilder();
        sb.append(AnsiStyle.bold("\n  🏷️  Tag — 对话位置标签\n\n"));
        sb.append("  ").append(AnsiStyle.cyan("/tag <name>")).append("         为当前位置打标签\n");
        sb.append("  ").append(AnsiStyle.cyan("/tag list")).append("           列出所有标签\n");
        sb.append("  ").append(AnsiStyle.cyan("/tag goto <name>")).append("    回溯到指定标签位置\n");
        return sb.toString();
    }

    /**
     * 标签信息记录 —— 保存标签的消息位置和创建时间。
     *
     * @param position  消息历史中的位置（消息数量）
     * @param timestamp 创建时间戳
     */
    private record TagInfo(int position, String timestamp) {}
}
