package com.claudecode.telemetry;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Feature Flag 服务 —— 对应 claude-code 中 GrowthBook 的本地替代。
 * <p>
 * 使用本地 JSON 文件管理 feature flags，支持：
 * <ul>
 *   <li>布尔型 flag（功能开关）</li>
 *   <li>字符串/数字型 flag（配置值）</li>
 *   <li>运行时重加载</li>
 *   <li>环境变量覆盖 (CLAUDE_CODE_FF_xxx)</li>
 * </ul>
 * <p>
 * 配置文件位置: ~/.claude-code/feature-flags.json
 */
public class FeatureFlagService {

    private static final Logger log = LoggerFactory.getLogger(FeatureFlagService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** flag 默认值 */
    private static final Map<String, Object> DEFAULTS = Map.ofEntries(
            Map.entry("DIRECT_CONNECT", true),
            Map.entry("WORKTREE_MODE", true),
            Map.entry("LSP_INTEGRATION", true),
            Map.entry("SESSION_MEMORY", true),
            Map.entry("COORDINATOR_MODE", true),
            Map.entry("PLUGIN_MARKETPLACE", false),
            Map.entry("ADVANCED_UI", false),
            Map.entry("VOICE_INPUT", false),
            Map.entry("AUTO_COMPACT", true),
            Map.entry("METRICS_COLLECTION", false)
    );

    private final Path configFile;
    private final ConcurrentHashMap<String, Object> flags = new ConcurrentHashMap<>();
    private long lastLoadTime = 0;

    public FeatureFlagService() {
        this(Path.of(System.getProperty("user.home"), ".claude-code", "feature-flags.json"));
    }

    public FeatureFlagService(Path configFile) {
        this.configFile = configFile;
        loadFlags();
    }

    /**
     * 获取布尔型 flag。
     */
    public boolean isEnabled(String flagName) {
        // 环境变量覆盖（最高优先级）
        String envKey = "CLAUDE_CODE_FF_" + flagName;
        String envVal = System.getenv(envKey);
        if (envVal != null) {
            return "true".equalsIgnoreCase(envVal) || "1".equals(envVal);
        }

        Object value = flags.get(flagName);
        if (value instanceof Boolean b) return b;
        if (value != null) return Boolean.parseBoolean(value.toString());

        // 默认值
        Object def = DEFAULTS.get(flagName);
        if (def instanceof Boolean b) return b;
        return false;
    }

    /**
     * 获取字符串型 flag。
     */
    public String getString(String flagName, String defaultValue) {
        String envKey = "CLAUDE_CODE_FF_" + flagName;
        String envVal = System.getenv(envKey);
        if (envVal != null) return envVal;

        Object value = flags.get(flagName);
        if (value != null) return value.toString();

        Object def = DEFAULTS.get(flagName);
        if (def != null) return def.toString();

        return defaultValue;
    }

    /**
     * 获取数字型 flag。
     */
    public long getNumber(String flagName, long defaultValue) {
        String envKey = "CLAUDE_CODE_FF_" + flagName;
        String envVal = System.getenv(envKey);
        if (envVal != null) {
            try {
                return Long.parseLong(envVal);
            } catch (NumberFormatException ignored) {}
        }

        Object value = flags.get(flagName);
        if (value instanceof Number n) return n.longValue();
        if (value != null) {
            try {
                return Long.parseLong(value.toString());
            } catch (NumberFormatException ignored) {}
        }

        Object def = DEFAULTS.get(flagName);
        if (def instanceof Number n) return n.longValue();

        return defaultValue;
    }

    /**
     * 设置 flag 值（运行时）。
     */
    public void setFlag(String flagName, Object value) {
        flags.put(flagName, value);
    }

    /**
     * 获取所有 flag 及其当前值。
     */
    public Map<String, Object> getAllFlags() {
        Map<String, Object> result = new ConcurrentHashMap<>(DEFAULTS);
        result.putAll(flags);
        return result;
    }

    /**
     * 从配置文件加载 flag。
     */
    public void loadFlags() {
        if (!Files.isRegularFile(configFile)) {
            log.debug("Feature flag config not found: {}", configFile);
            return;
        }

        try {
            Map<String, Object> loaded = MAPPER.readValue(
                    configFile.toFile(), new TypeReference<>() {});
            flags.putAll(loaded);
            lastLoadTime = System.currentTimeMillis();
            log.info("Loaded {} feature flags from {}", loaded.size(), configFile);
        } catch (IOException e) {
            log.warn("Failed to load feature flags: {}", e.getMessage());
        }
    }

    /**
     * 保存当前 flag 到配置文件。
     */
    public void saveFlags() {
        try {
            Files.createDirectories(configFile.getParent());
            MAPPER.writerWithDefaultPrettyPrinter()
                    .writeValue(configFile.toFile(), flags);
            log.info("Saved {} feature flags to {}", flags.size(), configFile);
        } catch (IOException e) {
            log.error("Failed to save feature flags: {}", e.getMessage());
        }
    }

    /**
     * 重新加载（如果文件有变化）。
     */
    public void reloadIfChanged() {
        try {
            if (!Files.isRegularFile(configFile)) return;
            long mtime = Files.getLastModifiedTime(configFile).toMillis();
            if (mtime > lastLoadTime) {
                loadFlags();
            }
        } catch (IOException e) {
            log.debug("Failed to check flag file mtime: {}", e.getMessage());
        }
    }
}
