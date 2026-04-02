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
 * Session Memory 压缩 —— 保留近期消息段，用 AI 摘要旧消息。
 * <p>
 * 对应 claude-code 的 sessionMemoryCompact。这是主要的自动压缩方式。
 * 算法：
 * <ol>
 *   <li>找到上次压缩的边界（通过检测 [Conversation Summary] 标记）</li>
 *   <li>计算需要保留的近期消息段（至少保留 MIN_KEEP_TOKENS token 估算量 + MIN_KEEP_TEXT_MSGS 条文本消息）</li>
 *   <li>将边界之后、保留段之前的消息通过 AI 生成摘要</li>
 *   <li>用 [系统提示] + [历史摘要] + [新摘要] + [保留段] 替换历史</li>
 * </ol>
 */
public class SessionMemoryCompact {

    private static final Logger log = LoggerFactory.getLogger(SessionMemoryCompact.class);

    /** 最少保留的文本消息数（用户 + 助手） */
    private static final int MIN_KEEP_TEXT_MSGS = 5;

    /** 估算最少保留的 token 数 */
    private static final int MIN_KEEP_TOKENS = 10_000;

    /** 估算最多保留的 token 数 */
    private static final int MAX_KEEP_TOKENS = 40_000;

    /** 每字符估算的 token 数（粗略近似） */
    private static final double CHARS_PER_TOKEN = 4.0;

    /** token 估算安全系数（偏保守，对应 TS 的 4/3 乘数） */
    private static final double ESTIMATION_SAFETY_FACTOR = 4.0 / 3.0;

    private static final String SUMMARY_PROMPT = """
            Summarize the following conversation segment concisely but thoroughly.
            Preserve:
            - All key technical decisions and their rationale
            - File paths, function names, class names, and specific code identifiers
            - User requirements and preferences
            - Current state of work (what was done, what remains)
            - Any errors encountered and their resolutions
            
            Keep the summary under 800 words. Use bullet points for clarity.
            
            Conversation segment to summarize:
            """;

    private final ChatModel chatModel;

    public SessionMemoryCompact(ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    /**
     * 执行 Session Memory 压缩。
     *
     * @param history 当前消息历史（不直接修改，返回新列表）
     * @return 压缩结果；如果无法压缩返回 noAction
     */
    public CompactionResult compact(List<Message> history) {
        if (history.size() <= MIN_KEEP_TEXT_MSGS + 2) {
            return CompactionResult.noAction(CompactLayer.SESSION_MEMORY,
                    "Too few messages to compact");
        }

        int before = history.size();

        // 找到系统提示词（第一条）
        Message systemMsg = history.getFirst();

        // 找到上一次摘要的位置（如果有的话）
        int lastSummaryIndex = findLastSummaryIndex(history);

        // 从摘要之后开始计算可压缩区域
        int compressibleStart = lastSummaryIndex + 1;

        // 从末尾向前找保留段的起始位置
        int keepStart = findKeepStart(history, compressibleStart);

        // 如果可压缩区域太小，不值得压缩
        if (keepStart - compressibleStart < 4) {
            return CompactionResult.noAction(CompactLayer.SESSION_MEMORY,
                    "Not enough messages to compress (only " + (keepStart - compressibleStart) + " in range)");
        }

        // 提取需要压缩的消息段
        List<Message> toCompress = history.subList(compressibleStart, keepStart);

        // 生成摘要
        String summary;
        try {
            summary = generateSummary(toCompress);
        } catch (Exception e) {
            log.warn("Session memory compression failed: {}", e.getMessage());
            return CompactionResult.failure(CompactLayer.SESSION_MEMORY,
                    "Summary generation failed: " + e.getMessage());
        }

        if (summary == null || summary.isBlank()) {
            return CompactionResult.failure(CompactLayer.SESSION_MEMORY,
                    "Empty summary generated");
        }

        // 构建新历史
        List<Message> newHistory = new ArrayList<>();
        newHistory.add(systemMsg);

        // 保留旧的摘要（如果有的话，合并到新摘要中）
        String previousSummary = extractPreviousSummary(history, lastSummaryIndex);
        if (previousSummary != null) {
            summary = "=== Earlier Context ===\n" + previousSummary + "\n\n=== Recent Activity ===\n" + summary;
        }

        // 添加新的摘要消息
        newHistory.add(new SystemMessage("[Conversation Summary]\n" + summary));

        // 添加保留段
        for (int i = keepStart; i < history.size(); i++) {
            newHistory.add(history.get(i));
        }

        int after = newHistory.size();
        return new CompactionResult(true, CompactLayer.SESSION_MEMORY, before, after, summary,
                "Session memory compacted: " + before + " → " + after + " messages");
    }

    /**
     * 获取压缩后的新历史。调用方需要先调用 compact() 确认成功，然后调用此方法获取结果。
     * 为避免重复逻辑，此方法重新执行压缩并返回新历史。
     */
    public List<Message> getCompactedHistory(List<Message> history) {
        if (history.size() <= MIN_KEEP_TEXT_MSGS + 2) return null;

        Message systemMsg = history.getFirst();
        int lastSummaryIndex = findLastSummaryIndex(history);
        int compressibleStart = lastSummaryIndex + 1;
        int keepStart = findKeepStart(history, compressibleStart);

        if (keepStart - compressibleStart < 4) return null;

        List<Message> toCompress = history.subList(compressibleStart, keepStart);
        String summary;
        try {
            summary = generateSummary(toCompress);
        } catch (Exception e) {
            return null;
        }
        if (summary == null || summary.isBlank()) return null;

        List<Message> newHistory = new ArrayList<>();
        newHistory.add(systemMsg);

        String previousSummary = extractPreviousSummary(history, lastSummaryIndex);
        if (previousSummary != null) {
            summary = "=== Earlier Context ===\n" + previousSummary + "\n\n=== Recent Activity ===\n" + summary;
        }

        newHistory.add(new SystemMessage("[Conversation Summary]\n" + summary));
        for (int i = keepStart; i < history.size(); i++) {
            newHistory.add(history.get(i));
        }

        return newHistory;
    }

    // ── 内部方法 ──

    /** 找到历史中最后一个 [Conversation Summary] 系统消息的索引 */
    private int findLastSummaryIndex(List<Message> history) {
        for (int i = history.size() - 1; i >= 1; i--) {
            if (history.get(i) instanceof SystemMessage sm
                    && sm.getText() != null
                    && sm.getText().startsWith("[Conversation Summary]")) {
                return i;
            }
        }
        return 0; // 没有摘要，从系统提示之后开始
    }

    /** 从末尾向前找保留段的起始位置 */
    private int findKeepStart(List<Message> history, int minStart) {
        int textMsgCount = 0;
        long estimatedTokens = 0;

        for (int i = history.size() - 1; i >= minStart; i--) {
            Message msg = history.get(i);

            // 估算 token 量
            long msgTokens = estimateTokens(msg);
            estimatedTokens += msgTokens;

            if (msg instanceof UserMessage || msg instanceof AssistantMessage) {
                textMsgCount++;
            }

            // 确保不会拆分 tool_use / tool_result 对
            // 如果当前是 ToolResponseMessage，它的 AssistantMessage（含 tool_calls）应在前面
            if (msg instanceof ToolResponseMessage && i > minStart) {
                continue; // 继续往前包含对应的 AssistantMessage
            }

            // 满足最小保留条件，且已达到上限则停止
            if (textMsgCount >= MIN_KEEP_TEXT_MSGS && estimatedTokens >= MIN_KEEP_TOKENS) {
                // 检查是否达到 token 上限
                if (estimatedTokens >= MAX_KEEP_TOKENS) {
                    return i;
                }
            }
        }

        // 如果从 minStart 开始全部都在保留范围内，返回 minStart
        // 说明消息不够多，不需要压缩
        return minStart;
    }

    /** 估算消息的 token 数 */
    private long estimateTokens(Message msg) {
        String text = switch (msg) {
            case UserMessage um -> um.getText();
            case AssistantMessage am -> am.getText();
            case SystemMessage sm -> sm.getText();
            case ToolResponseMessage trm -> {
                StringBuilder sb = new StringBuilder();
                for (var resp : trm.getResponses()) {
                    if (resp.responseData() != null) {
                        sb.append(resp.responseData().toString());
                    }
                }
                yield sb.toString();
            }
            default -> "";
        };
        if (text == null || text.isEmpty()) return 10; // 最小估算
        return (long) (text.length() / CHARS_PER_TOKEN * ESTIMATION_SAFETY_FACTOR);
    }

    /** 提取上一次的摘要文本 */
    private String extractPreviousSummary(List<Message> history, int summaryIndex) {
        if (summaryIndex <= 0) return null;
        Message msg = history.get(summaryIndex);
        if (msg instanceof SystemMessage sm && sm.getText() != null) {
            String text = sm.getText();
            if (text.startsWith("[Conversation Summary]\n")) {
                return text.substring("[Conversation Summary]\n".length());
            }
            if (text.startsWith("[Conversation Summary] ")) {
                return text.substring("[Conversation Summary] ".length());
            }
        }
        return null;
    }

    /** 调用 AI 生成对话段摘要 */
    private String generateSummary(List<Message> segment) {
        StringBuilder dialogText = new StringBuilder();
        for (Message msg : segment) {
            switch (msg) {
                case UserMessage um -> dialogText.append("[User] ").append(um.getText()).append("\n");
                case AssistantMessage am -> {
                    if (am.getText() != null && !am.getText().isBlank()) {
                        String text = am.getText();
                        if (text.length() > 800) text = text.substring(0, 800) + "...";
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
                        String data = resp.responseData() != null ? resp.responseData().toString() : "";
                        if (data.length() > 200) data = data.substring(0, 200) + "...";
                        dialogText.append("[Tool Result: ").append(resp.name()).append("] ")
                                .append(data).append("\n");
                    }
                }
                default -> {}
            }
        }

        if (dialogText.isEmpty()) return null;

        Prompt prompt = new Prompt(List.of(new UserMessage(SUMMARY_PROMPT + dialogText)));
        ChatResponse response = chatModel.call(prompt);
        return response.getResult().getOutput().getText();
    }
}
