package com.claudecode.tool.impl;

import com.claudecode.tool.Tool;
import com.claudecode.tool.ToolContext;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;

/**
 * 文件写入工具 —— 对应 claude-code/src/tools/write/WriteFileTool.ts。
 * <p>
 * 将内容写入文件（创建或覆盖）。
 */
public class FileWriteTool implements Tool {

    @Override
    public String name() {
        return "Write";
    }

    @Override
    public String description() {
        return """
            Write content to a file. Creates the file and any parent directories if they don't exist. \
            If the file exists, it will be overwritten. Use this for creating new files or completely \
            replacing file content.""";
    }

    @Override
    public String inputSchema() {
        return """
            {
              "type": "object",
              "properties": {
                "file_path": {
                  "type": "string",
                  "description": "Absolute or relative path to the file"
                },
                "content": {
                  "type": "string",
                  "description": "The content to write to the file"
                }
              },
              "required": ["file_path", "content"]
            }""";
    }

    @Override
    public String execute(Map<String, Object> input, ToolContext context) {
        String filePath = (String) input.get("file_path");
        String content = (String) input.get("content");
        Path path = context.getWorkDir().resolve(filePath).normalize();

        try {
            // 自动创建父目录
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }

            boolean existed = Files.exists(path);
            Files.writeString(path, content, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

            long lines = content.lines().count();
            if (existed) {
                return "✅ Updated " + path + " (" + lines + " lines)";
            } else {
                return "✅ Created " + path + " (" + lines + " lines)";
            }

        } catch (IOException e) {
            return "Error writing file: " + e.getMessage();
        }
    }

    @Override
    public String activityDescription(Map<String, Object> input) {
        return "✏️ Writing " + input.getOrDefault("file_path", "file");
    }
}
