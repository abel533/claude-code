package com.claudecode.core;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Token 使用量追踪器 —— 记录 API 调用的 token 消耗。
 * <p>
 * 从 ChatResponse 的 usage 元数据中提取 token 统计信息，
 * 支持按会话累计和费用估算。
 */
public class TokenTracker {

    private final AtomicLong totalInputTokens = new AtomicLong(0);
    private final AtomicLong totalOutputTokens = new AtomicLong(0);
    private final AtomicLong totalCacheReadTokens = new AtomicLong(0);
    private final AtomicLong totalCacheCreationTokens = new AtomicLong(0);
    private final AtomicLong apiCallCount = new AtomicLong(0);

    /** 模型定价（每百万 token 的美元价格） */
    private double inputPricePerMillion = 3.0;   // Claude Sonnet 4 input
    private double outputPricePerMillion = 15.0;  // Claude Sonnet 4 output
    private double cacheReadPricePerMillion = 0.3; // 缓存读取
    private String modelName = "claude-sonnet-4-20250514";

    /** 记录一次 API 调用的 token 使用 */
    public void recordUsage(long inputTokens, long outputTokens) {
        totalInputTokens.addAndGet(inputTokens);
        totalOutputTokens.addAndGet(outputTokens);
        apiCallCount.incrementAndGet();
    }

    /** 记录一次包含缓存的 API 调用 */
    public void recordUsage(long inputTokens, long outputTokens, long cacheRead, long cacheCreation) {
        totalInputTokens.addAndGet(inputTokens);
        totalOutputTokens.addAndGet(outputTokens);
        totalCacheReadTokens.addAndGet(cacheRead);
        totalCacheCreationTokens.addAndGet(cacheCreation);
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

    /** 重置统计 */
    public void reset() {
        totalInputTokens.set(0);
        totalOutputTokens.set(0);
        totalCacheReadTokens.set(0);
        totalCacheCreationTokens.set(0);
        apiCallCount.set(0);
    }

    /** 格式化 token 数量（带千位分隔） */
    public static String formatTokens(long tokens) {
        if (tokens < 1000) return String.valueOf(tokens);
        if (tokens < 1_000_000) return String.format("%.1fK", tokens / 1000.0);
        return String.format("%.2fM", tokens / 1_000_000.0);
    }
}
