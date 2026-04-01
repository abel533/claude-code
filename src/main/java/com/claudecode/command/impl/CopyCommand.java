package com.claudecode.command.impl;

import com.claudecode.command.CommandContext;
import com.claudecode.command.SlashCommand;
import com.claudecode.console.AnsiStyle;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;

import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.util.List;

/**
 * /copy 命令 —— 将最近一次 AI 回复复制到系统剪贴板。
 * <p>
 * 从消息历史中提取最后一条 AssistantMessage 的文本内容，
 * 使用 AWT 剪贴板 API 复制。
 */
public class CopyCommand implements SlashCommand {

    @Override
    public String name() {
        return "copy";
    }

    @Override
    public String description() {
        return "Copy last AI response to clipboard";
    }

    @Override
    public String execute(String args, CommandContext context) {
        // 从消息历史中查找最后一条助手消息
        List<Message> history = context.agentLoop().getMessageHistory();
        String lastResponse = null;

        for (int i = history.size() - 1; i >= 0; i--) {
            Message msg = history.get(i);
            if (msg instanceof AssistantMessage assistant) {
                String text = assistant.getText();
                if (text != null && !text.isBlank()) {
                    lastResponse = text;
                    break;
                }
            }
        }

        if (lastResponse == null) {
            return AnsiStyle.yellow("  ⚠ 暂无 AI 回复可复制");
        }

        try {
            // 使用 AWT 剪贴板
            StringSelection selection = new StringSelection(lastResponse);
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(selection, selection);

            int charCount = lastResponse.length();
            int lineCount = (int) lastResponse.lines().count();
            return AnsiStyle.green("  ✓ 已复制到剪贴板")
                    + AnsiStyle.dim(" (" + charCount + " 字符, " + lineCount + " 行)");
        } catch (java.awt.HeadlessException e) {
            // 无头环境（如 SSH）无法使用 AWT 剪贴板
            return AnsiStyle.yellow("  ⚠ 当前环境不支持剪贴板（Headless 模式）\n")
                    + AnsiStyle.dim("    提示：在有图形界面的终端中运行可使用此功能");
        } catch (Exception e) {
            return AnsiStyle.red("  ✗ 复制失败: " + e.getMessage());
        }
    }
}
