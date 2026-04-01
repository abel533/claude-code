package com.claudecode.command.impl;

import com.claudecode.command.CommandContext;
import com.claudecode.command.SlashCommand;
import com.claudecode.console.AnsiStyle;
import com.claudecode.core.TokenTracker;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.ArrayList;
import java.util.List;

/**
 * /compact 命令 —— 用 AI 生成摘要来压缩上下文。
 * <p>
 * 对应 claude-code/src/commands/compact.ts。
 * 将详细的对话历史替换为 AI 生成的摘要，大幅减少 token 消耗。
 * 保留系统提示词和最近一轮对话。
 */
public class CompactCommand implements SlashCommand {

    private static final String COMPACT_PROMPT = """
            Please compress the following conversation history into a concise summary. Requirements:
            1. Preserve all key decisions, code changes, and technical details
            2. Keep file paths, function names, and specific information
            3. Preserve user preferences and requirements
            4. Omit repeated discussions and irrelevant details
            5. Output within 500 words
            
            Conversation history:
            """;

    @Override
    public String name() {
        return "compact";
    }

    @Override
    public String description() {
        return "Compact conversation context with AI summary";
    }

    @Override
    public String execute(String args, CommandContext context) {
        if (context.agentLoop() == null) {
            return AnsiStyle.yellow("  ⚠ No active conversation to compact.");
        }

        List<Message> history = context.agentLoop().getMessageHistory();
        int before = history.size();

        if (before <= 3) {
            return AnsiStyle.dim("  Context is already small (" + before + " messages), no compaction needed.");
        }

        TokenTracker tracker = context.agentLoop().getTokenTracker();
        long tokensBefore = tracker.getInputTokens() + tracker.getOutputTokens();

        // 尝试用 AI 生成摘要
        String summary = generateSummary(context, history);

        // 构建压缩后的历史：系统提示 + 摘要作为系统消息 + 保留最后一轮对话
        List<Message> compacted = new ArrayList<>();
        compacted.add(history.getFirst()); // 原始系统提示词

        if (summary != null && !summary.isBlank()) {
            compacted.add(new SystemMessage("[Conversation Summary] " + summary));
        }

        // 保留最后一轮用户消息和助手回复（如果有）
        for (int i = Math.max(1, before - 2); i < before; i++) {
            compacted.add(history.get(i));
        }

        context.agentLoop().replaceHistory(compacted);
        int after = compacted.size();

        StringBuilder sb = new StringBuilder();
        sb.append(AnsiStyle.green("  ✅ Context compacted")).append("\n");
        sb.append("  Messages: ").append(before).append(" → ").append(after).append("\n");
        if (tokensBefore > 0) {
            sb.append("  Tokens before compaction: ").append(TokenTracker.formatTokens(tokensBefore)).append("\n");
        }
        if (summary != null) {
            sb.append(AnsiStyle.dim("  📝 AI summary generated and injected into context"));
        } else {
            sb.append(AnsiStyle.dim("  ⚠ AI summary generation failed, keeping recent conversation only"));
        }

        return sb.toString();
    }

    /** 调用 AI 生成对话摘要 */
    private String generateSummary(CommandContext context, List<Message> history) {
        try {
            ChatModel chatModel = context.agentLoop().getChatModel();

            // 构建摘要请求的消息列史
            StringBuilder dialogText = new StringBuilder();
            for (Message msg : history) {
                switch (msg) {
                    case UserMessage um -> dialogText.append("[User] ").append(um.getText()).append("\n");
                    case AssistantMessage am -> {
                        if (am.getText() != null && !am.getText().isBlank()) {
                            // 截断过长的助手回复
                            String text = am.getText();
                            if (text.length() > 500) text = text.substring(0, 500) + "...";
                            dialogText.append("[Assistant] ").append(text).append("\n");
                        }
                        if (am.hasToolCalls()) {
                            for (var tc : am.getToolCalls()) {
                                dialogText.append("[Tool Call] ").append(tc.name()).append("\n");
                            }
                        }
                    }
                    default -> {} // 跳过系统消息和工具响应
                }
            }

            if (dialogText.isEmpty()) return null;

            Prompt summaryPrompt = new Prompt(List.of(
                    new UserMessage(COMPACT_PROMPT + dialogText)
            ));

            ChatResponse response = chatModel.call(summaryPrompt);
            return response.getResult().getOutput().getText();
        } catch (Exception e) {
            // 摘要生成失败不影响压缩操作
            return null;
        }
    }
}
