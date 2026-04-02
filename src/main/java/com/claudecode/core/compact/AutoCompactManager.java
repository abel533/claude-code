package com.claudecode.core.compact;

import com.claudecode.core.TokenTracker;
import com.claudecode.core.compact.CompactionResult.CompactLayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 自动压缩编排器 —— 根据 token 使用量自动选择并执行压缩策略。
 * <p>
 * 对应 claude-code 的自动压缩编排逻辑。在 AgentLoop 中每次 API 响应后调用。
 * 流程：检查阈值 → 微压缩 → Session Memory 压缩 → 全量压缩（兜底）
 * 熔断器：连续失败 {@value MAX_CONSECUTIVE_FAILURES} 次后暂停自动压缩。
 */
public class AutoCompactManager {

    private static final Logger log = LoggerFactory.getLogger(AutoCompactManager.class);

    /** 连续失败阈值，超过后暂停自动压缩 */
    private static final int MAX_CONSECUTIVE_FAILURES = 3;

    private final MicroCompact microCompact;
    private final SessionMemoryCompact sessionMemoryCompact;
    private final FullCompact fullCompact;
    private final TokenTracker tokenTracker;

    /** 连续压缩失败次数 */
    private int consecutiveFailures = 0;

    /** 是否已触发过熔断 */
    private boolean circuitBroken = false;

    /** 压缩事件回调（用于通知 UI） */
    private Consumer<CompactionResult> onCompactionEvent;

    public AutoCompactManager(ChatModel chatModel, TokenTracker tokenTracker) {
        this.tokenTracker = tokenTracker;
        this.microCompact = new MicroCompact();
        this.sessionMemoryCompact = new SessionMemoryCompact(chatModel);
        this.fullCompact = new FullCompact(chatModel);
    }

    public void setOnCompactionEvent(Consumer<CompactionResult> onCompactionEvent) {
        this.onCompactionEvent = onCompactionEvent;
    }

    /**
     * 在每次 API 响应后调用，根据 token 使用状态自动执行压缩。
     *
     * @param historySupplier  获取当前消息历史的函数
     * @param historyReplacer  替换消息历史的函数
     * @return 如果执行了压缩返回结果，否则返回 null
     */
    public CompactionResult autoCompactIfNeeded(
            Supplier<List<Message>> historySupplier,
            Consumer<List<Message>> historyReplacer) {

        // 熔断器检查
        if (circuitBroken) {
            return null;
        }

        // 检查是否需要压缩
        if (!tokenTracker.shouldAutoCompact()) {
            // 即使不需要自动压缩，也执行微压缩（成本极低）
            List<Message> history = historySupplier.get();
            if (history instanceof java.util.ArrayList<Message> mutableHistory) {
                microCompact.compact(mutableHistory);
            }
            return null;
        }

        log.info("Auto-compact triggered at {}% token usage",
                String.format("%.1f", tokenTracker.getUsagePercentage() * 100));

        List<Message> history = historySupplier.get();

        // 阶段 1：微压缩
        if (history instanceof java.util.ArrayList<Message> mutableHistory) {
            CompactionResult microResult = microCompact.compact(mutableHistory);
            if (microResult.success()) {
                notifyEvent(microResult);
                // 微压缩后重新检查是否仍需深度压缩
                if (!tokenTracker.shouldAutoCompact()) {
                    consecutiveFailures = 0;
                    return microResult;
                }
            }
        }

        // 阶段 2：Session Memory 压缩
        try {
            List<Message> compacted = sessionMemoryCompact.getCompactedHistory(history);
            if (compacted != null) {
                historyReplacer.accept(compacted);
                CompactionResult result = CompactionResult.success(
                        CompactLayer.SESSION_MEMORY,
                        history.size(), compacted.size(),
                        "Auto session memory compact");
                consecutiveFailures = 0;
                notifyEvent(result);
                log.info("Session memory compact: {} → {} messages", history.size(), compacted.size());
                return result;
            }
        } catch (Exception e) {
            log.warn("Session memory compact failed: {}", e.getMessage());
        }

        // 阶段 3：全量压缩（兜底）
        try {
            List<Message> compacted = fullCompact.compact(history);
            if (compacted != null) {
                historyReplacer.accept(compacted);
                CompactionResult result = CompactionResult.success(
                        CompactLayer.FULL,
                        history.size(), compacted.size(),
                        "Auto full compact (fallback)");
                consecutiveFailures = 0;
                notifyEvent(result);
                log.info("Full compact fallback: {} → {} messages", history.size(), compacted.size());
                return result;
            }
        } catch (Exception e) {
            log.warn("Full compact failed: {}", e.getMessage());
        }

        // 所有压缩方式均失败
        consecutiveFailures++;
        if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
            circuitBroken = true;
            log.error("Auto-compact circuit breaker triggered after {} consecutive failures",
                    consecutiveFailures);
            CompactionResult result = CompactionResult.failure(CompactLayer.FULL,
                    "Circuit breaker: auto-compact disabled after " + consecutiveFailures + " failures");
            notifyEvent(result);
            return result;
        }

        return CompactionResult.failure(CompactLayer.SESSION_MEMORY,
                "All compression strategies failed");
    }

    /** 手动重置熔断器 */
    public void resetCircuitBreaker() {
        circuitBroken = false;
        consecutiveFailures = 0;
        log.info("Auto-compact circuit breaker reset");
    }

    public boolean isCircuitBroken() {
        return circuitBroken;
    }

    public int getConsecutiveFailures() {
        return consecutiveFailures;
    }

    /** 获取 FullCompact 实例（供 CompactCommand 委托使用） */
    public FullCompact getFullCompact() {
        return fullCompact;
    }

    private void notifyEvent(CompactionResult result) {
        if (onCompactionEvent != null) {
            try {
                onCompactionEvent.accept(result);
            } catch (Exception e) {
                log.debug("Compaction event notification failed", e);
            }
        }
    }
}
