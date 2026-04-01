package com.claudecode.command;

import java.util.List;

/**
 * 斜杠命令接口 —— 对应 claude-code/src/commands.ts 中的 Command 类型。
 * <p>
 * 用于处理以 / 开头的用户输入命令。
 */
public interface SlashCommand {

    /** 命令名称（不含 / 前缀） */
    String name();

    /** 命令描述 */
    String description();

    /** 命令别名列表 */
    default List<String> aliases() {
        return List.of();
    }

    /**
     * 执行命令。
     *
     * @param args    命令参数（/ 后的文本去掉命令名后的部分）
     * @param context 命令执行上下文
     * @return 命令输出文本
     */
    String execute(String args, CommandContext context);
}
