package com.claudecode.command.impl;

import com.claudecode.command.CommandContext;
import com.claudecode.command.SlashCommand;
import com.claudecode.console.AnsiStyle;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Collectors;

/**
 * /security-review 命令 —— 通过 AI 进行安全审查。
 * <p>
 * 获取当前项目的最新代码变更（{@code git diff HEAD}），
 * 发送给 AI 模型进行安全方面的专项审查。
 * <p>
 * 审查重点包括：
 * <ul>
 *   <li>SQL 注入漏洞</li>
 *   <li>跨站脚本攻击（XSS）</li>
 *   <li>身份认证和授权问题</li>
 *   <li>敏感信息泄露（密钥、密码等）</li>
 *   <li>依赖安全问题</li>
 * </ul>
 */
public class SecurityReviewCommand implements SlashCommand {

    @Override
    public String name() {
        return "security-review";
    }

    @Override
    public String description() {
        return "Review code changes for security vulnerabilities";
    }

    @Override
    public List<String> aliases() {
        return List.of("sec");
    }

    @Override
    public String execute(String args, CommandContext context) {
        if (context.agentLoop() == null) {
            return AnsiStyle.red("  ✗ AgentLoop unavailable, cannot perform security review.");
        }

        try {
            // 获取最近的代码变更
            String diffOutput = executeGitDiff();

            if (diffOutput.isBlank()) {
                return AnsiStyle.yellow("  ⚠ No code changes detected.") + "\n"
                        + AnsiStyle.dim("  git diff HEAD returned nothing. Please verify there are commits.");
            }

            // 输出审查进行中的提示
            context.out().println(AnsiStyle.magenta("  🔒 Performing security review..."));
            context.out().println(AnsiStyle.dim("  diff size: " + diffOutput.lines().count() + " lines"));
            context.out().println();

            // 构建安全审查提示词
            String securityPrompt = buildSecurityPrompt(diffOutput);

            // 发送给 AI 进行安全审查
            String result = context.agentLoop().run(securityPrompt);
            return result;

        } catch (Exception e) {
            return AnsiStyle.red("  ✗ Security review failed: " + e.getMessage()) + "\n"
                    + AnsiStyle.dim("  Please ensure the current directory is a Git repository with commit history.");
        }
    }

    /**
     * 执行 {@code git diff HEAD} 获取最近的代码变更。
     *
     * @return diff 输出内容
     * @throws Exception 命令执行失败时抛出异常
     */
    private String executeGitDiff() throws Exception {
        ProcessBuilder pb = new ProcessBuilder("git", "diff", "HEAD");
        pb.redirectErrorStream(false);

        Process process = pb.start();

        String output;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            output = reader.lines().collect(Collectors.joining("\n"));
        }

        // 读取错误输出
        String errorOutput;
        try (BufferedReader errorReader = new BufferedReader(
                new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
            errorOutput = errorReader.lines().collect(Collectors.joining("\n"));
        }

        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new RuntimeException("git diff HEAD failed (exit=" + exitCode + "): " + errorOutput);
        }

        return output;
    }

    /**
     * 构建安全审查提示词。
     * <p>
     * 要求 AI 从多个安全维度对代码变更进行全面审查，
     * 并按严重程度分类报告发现的问题。
     *
     * @param diffOutput git diff 的输出内容
     * @return 完整的安全审查提示词
     */
    private String buildSecurityPrompt(String diffOutput) {
        return """
                Please perform a comprehensive security review of the following code changes.
                
                ```diff
                %s
                ```
                
                Analyze the code changes for the following security concerns:
                
                ## 1. SQL Injection
                - Are there any raw SQL queries with string concatenation?
                - Are parameterized queries/prepared statements used properly?
                - Is user input sanitized before database operations?
                
                ## 2. Cross-Site Scripting (XSS)
                - Is user input properly escaped before rendering in HTML?
                - Are there any unsafe innerHTML or DOM manipulation patterns?
                - Is output encoding applied correctly?
                
                ## 3. Authentication & Authorization
                - Are authentication checks properly implemented?
                - Are authorization boundaries enforced correctly?
                - Are session tokens handled securely?
                - Are passwords hashed with strong algorithms (bcrypt, Argon2)?
                
                ## 4. Secrets & Sensitive Data
                - Are any API keys, passwords, or tokens hardcoded?
                - Are sensitive data properly encrypted at rest and in transit?
                - Are credentials stored in environment variables or secure vaults?
                - Are there any logging statements that might expose sensitive information?
                
                ## 5. Dependency & Configuration Security
                - Are there any known vulnerable dependencies?
                - Are security headers properly configured?
                - Are CORS policies appropriately restrictive?
                - Is TLS/SSL properly configured?
                
                ## 6. Other Security Concerns
                - Path traversal vulnerabilities
                - Command injection risks
                - Insecure deserialization
                - Race conditions
                - Improper error handling that leaks information
                
                For each issue found, please provide:
                - **Severity**: Critical / High / Medium / Low
                - **Location**: File and line reference
                - **Description**: What the vulnerability is
                - **Recommendation**: How to fix it
                
                If no security issues are found, explicitly state that the changes look secure.
                """.formatted(diffOutput);
    }
}
