package com.claudecode.tui;

import io.mybatis.jink.style.Color;

import java.util.List;

/**
 * TUI 消息模型 —— 对应 Claude Code 界面中显示的各类消息。
 * <p>
 * 使用 sealed interface 确保消息类型完备。
 */
public sealed interface UIMessage {

    /** 用户输入消息 */
    record UserMsg(String text) implements UIMessage {}

    /** AI 助手回复（支持流式追加） */
    record AssistantMsg(String text, boolean streaming) implements UIMessage {
        public AssistantMsg appendText(String token) {
            return new AssistantMsg(text + token, streaming);
        }

        public AssistantMsg finish() {
            return new AssistantMsg(text, false);
        }
    }

    /** 工具调用消息 */
    record ToolCallMsg(String toolName, String args, String result, boolean running) implements UIMessage {
        public ToolCallMsg complete(String result) {
            return new ToolCallMsg(toolName, args, result, false);
        }
    }

    /** AI 思考过程 */
    record ThinkingMsg(String text) implements UIMessage {}

    /** 系统状态消息（启动提示、警告等） */
    record SystemMsg(String text, Color color) implements UIMessage {}

    /** 耗时统计 */
    record TimingMsg(long seconds) implements UIMessage {}

    /** 权限确认请求（内联显示） */
    record PermissionMsg(
            String toolName,
            String action,
            String args,
            boolean dangerous,
            String suggestedRule,
            boolean answered
    ) implements UIMessage {}

    /** 命令输出消息 */
    record CommandOutputMsg(String text) implements UIMessage {}
}
