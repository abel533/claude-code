package com.claudecode.command.impl;

import com.claudecode.command.CommandContext;
import com.claudecode.command.SlashCommand;
import com.claudecode.console.AnsiStyle;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * /init 命令 —— 初始化项目 CLAUDE.md。
 * <p>
 * 对应 claude-code/src/commands/init.ts。
 * 检测项目类型并生成 CLAUDE.md 模板文件。
 */
public class InitCommand implements SlashCommand {

    @Override
    public String name() {
        return "init";
    }

    @Override
    public String description() {
        return "Initialize CLAUDE.md for the current project";
    }

    @Override
    public String execute(String args, CommandContext context) {
        Path projectDir = Path.of(System.getProperty("user.dir"));
        Path claudeMdPath = projectDir.resolve("CLAUDE.md");

        // 检查是否已存在
        if (Files.exists(claudeMdPath)) {
            return AnsiStyle.yellow("  ⚠ CLAUDE.md already exists at: " + claudeMdPath) + "\n"
                    + AnsiStyle.dim("  Use a text editor to modify it, or delete and re-run /init.");
        }

        // 检测项目类型
        ProjectType type = detectProjectType(projectDir);

        // 生成 CLAUDE.md 内容
        String content = generateClaudeMd(projectDir, type);

        try {
            Files.writeString(claudeMdPath, content, StandardCharsets.UTF_8);

            // 如果存在 .claude 目录，也创建 skills 目录
            Path claudeDir = projectDir.resolve(".claude");
            Path skillsDir = claudeDir.resolve("skills");
            if (!Files.exists(skillsDir)) {
                Files.createDirectories(skillsDir);
            }

            StringBuilder sb = new StringBuilder();
            sb.append(AnsiStyle.green("  ✅ Created CLAUDE.md")).append("\n");
            sb.append(AnsiStyle.dim("  Path: " + claudeMdPath)).append("\n");
            sb.append(AnsiStyle.dim("  Project type: " + type.displayName)).append("\n");
            sb.append(AnsiStyle.dim("  Skills dir: " + skillsDir)).append("\n\n");
            sb.append(AnsiStyle.dim("  Edit CLAUDE.md to customize instructions for the AI assistant."));
            return sb.toString();

        } catch (IOException e) {
            return AnsiStyle.red("  ✗ Failed to create CLAUDE.md: " + e.getMessage());
        }
    }

    /** 检测项目类型 */
    private ProjectType detectProjectType(Path projectDir) {
        if (Files.exists(projectDir.resolve("pom.xml"))) return ProjectType.MAVEN;
        if (Files.exists(projectDir.resolve("build.gradle")) || Files.exists(projectDir.resolve("build.gradle.kts")))
            return ProjectType.GRADLE;
        if (Files.exists(projectDir.resolve("package.json"))) return ProjectType.NODE;
        if (Files.exists(projectDir.resolve("pyproject.toml")) || Files.exists(projectDir.resolve("setup.py")))
            return ProjectType.PYTHON;
        if (Files.exists(projectDir.resolve("Cargo.toml"))) return ProjectType.RUST;
        if (Files.exists(projectDir.resolve("go.mod"))) return ProjectType.GO;
        if (Files.exists(projectDir.resolve("Gemfile"))) return ProjectType.RUBY;
        return ProjectType.GENERIC;
    }

    /** 生成 CLAUDE.md 内容 */
    private String generateClaudeMd(Path projectDir, ProjectType type) {
        String projectName = projectDir.getFileName().toString();

        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(projectName).append("\n\n");
        sb.append("## Project Overview\n\n");
        sb.append("<!-- Describe your project here -->\n\n");

        sb.append("## Tech Stack\n\n");
        sb.append(type.techStackHint).append("\n\n");

        sb.append("## Build & Run\n\n");
        sb.append("```bash\n");
        sb.append(type.buildCommand).append("\n");
        sb.append("```\n\n");

        sb.append("## Test\n\n");
        sb.append("```bash\n");
        sb.append(type.testCommand).append("\n");
        sb.append("```\n\n");

        sb.append("## Code Style\n\n");
        sb.append("- Follow existing patterns in the codebase\n");
        sb.append("- Write clear, descriptive variable names\n");
        sb.append("- Add comments for complex business logic\n\n");

        sb.append("## Project Structure\n\n");
        sb.append("<!-- Describe key directories and files -->\n");

        return sb.toString();
    }

    enum ProjectType {
        MAVEN("Maven/Java", "- Java (Maven)\n- Spring Boot (if applicable)", "mvn clean install", "mvn test"),
        GRADLE("Gradle/Java", "- Java/Kotlin (Gradle)\n- Spring Boot (if applicable)", "gradle build", "gradle test"),
        NODE("Node.js", "- Node.js\n- TypeScript/JavaScript", "npm install && npm run build", "npm test"),
        PYTHON("Python", "- Python 3\n- pip/poetry", "pip install -e .", "pytest"),
        RUST("Rust", "- Rust\n- Cargo", "cargo build", "cargo test"),
        GO("Go", "- Go", "go build ./...", "go test ./..."),
        RUBY("Ruby", "- Ruby\n- Bundler", "bundle install", "bundle exec rspec"),
        GENERIC("Generic", "<!-- List your tech stack -->", "# add build command", "# add test command");

        final String displayName;
        final String techStackHint;
        final String buildCommand;
        final String testCommand;

        ProjectType(String displayName, String techStackHint, String buildCommand, String testCommand) {
            this.displayName = displayName;
            this.techStackHint = techStackHint;
            this.buildCommand = buildCommand;
            this.testCommand = testCommand;
        }
    }
}
