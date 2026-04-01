package com.claudecode.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Hook 系统 —— 对应 claude-code/src/hooks/ 模块。
 * <p>
 * 提供工具调用前后的钩子机制，允许用户通过配置文件
 * 或编程方式注册拦截器，在工具执行的各个阶段介入。
 * <p>
 * 支持的 Hook 类型：
 * <ul>
 *   <li>{@link HookType#PRE_TOOL_USE} —— 工具执行前，可修改参数或阻止执行</li>
 *   <li>{@link HookType#POST_TOOL_USE} —— 工具执行后，可修改结果或触发后续操作</li>
 *   <li>{@link HookType#PRE_PROMPT} —— 发送 prompt 前，可修改消息内容</li>
 *   <li>{@link HookType#POST_RESPONSE} —— 收到响应后，可进行后处理</li>
 * </ul>
 */
public class HookManager {

    private static final Logger log = LoggerFactory.getLogger(HookManager.class);

    /** 所有已注册的 Hook 列表（线程安全） */
    private final List<HookRegistration> hooks = new CopyOnWriteArrayList<>();

    /**
     * 注册一个 Hook。
     *
     * @param type    Hook 类型
     * @param name    Hook 名称（用于日志/调试）
     * @param handler Hook 处理器
     */
    public void register(HookType type, String name, HookHandler handler) {
        hooks.add(new HookRegistration(type, name, handler, 0));
        log.debug("Registered Hook: {} [{}]", name, type);
    }

    /**
     * 注册一个带优先级的 Hook（数字越小优先级越高）。
     */
    public void register(HookType type, String name, HookHandler handler, int priority) {
        hooks.add(new HookRegistration(type, name, handler, priority));
        log.debug("Registered Hook: {} [{}] priority={}", name, type, priority);
    }

    /**
     * 执行指定类型的所有 Hook。
     * <p>
     * Hook 按优先级顺序执行。如果任一 Hook 返回 {@link HookResult#ABORT}，
     * 后续 Hook 将不再执行，并返回 ABORT 结果。
     *
     * @param type    Hook 类型
     * @param context Hook 执行上下文
     * @return 聚合的 Hook 结果
     */
    public HookResult execute(HookType type, HookContext context) {
        List<HookRegistration> matching = hooks.stream()
                .filter(h -> h.type() == type)
                .sorted((a, b) -> Integer.compare(a.priority(), b.priority()))
                .toList();

        if (matching.isEmpty()) {
            return HookResult.CONTINUE;
        }

        for (HookRegistration reg : matching) {
            try {
                log.debug("Executing Hook: {} [{}]", reg.name(), type);
                HookResult result = reg.handler().handle(context);

                if (result == HookResult.ABORT) {
                    log.info("Hook [{}] aborted the operation", reg.name());
                    return HookResult.ABORT;
                }
            } catch (Exception e) {
                log.warn("Hook [{}] execution exception: {}", reg.name(), e.getMessage());
                // Hook 异常不影响主流程
            }
        }

        return HookResult.CONTINUE;
    }

    /** 移除指定名称的 Hook */
    public void unregister(String name) {
        hooks.removeIf(h -> h.name().equals(name));
    }

    /** 获取所有已注册的 Hook */
    public List<HookRegistration> getHooks() {
        return Collections.unmodifiableList(hooks);
    }

    /** 清除所有 Hook */
    public void clear() {
        hooks.clear();
    }

    // ==================== 内部类型 ====================

    /** Hook 类型 */
    public enum HookType {
        /** 工具执行前 —— 可阻止执行或修改参数 */
        PRE_TOOL_USE,
        /** 工具执行后 —— 可修改结果 */
        POST_TOOL_USE,
        /** 发送 prompt 前 */
        PRE_PROMPT,
        /** 收到响应后 */
        POST_RESPONSE
    }

    /** Hook 执行结果 */
    public enum HookResult {
        /** 继续执行 */
        CONTINUE,
        /** 中止操作 */
        ABORT
    }

    /** Hook 处理器接口 */
    @FunctionalInterface
    public interface HookHandler {
        HookResult handle(HookContext context);
    }

    /** Hook 执行上下文 —— 携带当前操作的相关信息 */
    public static class HookContext {
        private final String toolName;
        private final Map<String, Object> arguments;
        private String result;
        private final Map<String, Object> metadata;

        public HookContext(String toolName, Map<String, Object> arguments) {
            this.toolName = toolName;
            this.arguments = arguments != null ? arguments : Map.of();
            this.metadata = new java.util.HashMap<>();
        }

        public String getToolName() { return toolName; }
        public Map<String, Object> getArguments() { return arguments; }
        public String getResult() { return result; }
        public void setResult(String result) { this.result = result; }

        /** 自定义元数据 */
        public void put(String key, Object value) { metadata.put(key, value); }
        @SuppressWarnings("unchecked")
        public <T> T get(String key) { return (T) metadata.get(key); }
    }

    /** Hook 注册记录 */
    public record HookRegistration(HookType type, String name, HookHandler handler, int priority) {}
}
