package com.claudecode.core.compact;

import com.claudecode.core.compact.CompactionResult.CompactLayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.ArrayList;
import java.util.List;

/**
 * 全量压缩 —— AI 摘要全部对话历史，带 PTL（Prompt Too Long）重试。
 * <p>
 * 对应 claude-code 的 fullCompact。当 SessionMemoryCompact 无法有效压缩时作为兜底。
 * PTL 重试策略：按 API Round（user→assistant→tool_result 为一组）逐步丢弃最旧的组。
 */
public class FullCompact {

    private static final Logger log = LoggerFactory.getLogger(FullCompact.class);

    /** PTL 重试最大次数 */
    private static final int MAX_PTL_RETRIES = 5;

    /** 保留最近 N 条消息（不压缩） */
    private static final int KEEP_RECENT_MESSAGES = 2;

    private static final String FULL_COMPACT_PROMPT = """
            Please compress the following conversation history into a thorough summary. Requirements:
            1. Preserve ALL key decisions, code changes, and technical details
            2. Keep file paths, function names, class names, and specific identifiers
            3. Preserve user preferences, requirements, and constraints
            4. Record the current state of work: what was completed, what remains, what's blocked
            5. Note any errors encountered and their resolutions
            6. Keep important context about the project structure and architecture
            7. Output within 1000 words, using structured bullet points
            
            Conversation history:
            """;

    private final ChatModel chatModel;

    public FullCompact(ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    /**
     * 执行全量压缩。
     *
     * @param history 当前消息历史
     * @return 压缩后的新历史；如果失败返回 null
     */
    public List<Message> compact(List<Message> history) {
        if (history.size() <= KEEP_RECENT_MESSAGES + 2) {
            return null;
        }

        int before = history.size();
        Message systemMsg = history.getFirst();

        // 按 API Round 分组
        List<ApiRound> rounds = groupByRounds(history);

        // PTL 重试循环：逐步丢弃最旧的 round
        int dropCount = 0;
        while (dropCount < rounds.size() - 1 && dropCount < MAX_PTL_RETRIES) {
            List<ApiRound> remaining = rounds.subList(dropCount, rounds.size());

            try {
                String summary = generateFullSummary(remaining);
                if (summary != null && !summary.isBlank()) {
                    // 构建新历史
                    List<Message> newHistory = new ArrayList<>();
                    newHistory.add(systemMsg);
                    newHistory.add(new SystemMessage("[Conversation Summary]\n" + summary));

                    // 保留最后几条消息
                    for (int i = Math.max(1, before - KEEP_RECENT_MESSAGES); i < before; i++) {
                        newHistory.add(history.get(i));
                    }

                    log.info("Full compact succeeded: {} → {} messages (dropped {} rounds)",
                            before, newHistory.size(), dropCount);
                    return newHistory;
                }
            } catch (Exception e) {
                log.warn("Full compact attempt failed (drop={}): {}", dropCount, e.getMessage());
                // PTL error — drop oldest round and retry
            }

            dropCount++;
        }

        log.error("Full compact failed after {} PTL retries", dropCount);
        return null;
    }

    /**
     * 执行全量压缩并返回 CompactionResult。
     */
    public CompactionResult compactWithResult(List<Message> history) {
        int before = history.size();
        List<Message> result = compact(history);
        if (result == null) {
            return CompactionResult.failure(CompactLayer.FULL, "Full compact failed");
        }
        return CompactionResult.success(CompactLayer.FULL, before, result.size(), null);
    }

    // ── 内部方法 ──

    /** 按 API Round 分组：一个 round = [UserMessage] + [AssistantMessage + ToolResponseMessages...] */
    private List<ApiRound> groupByRounds(List<Message> history) {
        List<ApiRound> rounds = new ArrayList<>();
        List<Message> currentRound = new ArrayList<>();

        for (int i = 1; i < history.size(); i++) { // 跳过系统消息
            Message msg = history.get(i);
            if (msg instanceof UserMessage && !currentRound.isEmpty()) {
                rounds.add(new ApiRound(List.copyOf(currentRound)));
                currentRound.clear();
            }
            currentRound.add(msg);
        }

        if (!currentRound.isEmpty()) {
            rounds.add(new ApiRound(List.copyOf(currentRound)));
        }

        return rounds;
    }

    /** 生成全量摘要 */
    private String generateFullSummary(List<ApiRound> rounds) {
        StringBuilder dialogText = new StringBuilder();

        for (ApiRound round : rounds) {
            for (Message msg : round.messages()) {
                switch (msg) {
                    case UserMessage um -> dialogText.append("[User] ").append(um.getText()).append("\n");
                    case AssistantMessage am -> {
                        if (am.getText() != null && !am.getText().isBlank()) {
                            String text = am.getText();
                            if (text.length() > 600) text = text.substring(0, 600) + "...";
                            dialogText.append("[Assistant] ").append(text).append("\n");
                        }
                        if (am.hasToolCalls()) {
                            for (var tc : am.getToolCalls()) {
                                dialogText.append("[Tool Call] ").append(tc.name()).append("\n");
                            }
                        }
                    }
                    case ToolResponseMessage trm -> {
                        for (var resp : trm.getResponses()) {
                            dialogText.append("[Tool Result: ").append(resp.name()).append("]\n");
                        }
                    }
                    default -> {}
                }
            }
            dialogText.append("---\n");
        }

        if (dialogText.isEmpty()) return null;

        Prompt prompt = new Prompt(List.of(new UserMessage(FULL_COMPACT_PROMPT + dialogText)));
        ChatResponse response = chatModel.call(prompt);
        return response.getResult().getOutput().getText();
    }

    /** API Round：一个用户请求 + AI 响应 + 工具调用的完整回合 */
    private record ApiRound(List<Message> messages) {}
}
