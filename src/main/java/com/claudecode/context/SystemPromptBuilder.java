package com.claudecode.context;

/**
 * 系统提示词构建器 —— 对应 claude-code/src/prompts.ts。
 * <p>
 * 组装完整的系统提示词，包括核心指令、环境信息、工具说明等。
 */
public class SystemPromptBuilder {

    private String workDir;
    private String osName;
    private String userName;
    private String claudeMdContent;
    private String customInstructions;

    public SystemPromptBuilder() {
        this.workDir = System.getProperty("user.dir");
        this.osName = System.getProperty("os.name");
        this.userName = System.getProperty("user.name");
    }

    public SystemPromptBuilder workDir(String workDir) {
        this.workDir = workDir;
        return this;
    }

    public SystemPromptBuilder claudeMd(String content) {
        this.claudeMdContent = content;
        return this;
    }

    public SystemPromptBuilder customInstructions(String instructions) {
        this.customInstructions = instructions;
        return this;
    }

    /**
     * 构建完整的系统提示词。
     */
    public String build() {
        StringBuilder sb = new StringBuilder();

        // 核心角色定义
        sb.append("""
                You are Claude, an AI assistant made by Anthropic, operating as a CLI coding agent.
                You are an interactive CLI tool that helps users with software engineering tasks.
                Use the provided tools to help the user with their request.

                """);

        // 环境信息
        sb.append("# Environment\n");
        sb.append("- Working directory: ").append(workDir).append("\n");
        sb.append("- OS: ").append(osName).append("\n");
        sb.append("- User: ").append(userName).append("\n");
        sb.append("\n");

        // 行为准则
        sb.append("""
                # Guidelines
                - Be concise in responses, but thorough in implementation
                - Always verify changes work before considering a task done
                - Use tools to explore the codebase before making changes
                - When writing code, follow existing patterns and conventions
                - Ask for clarification when requirements are ambiguous
                
                """);

        // CLAUDE.md 内容
        if (claudeMdContent != null && !claudeMdContent.isBlank()) {
            sb.append("# Project Instructions (CLAUDE.md)\n");
            sb.append(claudeMdContent).append("\n\n");
        }

        // 自定义指令
        if (customInstructions != null && !customInstructions.isBlank()) {
            sb.append("# Custom Instructions\n");
            sb.append(customInstructions).append("\n\n");
        }

        return sb.toString();
    }
}
