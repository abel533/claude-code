package com.claudecode.tool.impl;

import com.claudecode.tool.Tool;
import com.claudecode.tool.ToolContext;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Config 工具 —— 获取或设置配置值。
 * <p>
 * 属于 P2 优先级的辅助工具。支持两种存储后端：
 * </p>
 * <ol>
 *   <li>首选从 ToolContext 中的配置映射（键 "CONFIG_STORE"）读写</li>
 *   <li>回退到 {@link System#getProperty} / {@link System#setProperty}</li>
 * </ol>
 *
 * <h3>参数</h3>
 * <ul>
 *   <li><b>action</b>（必填）—— "get" 或 "set"</li>
 *   <li><b>key</b>（必填）—— 配置键名</li>
 *   <li><b>value</b>（可选，set 时必填）—— 配置值</li>
 * </ul>
 *
 * <h3>返回</h3>
 * <p>JSON 格式：get 返回当前值，set 返回确认信息。</p>
 */
public class ConfigTool implements Tool {

    /** ToolContext 中配置存储的键名 */
    private static final String CONFIG_STORE_KEY = "CONFIG_STORE";

    @Override
    public String name() {
        return "Config";
    }

    @Override
    public String description() {
        return "Get or set configuration values";
    }

    @Override
    public String inputSchema() {
        return """
                {
                  "type": "object",
                  "properties": {
                    "action": {
                      "type": "string",
                      "description": "操作类型：get（获取）或 set（设置）",
                      "enum": ["get", "set"]
                    },
                    "key": {
                      "type": "string",
                      "description": "配置项的键名"
                    },
                    "value": {
                      "type": "string",
                      "description": "配置项的值（仅 set 操作时需要）"
                    }
                  },
                  "required": ["action", "key"]
                }""";
    }

    /**
     * Config 工具不是纯只读的（set 操作会修改状态），
     * 但出于安全考虑仍标记为 false。
     */
    @Override
    public boolean isReadOnly() {
        return false;
    }

    @Override
    public String execute(Map<String, Object> input, ToolContext context) {
        // 解析必填参数: action
        String action = (String) input.get("action");
        if (action == null || action.isBlank()) {
            return errorJson("参数 'action' 是必填项，可选值: get, set");
        }
        action = action.trim().toLowerCase();

        // 解析必填参数: key
        String key = (String) input.get("key");
        if (key == null || key.isBlank()) {
            return errorJson("参数 'key' 是必填项且不能为空");
        }

        // 获取或初始化配置存储
        @SuppressWarnings("unchecked")
        ConcurrentHashMap<String, String> configStore =
                context.getOrDefault(CONFIG_STORE_KEY, null);

        if (configStore == null) {
            configStore = new ConcurrentHashMap<>();
            context.set(CONFIG_STORE_KEY, configStore);
        }

        return switch (action) {
            case "get" -> executeGet(key, configStore);
            case "set" -> executeSet(key, input, configStore);
            default -> errorJson("无效的 action 值: '" + action + "'。可选值: get, set");
        };
    }

    @Override
    public String activityDescription(Map<String, Object> input) {
        String action = (String) input.getOrDefault("action", "?");
        String key = (String) input.getOrDefault("key", "?");
        if ("set".equalsIgnoreCase(action)) {
            return "⚙️ Setting config: " + key;
        }
        return "⚙️ Getting config: " + key;
    }

    /* ------------------------------------------------------------------ */
    /*  get / set 具体实现                                                 */
    /* ------------------------------------------------------------------ */

    /**
     * 执行 get 操作：优先从配置映射读取，回退到系统属性。
     *
     * @param key         配置键
     * @param configStore 配置映射
     * @return JSON 格式的结果
     */
    private String executeGet(String key, ConcurrentHashMap<String, String> configStore) {
        // 优先从上下文配置映射获取
        String value = configStore.get(key);

        // 回退到系统属性
        if (value == null) {
            value = System.getProperty(key);
        }

        if (value == null) {
            return """
                    {
                      "action": "get",
                      "key": "%s",
                      "value": null,
                      "found": false,
                      "message": "配置项 '%s' 未找到"
                    }""".formatted(escapeJson(key), escapeJson(key));
        }

        return """
                {
                  "action": "get",
                  "key": "%s",
                  "value": "%s",
                  "found": true
                }""".formatted(escapeJson(key), escapeJson(value));
    }

    /**
     * 执行 set 操作：同时写入配置映射和系统属性。
     *
     * @param key         配置键
     * @param input       输入参数映射
     * @param configStore 配置映射
     * @return JSON 格式的确认
     */
    private String executeSet(String key, Map<String, Object> input,
                              ConcurrentHashMap<String, String> configStore) {
        String value = (String) input.get("value");
        if (value == null) {
            return errorJson("set 操作需要提供 'value' 参数");
        }

        // 获取旧值（用于返回信息）
        String oldValue = configStore.get(key);
        if (oldValue == null) {
            oldValue = System.getProperty(key);
        }

        // 写入配置映射
        configStore.put(key, value);

        // 同步写入系统属性（简单实现，生产环境应使用专门的配置管理）
        try {
            System.setProperty(key, value);
        } catch (SecurityException e) {
            // 如果没有权限设置系统属性，只使用配置映射即可
        }

        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"action\": \"set\",\n");
        sb.append("  \"key\": \"").append(escapeJson(key)).append("\",\n");
        sb.append("  \"value\": \"").append(escapeJson(value)).append("\",\n");

        if (oldValue != null) {
            sb.append("  \"previous_value\": \"").append(escapeJson(oldValue)).append("\",\n");
        } else {
            sb.append("  \"previous_value\": null,\n");
        }

        sb.append("  \"success\": true,\n");
        sb.append("  \"message\": \"配置项 '").append(escapeJson(key)).append("' 已设置\"\n");
        sb.append("}");

        return sb.toString();
    }

    /* ------------------------------------------------------------------ */
    /*  辅助方法                                                           */
    /* ------------------------------------------------------------------ */

    /**
     * 转义 JSON 特殊字符。
     */
    private String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    /**
     * 构建错误 JSON 响应。
     */
    private String errorJson(String message) {
        return """
                {
                  "error": true,
                  "message": "%s"
                }""".formatted(escapeJson(message));
    }
}
