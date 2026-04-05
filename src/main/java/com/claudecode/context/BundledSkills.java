package com.claudecode.context;

import com.claudecode.context.SkillLoader.Skill;

import java.nio.file.Path;
import java.util.List;

/**
 * 内置 Skills 注册 —— 对应 claude-code/src/skills/bundled/ 目录。
 * <p>
 * 提供默认可用的 Skills，无需用户手动创建 .md 文件。
 */
public final class BundledSkills {

    private BundledSkills() {}

    /**
     * 获取所有内置 Skills。
     */
    public static List<Skill> getAll() {
        return List.of(
                verifySkill(),
                debugSkill(),
                reviewSkill(),
                commitSkill()
        );
    }

    /**
     * verify — 代码验证 Skill。
     * 运行项目测试、lint、类型检查，确保代码变更正确。
     */
    static Skill verifySkill() {
        return new Skill(
                "verify",
                "Run tests and checks to verify code changes are correct",
                "After making code changes that need verification",
                """
                # Verify Changes
                
                You are verifying that recent code changes are correct and don't break anything.
                
                ## Steps
                
                1. **Identify the project type** by reading build/config files:
                   - Look for: package.json, pom.xml, build.gradle, Cargo.toml, go.mod, etc.
                
                2. **Run the appropriate test command**:
                   - Node.js: `npm test` or `npx jest` or `npx vitest`
                   - Java/Maven: `mvn test -q`
                   - Java/Gradle: `./gradlew test`
                   - Python: `pytest` or `python -m pytest`
                   - Rust: `cargo test`
                   - Go: `go test ./...`
                
                3. **Run linting if available**:
                   - Node.js: `npm run lint` or `npx eslint .`
                   - Python: `ruff check .` or `flake8`
                   - Java: `mvn checkstyle:check -q`
                
                4. **Run type checking if available**:
                   - TypeScript: `npx tsc --noEmit`
                   - Python: `mypy .`
                
                5. **Report results**:
                   - ✅ All checks passed
                   - ❌ Failures found (include specific errors)
                   - ⚠️ Warnings (include details)
                
                If a check fails, analyze the error and suggest a fix.
                """,
                "bundled",
                Path.of("bundled://verify")
        );
    }

    /**
     * debug — 调试辅助 Skill。
     * 分析错误信息，定位问题根因。
     */
    static Skill debugSkill() {
        return new Skill(
                "debug",
                "Analyze errors and help debug issues",
                "When encountering an error or unexpected behavior",
                """
                # Debug Issue
                
                You are helping debug an issue. Follow this systematic approach:
                
                ## Steps
                
                1. **Understand the error**:
                   - Read the error message carefully
                   - Identify the error type (compile, runtime, logic, etc.)
                   - Note the file and line number if available
                
                2. **Reproduce the issue**:
                   - Try to reproduce the error with the minimal command
                   - Check if the error is consistent
                
                3. **Trace the root cause**:
                   - Read the file(s) mentioned in the error
                   - Check imports, dependencies, and configuration
                   - Look for recent changes that might have caused it
                   - Use Grep to find related code patterns
                
                4. **Analyze and diagnose**:
                   - Identify the root cause (not just symptoms)
                   - Consider edge cases and dependencies
                   - Check if similar patterns exist elsewhere
                
                5. **Suggest fix**:
                   - Propose a specific, minimal fix
                   - Explain why the fix works
                   - Note any side effects or risks
                
                If the user provides an error message, start from Step 1.
                If the user describes unexpected behavior, start from Step 2.
                """,
                "bundled",
                Path.of("bundled://debug")
        );
    }

    /**
     * review — 代码审查 Skill。
     */
    static Skill reviewSkill() {
        return new Skill(
                "review",
                "Review code changes for quality, bugs, and best practices",
                "When the user wants feedback on code changes",
                """
                # Code Review
                
                You are reviewing code changes. Focus on:
                
                ## Checklist
                
                1. **Correctness**: Does the code do what it's supposed to?
                2. **Edge cases**: Are boundary conditions handled?
                3. **Error handling**: Are errors caught and handled gracefully?
                4. **Security**: Any injection, auth, or data exposure risks?
                5. **Performance**: Any obvious performance issues?
                6. **Readability**: Is the code clear and well-structured?
                7. **Tests**: Are there tests? Do they cover key cases?
                
                ## Process
                
                1. Run `git diff` to see what changed
                2. Read each changed file
                3. For each issue found, note:
                   - Severity: 🔴 Critical / 🟡 Warning / 🔵 Suggestion
                   - File and line number
                   - What's wrong and how to fix it
                4. Summarize findings
                """,
                "bundled",
                Path.of("bundled://review")
        );
    }

    /**
     * commit — 提交 Skill。
     */
    static Skill commitSkill() {
        return new Skill(
                "commit",
                "Create a well-structured git commit with conventional commit message",
                "When the user wants to commit changes",
                """
                # Git Commit
                
                Create a well-structured git commit.
                
                ## Steps
                
                1. Run `git status` and `git diff --stat` to see changes
                2. Analyze the changes to determine:
                   - Type: feat, fix, refactor, docs, test, chore, etc.
                   - Scope: which module/area is affected
                   - Breaking changes
                3. Generate commit message in Conventional Commits format:
                   ```
                   type(scope): brief description
                   
                   Detailed body explaining what and why (not how).
                   
                   Breaking changes (if any).
                   ```
                4. Stage relevant files with `git add`
                5. Create the commit with `git commit -m "..."`
                6. Report the commit hash
                """,
                "bundled",
                Path.of("bundled://commit")
        );
    }
}
