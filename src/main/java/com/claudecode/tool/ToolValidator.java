package com.claudecode.tool;

import java.nio.file.Path;

/**
 * 工具输入验证器 —— 消除 Tool 实现中的重复验证代码。
 * <p>
 * 所有 validate* 方法：返回 null 表示验证通过，返回 String 表示错误消息。
 * 典型用法：
 * <pre>
 *   String err = ToolValidator.requireString(input, "file_path");
 *   if (err != null) return err;
 * </pre>
 */
public final class ToolValidator {

    private ToolValidator() {}

    /**
     * 验证必填 String 参数（非 null、非空白）。
     * @return null 表示通过，否则返回错误消息
     */
    public static String requireString(java.util.Map<String, Object> input, String paramName) {
        Object value = input.get(paramName);
        if (value == null) {
            return "Error: '" + paramName + "' is required.";
        }
        if (value instanceof String s && s.isBlank()) {
            return "Error: '" + paramName + "' must not be empty.";
        }
        return null;
    }

    /**
     * 获取 String 参数（带默认值）。
     */
    public static String getString(java.util.Map<String, Object> input, String paramName, String defaultValue) {
        Object value = input.get(paramName);
        if (value instanceof String s && !s.isBlank()) return s;
        return defaultValue;
    }

    /**
     * 安全获取 int 参数（带默认值，防止 ClassCastException）。
     */
    public static int getInt(java.util.Map<String, Object> input, String paramName, int defaultValue) {
        Object value = input.get(paramName);
        if (value instanceof Number n) return n.intValue();
        if (value instanceof String s) {
            try { return Integer.parseInt(s); } catch (NumberFormatException e) { /* fall through */ }
        }
        return defaultValue;
    }

    /**
     * 安全获取 boolean 参数（带默认值）。
     */
    public static boolean getBoolean(java.util.Map<String, Object> input, String paramName, boolean defaultValue) {
        Object value = input.get(paramName);
        if (value instanceof Boolean b) return b;
        if (value instanceof String s) return Boolean.parseBoolean(s);
        return defaultValue;
    }

    /**
     * 验证文件路径在工作目录内（防止路径遍历）。
     * @return null 表示通过，否则返回错误消息
     */
    public static String validatePathInWorkDir(String filePath, Path workDir) {
        if (filePath == null || filePath.isBlank()) {
            return "Error: file path is required.";
        }
        Path resolved = workDir.resolve(filePath).normalize();
        if (!resolved.startsWith(workDir.normalize())) {
            return "Error: Path traversal not allowed. Path must be within the working directory.";
        }
        return null;
    }

    /**
     * 解析并验证路径（合并 resolve + normalize + traversal check）。
     * @return 解析后的路径，如验证失败则为 null（错误通过 errorHolder 返回）
     */
    public static Path resolveSafePath(String filePath, Path workDir) {
        if (filePath == null || filePath.isBlank()) return null;
        Path resolved = workDir.resolve(filePath).normalize();
        if (!resolved.startsWith(workDir.normalize())) return null;
        return resolved;
    }
}
