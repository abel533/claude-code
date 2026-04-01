package com.claudecode.tool;

/**
 * 工具权限检查结果。
 * <p>
 * 对应 claude-code 中 Tool.checkPermissions() 的返回值。
 */
public record PermissionResult(boolean allowed, String message) {

    /** 放行 */
    public static final PermissionResult ALLOW = new PermissionResult(true, null);

    /** 拒绝，附带原因 */
    public static PermissionResult deny(String reason) {
        return new PermissionResult(false, reason);
    }
}
