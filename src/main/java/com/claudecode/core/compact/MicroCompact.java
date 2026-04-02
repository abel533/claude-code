package com.claudecode.core.compact;

import com.claudecode.core.compact.CompactionResult.CompactLayer;
import org.springframework.ai.chat.messages.*;

import java.util.List;

/**
 * 微压缩 —— 在每次 API 调用后执行，裁剪旧的 tool_result 内容。
 * <p>
 * 对应 claude-code 的 microCompact。不需要额外 API 调用，纯本地操作。
 * 策略：保留最近 N 轮的 tool 结果，更早的只保留摘要行 "[Tool result truncated]"。
 */
public class MicroCompact {

    /** 保留最近 N 条 ToolResponseMessage 的完整内容 */
    private static final int KEEP_RECENT_TOOL_RESULTS = 6;

    /** 截断阈值：超过此长度的旧 tool result 才会被截断 */
    private static final int TRUNCATE_THRESHOLD = 200;

    /** 截断后的占位文本 */
    private static final String TRUNCATED_MARKER = "[Tool result truncated — %d chars omitted]";

    /**
     * 对消息历史执行微压缩。
     * 直接在原始列表上原地修改以提升性能。
     *
     * @param history 消息列表（直接修改）
     * @return 压缩结果
     */
    public CompactionResult compact(List<Message> history) {
        int totalToolResponses = 0;
        int truncated = 0;

        // 倒序扫描，找到所有 ToolResponseMessage 的位置
        int recentCount = 0;
        for (int i = history.size() - 1; i >= 0; i--) {
            if (history.get(i) instanceof ToolResponseMessage) {
                totalToolResponses++;
                recentCount++;
                if (recentCount > KEEP_RECENT_TOOL_RESULTS) {
                    // 需要截断
                    ToolResponseMessage trm = (ToolResponseMessage) history.get(i);
                    if (shouldTruncate(trm)) {
                        history.set(i, truncateToolResponse(trm));
                        truncated++;
                    }
                }
            }
        }

        if (truncated == 0) {
            return CompactionResult.noAction(CompactLayer.MICRO, "No tool results to truncate");
        }

        return CompactionResult.success(CompactLayer.MICRO, totalToolResponses,
                totalToolResponses - truncated, null);
    }

    /** 判断 ToolResponseMessage 是否需要截断 */
    private boolean shouldTruncate(ToolResponseMessage trm) {
        var responses = trm.getResponses();
        if (responses == null || responses.isEmpty()) return false;
        for (var resp : responses) {
            if (resp.responseData() != null && resp.responseData().toString().length() > TRUNCATE_THRESHOLD) {
                return true;
            }
        }
        return false;
    }

    /** 创建截断后的 ToolResponseMessage */
    private ToolResponseMessage truncateToolResponse(ToolResponseMessage original) {
        var responses = original.getResponses();
        if (responses == null || responses.isEmpty()) return original;

        var truncatedResponses = responses.stream().map(resp -> {
            String data = resp.responseData() != null ? resp.responseData().toString() : "";
            if (data.length() > TRUNCATE_THRESHOLD) {
                String marker = String.format(TRUNCATED_MARKER, data.length());
                return new ToolResponseMessage.ToolResponse(resp.id(), resp.name(), marker);
            }
            return resp;
        }).toList();

        return ToolResponseMessage.builder()
                .responses(truncatedResponses)
                .build();
    }
}
