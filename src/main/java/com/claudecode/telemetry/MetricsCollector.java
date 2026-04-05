package com.claudecode.telemetry;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 基础指标收集器 —— 替代 claude-code 中的 Datadog/OpenTelemetry 全套。
 * <p>
 * 只收集本地指标，不上报到任何远程服务。
 * <p>
 * 收集的指标：
 * <ul>
 *   <li>会话时长</li>
 *   <li>工具使用次数（按工具名）</li>
 *   <li>命令使用次数（按命令名）</li>
 *   <li>API 调用次数和 token 用量</li>
 *   <li>错误次数（按类型）</li>
 *   <li>自动压缩次数</li>
 * </ul>
 * <p>
 * 存储位置: ~/.claude-code/metrics/{date}.json
 */
public class MetricsCollector {

    private static final Logger log = LoggerFactory.getLogger(MetricsCollector.class);
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    private final Path metricsDir;
    private final String sessionId;
    private final Instant sessionStart;

    // ==================== 计数器 ====================

    /** 工具使用次数: toolName → count */
    private final ConcurrentHashMap<String, AtomicLong> toolUsage = new ConcurrentHashMap<>();

    /** 命令使用次数: commandName → count */
    private final ConcurrentHashMap<String, AtomicLong> commandUsage = new ConcurrentHashMap<>();

    /** 错误次数: errorType → count */
    private final ConcurrentHashMap<String, AtomicLong> errorCounts = new ConcurrentHashMap<>();

    /** API 调用次数 */
    private final AtomicLong apiCallCount = new AtomicLong(0);

    /** 总 token 使用量 */
    private final AtomicLong totalInputTokens = new AtomicLong(0);
    private final AtomicLong totalOutputTokens = new AtomicLong(0);

    /** 自动压缩次数 */
    private final AtomicLong autoCompactCount = new AtomicLong(0);

    /** 用户消息数 */
    private final AtomicLong userMessageCount = new AtomicLong(0);

    /** 助手消息数 */
    private final AtomicLong assistantMessageCount = new AtomicLong(0);

    public MetricsCollector() {
        this(Path.of(System.getProperty("user.home"), ".claude-code", "metrics"),
                UUID.randomUUID().toString().substring(0, 8));
    }

    public MetricsCollector(Path metricsDir, String sessionId) {
        this.metricsDir = metricsDir;
        this.sessionId = sessionId;
        this.sessionStart = Instant.now();
    }

    // ==================== 记录方法 ====================

    public void recordToolUse(String toolName) {
        toolUsage.computeIfAbsent(toolName, k -> new AtomicLong(0)).incrementAndGet();
    }

    public void recordCommand(String commandName) {
        commandUsage.computeIfAbsent(commandName, k -> new AtomicLong(0)).incrementAndGet();
    }

    public void recordError(String errorType) {
        errorCounts.computeIfAbsent(errorType, k -> new AtomicLong(0)).incrementAndGet();
    }

    public void recordApiCall(long inputTokens, long outputTokens) {
        apiCallCount.incrementAndGet();
        totalInputTokens.addAndGet(inputTokens);
        totalOutputTokens.addAndGet(outputTokens);
    }

    public void recordAutoCompact() {
        autoCompactCount.incrementAndGet();
    }

    public void recordUserMessage() {
        userMessageCount.incrementAndGet();
    }

    public void recordAssistantMessage() {
        assistantMessageCount.incrementAndGet();
    }

    // ==================== 获取指标 ====================

    public long getSessionDurationSeconds() {
        return Instant.now().getEpochSecond() - sessionStart.getEpochSecond();
    }

    public Map<String, Long> getToolUsage() {
        Map<String, Long> result = new TreeMap<>();
        toolUsage.forEach((k, v) -> result.put(k, v.get()));
        return result;
    }

    public Map<String, Long> getCommandUsage() {
        Map<String, Long> result = new TreeMap<>();
        commandUsage.forEach((k, v) -> result.put(k, v.get()));
        return result;
    }

    public Map<String, Long> getErrorCounts() {
        Map<String, Long> result = new TreeMap<>();
        errorCounts.forEach((k, v) -> result.put(k, v.get()));
        return result;
    }

    // ==================== 持久化 ====================

    /**
     * 将当前会话指标保存到按日期分割的 JSON 文件。
     */
    public void flush() {
        try {
            Files.createDirectories(metricsDir);

            String date = LocalDate.now(ZoneId.systemDefault()).toString();
            Path file = metricsDir.resolve(date + ".json");

            // 读取已有数据
            List<Map<String, Object>> sessions = new ArrayList<>();
            if (Files.isRegularFile(file)) {
                try {
                    sessions = MAPPER.readValue(file.toFile(),
                            MAPPER.getTypeFactory().constructCollectionType(List.class, Map.class));
                } catch (Exception e) {
                    log.debug("Failed to read existing metrics file, starting fresh");
                }
            }

            // 追加当前会话
            sessions.add(toMap());

            // 写入
            MAPPER.writeValue(file.toFile(), sessions);
            log.debug("Metrics flushed to {}", file);
        } catch (IOException e) {
            log.error("Failed to flush metrics: {}", e.getMessage());
        }
    }

    /**
     * 将指标转为 Map（用于序列化）。
     */
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("session_id", sessionId);
        map.put("start_time", sessionStart.toString());
        map.put("duration_seconds", getSessionDurationSeconds());
        map.put("api_calls", apiCallCount.get());
        map.put("input_tokens", totalInputTokens.get());
        map.put("output_tokens", totalOutputTokens.get());
        map.put("user_messages", userMessageCount.get());
        map.put("assistant_messages", assistantMessageCount.get());
        map.put("auto_compacts", autoCompactCount.get());
        map.put("tool_usage", getToolUsage());
        map.put("command_usage", getCommandUsage());
        map.put("errors", getErrorCounts());
        return map;
    }

    /**
     * 获取指标摘要文本（用于 /doctor 或 /session 命令）。
     */
    public String summary() {
        StringBuilder sb = new StringBuilder();
        sb.append("Session: ").append(sessionId).append("\n");
        sb.append("Duration: ").append(formatDuration(getSessionDurationSeconds())).append("\n");
        sb.append("API Calls: ").append(apiCallCount.get()).append("\n");
        sb.append("Tokens: ").append(totalInputTokens.get()).append(" in / ")
                .append(totalOutputTokens.get()).append(" out\n");
        sb.append("Messages: ").append(userMessageCount.get()).append(" user / ")
                .append(assistantMessageCount.get()).append(" assistant\n");
        sb.append("Auto-compacts: ").append(autoCompactCount.get()).append("\n");

        if (!toolUsage.isEmpty()) {
            sb.append("Top tools: ");
            toolUsage.entrySet().stream()
                    .sorted(Map.Entry.comparingByValue(Comparator.comparingLong(AtomicLong::get).reversed()))
                    .limit(5)
                    .forEach(e -> sb.append(e.getKey()).append("(").append(e.getValue().get()).append(") "));
            sb.append("\n");
        }

        if (!errorCounts.isEmpty()) {
            sb.append("Errors: ");
            errorCounts.forEach((k, v) -> sb.append(k).append("(").append(v.get()).append(") "));
            sb.append("\n");
        }

        return sb.toString();
    }

    private static String formatDuration(long seconds) {
        if (seconds < 60) return seconds + "s";
        if (seconds < 3600) return (seconds / 60) + "m " + (seconds % 60) + "s";
        return (seconds / 3600) + "h " + ((seconds % 3600) / 60) + "m";
    }

    public String getSessionId() { return sessionId; }
    public Instant getSessionStart() { return sessionStart; }
}
