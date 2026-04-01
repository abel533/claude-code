package com.claudecode.repl;

import com.claudecode.command.CommandRegistry;
import com.claudecode.command.SlashCommand;
import com.claudecode.tool.ToolRegistry;
import org.jline.reader.Candidate;
import org.jline.reader.Completer;
import org.jline.reader.LineReader;
import org.jline.reader.ParsedLine;

import java.util.List;

/**
 * Tab 补全器 —— 对应 claude-code 中的命令补全逻辑。
 * <p>
 * 支持：
 * <ul>
 *   <li>斜杠命令补全（输入 / 后按 Tab）</li>
 *   <li>工具名称补全（用于调试或直接引用）</li>
 * </ul>
 */
public class ClaudeCodeCompleter implements Completer {

    private final CommandRegistry commandRegistry;
    private final ToolRegistry toolRegistry;

    public ClaudeCodeCompleter(CommandRegistry commandRegistry, ToolRegistry toolRegistry) {
        this.commandRegistry = commandRegistry;
        this.toolRegistry = toolRegistry;
    }

    @Override
    public void complete(LineReader reader, ParsedLine line, List<Candidate> candidates) {
        String buffer = line.line().substring(0, line.cursor());

        if (buffer.startsWith("/")) {
            // 斜杠命令补全
            completeCommands(buffer, candidates);
        }
    }

    /** 补全斜杠命令 */
    private void completeCommands(String buffer, List<Candidate> candidates) {
        String prefix = buffer.substring(1).toLowerCase();

        for (SlashCommand cmd : commandRegistry.getCommands()) {
            String name = cmd.name();
            if (name.startsWith(prefix)) {
                candidates.add(new Candidate(
                        "/" + name,          // 补全值
                        name,                // 显示文本
                        "Commands",          // 分组
                        cmd.description(),   // 描述（右侧提示）
                        null,                // 后缀
                        null,                // 关键字
                        true                 // 完整补全
                ));
            }
            // 也匹配别名
            for (String alias : cmd.aliases()) {
                if (alias.startsWith(prefix)) {
                    candidates.add(new Candidate(
                            "/" + alias,
                            alias + " → " + name,
                            "Aliases",
                            cmd.description(),
                            null, null, true
                    ));
                }
            }
        }
    }
}
