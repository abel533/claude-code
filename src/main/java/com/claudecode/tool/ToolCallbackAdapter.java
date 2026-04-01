package com.claudecode.tool;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.Map;

/**
 * Tool → Spring AI ToolCallback 适配器。
 * <p>
 * 将自定义 Tool 协议适配为 Spring AI 的 ToolCallback 接口，
 * 在调用时处理 JSON 解析、权限检查和异常捕获。
 * <p>
 * 对应 claude-code-learn 中的 AgentToolCallback。
 */
public class ToolCallbackAdapter implements ToolCallback {

    private static final Logger log = LoggerFactory.getLogger(ToolCallbackAdapter.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Tool tool;
    private final ToolDefinition toolDefinition;
    private final ToolContext context;

    public ToolCallbackAdapter(Tool tool, ToolContext context) {
        this.tool = tool;
        this.context = context;
        this.toolDefinition = DefaultToolDefinition.builder()
                .name(tool.name())
                .description(tool.description())
                .inputSchema(tool.inputSchema())
                .build();
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return toolDefinition;
    }

    @Override
    @SuppressWarnings("unchecked")
    public String call(String jsonInput) {
        try {
            Map<String, Object> input = MAPPER.readValue(jsonInput, Map.class);

            // 权限前置检查
            PermissionResult perm = tool.checkPermission(input, context);
            if (!perm.allowed()) {
                log.warn("[{}] 权限拒绝: {}", tool.name(), perm.message());
                return "Permission denied: " + perm.message();
            }

            log.debug("[{}] {}", tool.name(), tool.activityDescription(input));
            return tool.execute(input, context);
        } catch (JsonProcessingException e) {
            log.warn("[{}] JSON 解析失败: {}", tool.name(), e.getMessage());
            return "Error: Invalid JSON input: " + e.getMessage();
        } catch (Exception e) {
            log.warn("[{}] 执行异常: {}", tool.name(), e.getMessage());
            return "Error: " + e.getMessage();
        }
    }

    public Tool getTool() {
        return tool;
    }
}
