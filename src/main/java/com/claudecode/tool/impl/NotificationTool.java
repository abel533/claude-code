package com.claudecode.tool.impl;

import com.claudecode.tool.Tool;
import com.claudecode.tool.ToolContext;
import com.claudecode.tool.ToolValidator;
import com.claudecode.tool.util.ProcessExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * Notification 工具 —— 系统通知。
 * <p>
 * 向用户发送系统级通知（桌面弹窗 + 可选声音提示）。
 */
public class NotificationTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(NotificationTool.class);

    @Override
    public String name() {
        return "Notification";
    }

    @Override
    public String description() {
        return """
            Send a system notification to the user. Use this when:
            - A long-running task completes and the user may have switched away
            - An error requires the user's attention
            - You need the user to come back and provide input
            
            Supports desktop notifications (with optional sound) and terminal bell.""";
    }

    @Override
    public String inputSchema() {
        return """
            {
              "type": "object",
              "properties": {
                "title": {
                  "type": "string",
                  "description": "Notification title (short, max 80 chars)"
                },
                "message": {
                  "type": "string",
                  "description": "Notification body text"
                },
                "level": {
                  "type": "string",
                  "enum": ["info", "warning", "error"],
                  "description": "Notification severity level (default: info)"
                },
                "sound": {
                  "type": "boolean",
                  "description": "Play notification sound (default: true)"
                }
              },
              "required": ["title", "message"]
            }""";
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public String execute(Map<String, Object> input, ToolContext context) {
        String err = ToolValidator.requireString(input, "title");
        if (err != null) return err;
        err = ToolValidator.requireString(input, "message");
        if (err != null) return err;

        String title = input.get("title").toString();
        String message = input.get("message").toString();
        String level = ToolValidator.getString(input, "level", "info");
        boolean sound = ToolValidator.getBoolean(input, "sound", true);

        title = title.length() > 80 ? title.substring(0, 77) + "..." : title;

        boolean sent = false;
        String method = "none";

        String os = System.getProperty("os.name", "").toLowerCase();
        try {
            if (os.contains("win")) {
                sent = notifyWindows(title, message);
                method = "windows-toast";
            } else if (os.contains("mac")) {
                sent = notifyMac(title, message);
                method = "osascript";
            } else if (os.contains("linux")) {
                sent = notifyLinux(title, message);
                method = "notify-send";
            }
        } catch (Exception e) {
            log.debug("OS notification failed: {}", e.getMessage());
        }

        if (sound) {
            System.out.print('\u0007');
            System.out.flush();
        }

        if (!sent) {
            method = "terminal";
        }

        return String.format("Notification sent via %s: [%s] %s - %s", method, level, title, message);
    }

    private boolean notifyWindows(String title, String message) {
        String ps = String.format(
                "[System.Reflection.Assembly]::LoadWithPartialName('System.Windows.Forms') | Out-Null; " +
                "$n = New-Object System.Windows.Forms.NotifyIcon; " +
                "$n.Icon = [System.Drawing.SystemIcons]::Information; " +
                "$n.Visible = $true; " +
                "$n.ShowBalloonTip(5000, '%s', '%s', 'Info'); " +
                "Start-Sleep -Seconds 1; $n.Dispose()",
                title.replace("'", "''"), message.replace("'", "''"));
        var result = ProcessExecutor.execute(
                List.of("powershell", "-NoProfile", "-Command", ps), null, 10000);
        return result.isSuccess();
    }

    private boolean notifyMac(String title, String message) {
        String script = String.format(
                "display notification \"%s\" with title \"%s\"",
                message.replace("\"", "\\\""), title.replace("\"", "\\\""));
        var result = ProcessExecutor.execute(List.of("osascript", "-e", script), null, 5000);
        return result.isSuccess();
    }

    private boolean notifyLinux(String title, String message) {
        var result = ProcessExecutor.execute(List.of("notify-send", title, message), null, 5000);
        return result.isSuccess();
    }

    @Override
    public String activityDescription(Map<String, Object> input) {
        String title = (String) input.getOrDefault("title", "notification");
        return "🔔 Notify: " + title;
    }
}
