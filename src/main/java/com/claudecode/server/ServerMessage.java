package com.claudecode.server;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Server Mode 消息协议 —— 对应 claude-code/src/server/types.ts
 * <p>
 * WebSocket 上的 JSON 消息，7 种类型：
 * <ul>
 *   <li><b>user</b> — 客户端发送的用户消息</li>
 *   <li><b>assistant</b> — 服务端回复的助手消息</li>
 *   <li><b>result</b> — 轮次结束的最终结果</li>
 *   <li><b>control_request</b> — 权限请求（服务端→客户端）</li>
 *   <li><b>control_response</b> — 权限回复（客户端→服务端）</li>
 *   <li><b>interrupt</b> — 中断信号</li>
 *   <li><b>keep_alive</b> — 心跳</li>
 * </ul>
 */
public class ServerMessage {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .setSerializationInclusion(JsonInclude.Include.NON_NULL);

    // ==================== 消息类型常量 ====================

    public static final String TYPE_USER = "user";
    public static final String TYPE_ASSISTANT = "assistant";
    public static final String TYPE_RESULT = "result";
    public static final String TYPE_CONTROL_REQUEST = "control_request";
    public static final String TYPE_CONTROL_RESPONSE = "control_response";
    public static final String TYPE_INTERRUPT = "interrupt";
    public static final String TYPE_KEEP_ALIVE = "keep_alive";
    public static final String TYPE_SYSTEM = "system";
    public static final String TYPE_ERROR = "error";
    public static final String TYPE_TOOL_USE = "tool_use";

    // ==================== 通用消息 ====================

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Envelope(
            String type,
            @JsonProperty("session_id") String sessionId,
            Object payload
    ) {
        public String toJson() throws JsonProcessingException {
            return MAPPER.writeValueAsString(this);
        }

        public static Envelope fromJson(String json) throws JsonProcessingException {
            return MAPPER.readValue(json, Envelope.class);
        }
    }

    // ==================== 客户端→服务端 ====================

    /** 用户消息 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record UserMessage(
            String content,
            @JsonProperty("parent_tool_use_id") String parentToolUseId
    ) {}

    /** 权限回复 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ControlResponse(
            @JsonProperty("request_id") String requestId,
            String behavior,  // "allow" or "deny"
            String message,
            @JsonProperty("updated_input") Map<String, Object> updatedInput
    ) {}

    // ==================== 服务端→客户端 ====================

    /** 助手消息（流式或完整） */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AssistantPayload(
            String text,
            String uuid,
            boolean streaming,
            @JsonProperty("tool_calls") List<ToolCallInfo> toolCalls
    ) {}

    /** 工具调用信息 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ToolCallInfo(
            String id,
            String name,
            @JsonProperty("arguments") String arguments,
            String result,
            String status  // "running", "completed", "error"
    ) {}

    /** 轮次结果 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ResultPayload(
            String text,
            @JsonProperty("tool_calls_count") int toolCallsCount,
            @JsonProperty("prompt_tokens") long promptTokens,
            @JsonProperty("completion_tokens") long completionTokens
    ) {}

    /** 权限请求 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ControlRequest(
            @JsonProperty("request_id") String requestId,
            String subtype,  // "can_use_tool"
            @JsonProperty("tool_name") String toolName,
            @JsonProperty("tool_input") String toolInput,
            @JsonProperty("activity_description") String activityDescription
    ) {}

    /** 系统事件 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SystemEvent(
            String subtype,  // "init", "session_ready", "error"
            String model,
            String message,
            @JsonProperty("session_id") String sessionId
    ) {}

    /** 错误 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ErrorPayload(
            String code,
            String message
    ) {}

    // ==================== 工厂方法 ====================

    public static String userMessage(String sessionId, String content) throws JsonProcessingException {
        var envelope = new Envelope(TYPE_USER, sessionId, new UserMessage(content, null));
        return envelope.toJson();
    }

    public static String assistantMessage(String sessionId, String text, boolean streaming) throws JsonProcessingException {
        var payload = new AssistantPayload(text, UUID.randomUUID().toString(), streaming, null);
        var envelope = new Envelope(TYPE_ASSISTANT, sessionId, payload);
        return envelope.toJson();
    }

    public static String assistantToolUse(String sessionId, List<ToolCallInfo> toolCalls) throws JsonProcessingException {
        var payload = new AssistantPayload(null, UUID.randomUUID().toString(), false, toolCalls);
        var envelope = new Envelope(TYPE_TOOL_USE, sessionId, payload);
        return envelope.toJson();
    }

    public static String resultMessage(String sessionId, String text, int toolCallsCount,
                                       long promptTokens, long completionTokens) throws JsonProcessingException {
        var payload = new ResultPayload(text, toolCallsCount, promptTokens, completionTokens);
        var envelope = new Envelope(TYPE_RESULT, sessionId, payload);
        return envelope.toJson();
    }

    public static String controlRequest(String sessionId, String toolName, String toolInput,
                                        String activityDescription) throws JsonProcessingException {
        var payload = new ControlRequest(
                UUID.randomUUID().toString(), "can_use_tool",
                toolName, toolInput, activityDescription);
        var envelope = new Envelope(TYPE_CONTROL_REQUEST, sessionId, payload);
        return envelope.toJson();
    }

    public static String controlResponse(String sessionId, String requestId, String behavior,
                                         String message) throws JsonProcessingException {
        var payload = new ControlResponse(requestId, behavior, message, null);
        var envelope = new Envelope(TYPE_CONTROL_RESPONSE, sessionId, payload);
        return envelope.toJson();
    }

    public static String interrupt(String sessionId) throws JsonProcessingException {
        var envelope = new Envelope(TYPE_INTERRUPT, sessionId, null);
        return envelope.toJson();
    }

    public static String keepAlive(String sessionId) throws JsonProcessingException {
        var envelope = new Envelope(TYPE_KEEP_ALIVE, sessionId, null);
        return envelope.toJson();
    }

    public static String systemEvent(String sessionId, String subtype, String model, String message) throws JsonProcessingException {
        var payload = new SystemEvent(subtype, model, message, sessionId);
        var envelope = new Envelope(TYPE_SYSTEM, sessionId, payload);
        return envelope.toJson();
    }

    public static String error(String sessionId, String code, String message) throws JsonProcessingException {
        var payload = new ErrorPayload(code, message);
        var envelope = new Envelope(TYPE_ERROR, sessionId, payload);
        return envelope.toJson();
    }

    // ==================== 解析工具 ====================

    public static String getType(String json) throws JsonProcessingException {
        JsonNode node = MAPPER.readTree(json);
        return node.has("type") ? node.get("type").asText() : null;
    }

    public static JsonNode getPayload(String json) throws JsonProcessingException {
        JsonNode node = MAPPER.readTree(json);
        return node.get("payload");
    }

    public static String getSessionId(String json) throws JsonProcessingException {
        JsonNode node = MAPPER.readTree(json);
        return node.has("session_id") ? node.get("session_id").asText() : null;
    }

    public static ObjectMapper mapper() {
        return MAPPER;
    }
}
