package com.claudecode.command;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * 命令注册中心 —— 对应 claude-code/src/commands.ts 中的命令集合管理。
 */
public class CommandRegistry {

    private static final Logger log = LoggerFactory.getLogger(CommandRegistry.class);

    private final Map<String, SlashCommand> commands = new LinkedHashMap<>();

    /** 注册命令（包括别名） */
    public void register(SlashCommand command) {
        commands.put(command.name().toLowerCase(), command);
        for (String alias : command.aliases()) {
            commands.put(alias.toLowerCase(), command);
        }
        log.debug("Registered command: /{}", command.name());
    }

    /** 批量注册 */
    public void registerAll(SlashCommand... cmds) {
        for (SlashCommand cmd : cmds) {
            register(cmd);
        }
    }

    /** 解析并执行命令 */
    public Optional<String> dispatch(String input, CommandContext context) {
        if (!input.startsWith("/")) {
            return Optional.empty();
        }

        String stripped = input.substring(1).strip();
        String[] parts = stripped.split("\\s+", 2);
        String cmdName = parts[0].toLowerCase();
        String args = parts.length > 1 ? parts[1] : "";

        SlashCommand cmd = commands.get(cmdName);
        if (cmd == null) {
            return Optional.of("Unknown command: /" + cmdName + ". Type /help for available commands.");
        }

        return Optional.of(cmd.execute(args, context));
    }

    /** 判断输入是否是斜杠命令 */
    public boolean isCommand(String input) {
        return input != null && input.startsWith("/");
    }

    /** 获取所有唯一命令（用于 /help） */
    public List<SlashCommand> getCommands() {
        return commands.values().stream().distinct().toList();
    }

    /** 获取命令名称（用于 Tab 补全） */
    public Set<String> getCommandNames() {
        return Set.copyOf(commands.keySet());
    }
}
