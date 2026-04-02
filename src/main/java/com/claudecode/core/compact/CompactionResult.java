package com.claudecode.core.compact;

/**
 * 压缩操作的结果数据。
 *
 * @param success         是否成功
 * @param layer           执行的压缩层级
 * @param messagesBefore  压缩前消息数
 * @param messagesAfter   压缩后消息数
 * @param summary         AI 生成的摘要（可能为 null）
 * @param reason          结果原因/描述
 */
public record CompactionResult(
        boolean success,
        CompactLayer layer,
        int messagesBefore,
        int messagesAfter,
        String summary,
        String reason
) {

    /** 压缩层级 */
    public enum CompactLayer {
        /** 微压缩：裁剪旧 tool_result 内容 */
        MICRO,
        /** Session Memory：AI 摘要旧消息，保留近期段 */
        SESSION_MEMORY,
        /** 全量压缩：AI 摘要全部，PTL 重试 */
        FULL,
        /** 用户手动触发的全量压缩 */
        MANUAL
    }

    public static CompactionResult success(CompactLayer layer, int before, int after, String summary) {
        return new CompactionResult(true, layer, before, after, summary,
                "Compacted from " + before + " to " + after + " messages");
    }

    public static CompactionResult noAction(CompactLayer layer, String reason) {
        return new CompactionResult(false, layer, 0, 0, null, reason);
    }

    public static CompactionResult failure(CompactLayer layer, String reason) {
        return new CompactionResult(false, layer, 0, 0, null, reason);
    }
}
