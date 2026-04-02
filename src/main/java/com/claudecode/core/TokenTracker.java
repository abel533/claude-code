package com.claudecode.core;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Token 使用量追踪器 —— 记录 API 调用的 token 消耗并监控上下文窗口。
 * <p>
 * 从 ChatResponse 的 usage 元数据中提取 token 统计信息，
 * 支持按会话累计、费用估算和上下文窗口阈值监控。
 */
public class TokenTracker {

    // ── 上下文窗口阈值常量 ──
    /** 自动压缩触发百分比（有效窗口的 93%） */
    public static final double AUTO_COMPACT_THRESHOLD_PCT = 0.93;
    /** 警告阈值百分比（82%） */
    public static final double WARNING_THRESHOLD_PCT = 0.82;
    /** 阻塞阈值百分比（98%，必须压缩才能继续） */
    public static final double BLOCKING_THRESHOLD_PCT = 0.98;
    /** 自动压缩缓冲 token 数 */
    public static final long AUTO_COMPACT_BUFFER_TOKENS = 13_000;
    /** 手动压缩缓冲 token 数 */
    public static final long MANUAL_COMPACT_BUFFER_TOKENS = 3_000;

    /** 上下文窗口警告状态 */
    public enum TokenWarningState {
        NORMAL,   // 正常（绿色）
        WARNING,  // 接近阈值（黄色）
        ERROR,    // 达到压缩阈值（红色）
        BLOCKING  // 必须压缩才能继续（闪烁红）
    }

    private final AtomicLong totalInputTokens = new AtomicLong(0);
    private final AtomicLong totalOutputTokens = new AtomicLong(0);
    private final AtomicLong totalCacheReadTokens = new AtomicLong(0);
    private final AtomicLong totalCacheCreationTokens = new AtomicLong(0);
    private final AtomicLong apiCallCount = new AtomicLong(0);

    /** 最近一次 API 调用报告的 prompt token 数（近似当前上下文大小） */
    private final AtomicLong lastPromptTokens = new AtomicLong(0);

    /** 模型定价（每百万 token 的美元价格） */
    private double inputPricePerMillion = 3.0;   // Claude Sonnet 4 input
    private double outputPricePerMillion = 15.0;  // Claude Sonnet 4 output
    private double cacheReadPricePerMillion = 0.3; // 缓存读取
    private String modelName = "claude-sonnet-4-20250514";

    /** 上下文窗口总大小（token） */
    private long contextWindowSize;
    /** 预留给输出的 token 数 */
    private long reservedTokens = 20_000;

    public TokenTracker() {
        // 支持环境变量覆盖上下文窗口大小
        String envWindow = System.getenv("CLAUDE_CODE_CONTEXT_WINDOW");
        if (envWindow != null && !envWindow.isBlank()) {
            try {
                this.contextWindowSize = Long.parseLong(envWindow.trim());
            } catch (NumberFormatException e) {
                this.contextWindowSize = 200_000; // 默认 200K
            }
        } else {
            this.contextWindowSize = 200_000;
        }
    }

    /** 记录一次 API 调用的 token 使用 */
    public void recordUsage(long inputTokens, long outputTokens) {
        totalInputTokens.addAndGet(inputTokens);
        totalOutputTokens.addAndGet(outputTokens);
        lastPromptTokens.set(inputTokens);
        apiCallCount.incrementAndGet();
    }

    /** 记录一次包含缓存的 API 调用 */
    public void recordUsage(long inputTokens, long outputTokens, long cacheRead, long cacheCreation) {
        totalInputTokens.addAndGet(inputTokens);
        totalOutputTokens.addAndGet(outputTokens);
        totalCacheReadTokens.addAndGet(cacheRead);
        totalCacheCreationTokens.addAndGet(cacheCreation);
        lastPromptTokens.set(inputTokens);
        apiCallCount.incrementAndGet();
    }

    /** 设置模型和对应定价 */
    public void setModel(String model) {
        this.modelName = model;
        // 根据模型设置定价
        if (model.contains("opus")) {
            inputPricePerMillion = 15.0;
            outputPricePerMillion = 75.0;
            cacheReadPricePerMillion = 1.5;
        } else if (model.contains("sonnet")) {
            inputPricePerMillion = 3.0;
            outputPricePerMillion = 15.0;
            cacheReadPricePerMillion = 0.3;
        } else if (model.contains("haiku")) {
            inputPricePerMillion = 0.25;
            outputPricePerMillion = 1.25;
            cacheReadPricePerMillion = 0.03;
        }
    }

    public long getInputTokens() { return totalInputTokens.get(); }
    public long getOutputTokens() { return totalOutputTokens.get(); }
    public long getCacheReadTokens() { return totalCacheReadTokens.get(); }
    public long getCacheCreationTokens() { return totalCacheCreationTokens.get(); }
    public long getTotalTokens() { return totalInputTokens.get() + totalOutputTokens.get(); }
    public long getApiCallCount() { return apiCallCount.get(); }
    public String getModelName() { return modelName; }

    /** 估算当前会话费用（美元） */
    public double estimateCost() {
        double inputCost = totalInputTokens.get() * inputPricePerMillion / 1_000_000.0;
        double outputCost = totalOutputTokens.get() * outputPricePerMillion / 1_000_000.0;
        double cacheCost = totalCacheReadTokens.get() * cacheReadPricePerMillion / 1_000_000.0;
        return inputCost + outputCost + cacheCost;
    }

    // ── 上下文窗口监控 ──

    /** 有效上下文窗口大小（总窗口 - 预留输出） */
    public long getEffectiveWindow() {
        return contextWindowSize - reservedTokens;
    }

    /** 最近一次 prompt 的 token 数（近似当前上下文大小） */
    public long getLastPromptTokens() {
        return lastPromptTokens.get();
    }

    /** 当前上下文使用百分比 */
    public double getUsagePercentage() {
        long effective = getEffectiveWindow();
        if (effective <= 0) return 0;
        return (double) lastPromptTokens.get() / effective;
    }

    /** 是否应触发自动压缩 */
    public boolean shouldAutoCompact() {
        return getUsagePercentage() >= AUTO_COMPACT_THRESHOLD_PCT;
    }

    /** 是否已达到阻塞阈值（必须压缩才能继续） */
    public boolean isBlocking() {
        return getUsagePercentage() >= BLOCKING_THRESHOLD_PCT;
    }

    /** 获取自动压缩触发的 token 阈值 */
    public long getAutoCompactThreshold() {
        return (long) (getEffectiveWindow() * AUTO_COMPACT_THRESHOLD_PCT);
    }

    /** 获取当前 token 警告状态 */
    public TokenWarningState getTokenWarningState() {
        double pct = getUsagePercentage();
        if (pct >= BLOCKING_THRESHOLD_PCT) return TokenWarningState.BLOCKING;
        if (pct >= AUTO_COMPACT_THRESHOLD_PCT) return TokenWarningState.ERROR;
        if (pct >= WARNING_THRESHOLD_PCT) return TokenWarningState.WARNING;
        return TokenWarningState.NORMAL;
    }

    public long getContextWindowSize() { return contextWindowSize; }

    public void setContextWindowSize(long size) { this.contextWindowSize = size; }

    public long getReservedTokens() { return reservedTokens; }

    public void setReservedTokens(long reserved) { this.reservedTokens = reserved; }

    /** 重置统计 */
    public void reset() {
        totalInputTokens.set(0);
        totalOutputTokens.set(0);
        totalCacheReadTokens.set(0);
        totalCacheCreationTokens.set(0);
        lastPromptTokens.set(0);
        apiCallCount.set(0);
    }

    /** 格式化 token 数量（带千位分隔） */
    public static String formatTokens(long tokens) {
        if (tokens < 1000) return String.valueOf(tokens);
        if (tokens < 1_000_000) return String.format("%.1fK", tokens / 1000.0);
        return String.format("%.2fM", tokens / 1_000_000.0);
    }
}
