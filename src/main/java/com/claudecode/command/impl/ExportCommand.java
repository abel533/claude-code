package com.claudecode.command.impl;

import com.claudecode.command.CommandContext;
import com.claudecode.command.SlashCommand;
import com.claudecode.console.AnsiStyle;
import org.springframework.ai.chat.messages.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * /export 命令 —— 将对话历史导出为 Markdown 文件。
 * <p>
 * 支持格式：
 * <ul>
 *   <li>/export —— 导出到当前目录的 conversation-时间戳.md</li>
 *   <li>/export [路径] —— 导出到指定路径</li>
 * </ul>
 */
public class ExportCommand implements SlashCommand {

    private static final DateTimeFormatter TIMESTAMP_FMT = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    @Override
    public String name() {
        return "export";
    }

    @Override
    public String description() {
        return "Export conversation to Markdown file";
    }

    @Override
    public String execute(String args, CommandContext context) {
        List<Message> history = context.agentLoop().getMessageHistory();

        // 至少需要系统提示 + 用户消息 + 助手回复
        if (history.size() < 3) {
            return AnsiStyle.yellow("  ⚠ Not enough conversation content to export");
        }

        // 确定输出路径
        args = args == null ? "" : args.strip();
        Path outputPath;
        if (!args.isEmpty()) {
            outputPath = Path.of(args);
            if (!outputPath.isAbsolute()) {
                outputPath = Path.of(System.getProperty("user.dir")).resolve(outputPath);
            }
        } else {
            String timestamp = TIMESTAMP_FMT.format(LocalDateTime.now());
            outputPath = Path.of(System.getProperty("user.dir"), "conversation-" + timestamp + ".md");
        }

        // 生成 Markdown 内容
        String markdown = generateMarkdown(history);

        try {
            Files.createDirectories(outputPath.getParent());
            Files.writeString(outputPath, markdown, StandardCharsets.UTF_8);

            int msgCount = history.size();
            int lineCount = (int) markdown.lines().count();
            return AnsiStyle.green("  ✓ Conversation exported: " + outputPath)
                    + AnsiStyle.dim(" (" + msgCount + " messages, " + lineCount + " lines)");
        } catch (IOException e) {
            return AnsiStyle.red("  ✗ Export failed: " + e.getMessage());
        }
    }

    private String generateMarkdown(List<Message> history) {
        StringBuilder md = new StringBuilder();
        md.append("# Claude Code Java - Conversation Export\n\n");
        md.append("- **Exported at**: ").append(LocalDateTime.now()).append("\n");
        md.append("- **Working directory**: ").append(System.getProperty("user.dir")).append("\n");
        md.append("- **Messages**: ").append(history.size()).append("\n\n");
        md.append("---\n\n");

        for (Message msg : history) {
            switch (msg) {
                case SystemMessage sm -> {
                    md.append("## 🔧 System Prompt\n\n");
                    md.append("<details>\n<summary>Click to expand system prompt</summary>\n\n");
                    md.append(sm.getText()).append("\n\n");
                    md.append("</details>\n\n---\n\n");
                }
                case UserMessage um -> {
                    md.append("## 👤 User\n\n");
                    md.append(um.getText()).append("\n\n");
                }
                case AssistantMessage am -> {
                    md.append("## 🤖 Assistant\n\n");
                    if (am.getText() != null && !am.getText().isBlank()) {
                        md.append(am.getText()).append("\n\n");
                    }
                    if (am.hasToolCalls()) {
                        md.append("### Tool Calls\n\n");
                        for (var tc : am.getToolCalls()) {
                            md.append("- **").append(tc.name()).append("**");
                            if (tc.arguments() != null) {
                                md.append("\n  ```json\n  ").append(tc.arguments()).append("\n  ```");
                            }
                            md.append("\n");
                        }
                        md.append("\n");
                    }
                }
                case ToolResponseMessage trm -> {
                    md.append("### 🔨 Tool Results\n\n");
                    for (var resp : trm.getResponses()) {
                        md.append("**").append(resp.name()).append("**:\n");
                        String data = resp.responseData();
                        if (data != null) {
                            // 截断过长的工具输出
                            if (data.length() > 2000) {
                                data = data.substring(0, 2000) + "\n... (truncated)";
                            }
                            md.append("```\n").append(data).append("\n```\n\n");
                        }
                    }
                }
                default -> {}
            }
        }

        return md.toString();
    }
}
