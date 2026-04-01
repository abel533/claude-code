package com.claudecode.tool;

import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 工具执行上下文 —— 对应 claude-code 中 ToolUseContext。
 * <p>
 * 提供工具执行时所需的环境信息和共享状态。
 */
public class ToolContext {

    private final Path workDir;
    private final String model;
    private final ConcurrentHashMap<String, Object> state;

    public ToolContext(Path workDir, String model) {
        this.workDir = workDir;
        this.model = model;
        this.state = new ConcurrentHashMap<>();
    }

    public static ToolContext defaultContext() {
        return new ToolContext(Path.of(System.getProperty("user.dir")), "claude-sonnet-4-20250514");
    }

    public Path getWorkDir() {
        return workDir;
    }

    public String getModel() {
        return model;
    }

    /** 获取共享状态值 */
    @SuppressWarnings("unchecked")
    public <T> T get(String key) {
        return (T) state.get(key);
    }

    /** 设置共享状态值 */
    public void set(String key, Object value) {
        state.put(key, value);
    }

    @SuppressWarnings("unchecked")
    public <T> T getOrDefault(String key, T defaultValue) {
        return (T) state.getOrDefault(key, defaultValue);
    }

    public boolean has(String key) {
        return state.containsKey(key);
    }
}
