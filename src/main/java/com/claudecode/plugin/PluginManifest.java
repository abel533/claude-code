package com.claudecode.plugin;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

/**
 * 插件清单 —— 对应 claude-code 中 manifest.json 的 Java 映射。
 * <p>
 * 每个可安装的插件必须包含一个 manifest.json，声明其元数据和能力。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PluginManifest(
        /** 插件唯一 ID (kebab-case) */
        String id,
        /** 显示名称 */
        String name,
        /** 版本号 (semver) */
        String version,
        /** 功能描述 */
        String description,
        /** 作者 */
        String author,
        /** 主页 URL */
        String homepage,
        /** 许可证 */
        String license,
        /** 最低 claude-code-java 版本 */
        String minAppVersion,
        /** 入口类（JAR 中实现 Plugin 接口的类） */
        String mainClass,
        /** 声明的工具 */
        List<DeclaredTool> tools,
        /** 声明的命令 */
        List<DeclaredCommand> commands,
        /** 声明的 Hook */
        List<DeclaredHook> hooks,
        /** 安装作用域支持 */
        List<String> scopes,
        /** 依赖的其他插件 ID */
        List<String> dependencies,
        /** 额外元数据 */
        Map<String, Object> metadata
) {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 从 JSON 字符串解析 */
    public static PluginManifest fromJson(String json) throws Exception {
        return MAPPER.readValue(json, PluginManifest.class);
    }

    /** 从 JSON bytes 解析 */
    public static PluginManifest fromJson(byte[] json) throws Exception {
        return MAPPER.readValue(json, PluginManifest.class);
    }

    /** 序列化为 JSON */
    public String toJson() throws Exception {
        return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(this);
    }

    /** 校验必填字段 */
    public List<String> validate() {
        var errors = new java.util.ArrayList<String>();
        if (id == null || id.isBlank()) errors.add("id is required");
        if (name == null || name.isBlank()) errors.add("name is required");
        if (version == null || version.isBlank()) errors.add("version is required");
        if (mainClass == null || mainClass.isBlank()) errors.add("mainClass is required");

        if (id != null && !id.matches("^[a-z][a-z0-9-]*$")) {
            errors.add("id must be kebab-case: " + id);
        }
        if (version != null && !version.matches("^\\d+\\.\\d+\\.\\d+.*$")) {
            errors.add("version must be semver: " + version);
        }
        return errors;
    }

    /** 是否支持指定作用域 */
    public boolean supportsScope(String scope) {
        return scopes == null || scopes.isEmpty() || scopes.contains(scope);
    }

    // ---- 子记录 ----

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DeclaredTool(String name, String description, boolean readOnly) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DeclaredCommand(String name, String description) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DeclaredHook(String event, String handler) {}

    /**
     * 市场目录条目 —— 市场 API 返回的精简信息。
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record MarketplaceEntry(
            String id,
            String name,
            String version,
            String description,
            String author,
            String downloadUrl,
            long downloads,
            double rating,
            String updatedAt,
            List<String> tags
    ) {
        public static List<MarketplaceEntry> fromJsonArray(String json) throws Exception {
            return MAPPER.readValue(json, new TypeReference<>() {});
        }
    }
}
