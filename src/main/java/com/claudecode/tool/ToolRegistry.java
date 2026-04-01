package com.claudecode.tool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;

import java.util.*;

/**
 * 工具注册中心 —— 对应 claude-code/src/tools.ts 中的工具集合管理。
 * <p>
 * 管理 Tool 的注册、查找和到 Spring AI ToolCallback 的转换。
 */
public class ToolRegistry {

    private static final Logger log = LoggerFactory.getLogger(ToolRegistry.class);

    private final Map<String, Tool> tools = new LinkedHashMap<>();

    /**
     * 注册工具。若工具 isEnabled() 返回 false 则跳过。
     */
    public void register(Tool tool) {
        if (!tool.isEnabled()) {
            log.debug("工具 [{}] 未启用，跳过注册", tool.name());
            return;
        }
        if (tools.containsKey(tool.name())) {
            log.warn("工具 [{}] 已注册，将被覆盖", tool.name());
        }
        tools.put(tool.name(), tool);
        log.debug("注册工具: [{}]", tool.name());
    }

    /** 批量注册 */
    public void registerAll(Tool... toolArray) {
        for (Tool t : toolArray) {
            register(t);
        }
    }

    /** 按名称查找 */
    public Optional<Tool> findByName(String name) {
        return Optional.ofNullable(tools.get(name));
    }

    /** 获取所有已注册工具 */
    public List<Tool> getTools() {
        return List.copyOf(tools.values());
    }

    /** 获取所有工具名称 */
    public Set<String> getToolNames() {
        return Set.copyOf(tools.keySet());
    }

    /** 转换为 Spring AI ToolCallback 列表 */
    public List<ToolCallback> toCallbacks(ToolContext context) {
        List<ToolCallback> callbacks = new ArrayList<>();
        for (Tool tool : tools.values()) {
            callbacks.add(new ToolCallbackAdapter(tool, context));
        }
        return callbacks;
    }

    public int size() {
        return tools.size();
    }
}
