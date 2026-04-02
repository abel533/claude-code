package com.claudecode.permission;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 拒绝追踪器 —— 跟踪连续和总计的权限拒绝次数。
 * <p>
 * 对应 claude-code 的 denialTracking.ts。
 * 当连续拒绝达到阈值（3 次）或总计拒绝达到阈值（20 次）时，
 * 建议回退到手动提示模式，避免 auto/plan 模式下的无限拒绝循环。
 */
public class DenialTracker {

    private static final Logger log = LoggerFactory.getLogger(DenialTracker.class);

    /** 连续拒绝阈值 —— 超过后建议回退 */
    public static final int MAX_CONSECUTIVE_DENIALS = 3;

    /** 总计拒绝阈值 —— 超过后建议回退 */
    public static final int MAX_TOTAL_DENIALS = 20;

    private int consecutiveDenials = 0;
    private int totalDenials = 0;

    /** 记录一次拒绝 */
    public void recordDenial() {
        consecutiveDenials++;
        totalDenials++;
        if (shouldFallbackToPrompting()) {
            log.warn("Denial threshold reached: {} consecutive, {} total — consider switching to manual mode",
                    consecutiveDenials, totalDenials);
        }
    }

    /** 记录一次成功（重置连续计数，但不重置总计） */
    public void recordSuccess() {
        consecutiveDenials = 0;
    }

    /**
     * 是否应回退到手动提示模式。
     * 当连续拒绝 >= 3 或总计拒绝 >= 20 时返回 true。
     */
    public boolean shouldFallbackToPrompting() {
        return consecutiveDenials >= MAX_CONSECUTIVE_DENIALS
                || totalDenials >= MAX_TOTAL_DENIALS;
    }

    /** 完全重置计数器 */
    public void reset() {
        consecutiveDenials = 0;
        totalDenials = 0;
    }

    public int getConsecutiveDenials() {
        return consecutiveDenials;
    }

    public int getTotalDenials() {
        return totalDenials;
    }
}
