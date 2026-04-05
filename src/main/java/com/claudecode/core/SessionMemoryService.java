package com.claudecode.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.ai.chat.messages.Message;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * 会话记忆服务 —— 对应 claude-code/src/services/SessionMemory/。
 * <p>
 * 自动维护 SESSION_MEMORY.md 文件，记录当前会话的关键发现、决策和上下文。
 * 在后台运行，不中断主对话。
 * <p>
 * 触发条件：
 * <ul>
 *   <li>初始化：上下文 token 超过 50,000</li>
 *   <li>更新：自上次提取后增长超过 20,000 token 且工具调用 >= 5</li>
 * </ul>
 */
public class SessionMemoryService {

    private static final Logger log = LoggerFactory.getLogger(SessionMemoryService.class);

    /** 初始化阈值：50K tokens */
    private static final long MINIMUM_TOKENS_TO_INIT = 50_000;
    /** 更新阈值：自上次提取后增长 20K tokens */
    private static final long MINIMUM_TOKENS_BETWEEN_UPDATE = 20_000;
    /** 更新阈值：工具调用次数 */
    private static final int MINIMUM_TOOL_CALLS_BETWEEN_UPDATE = 5;

    private final Path memoryDir;
    private final Path memoryFile;
    private final AtomicLong lastExtractionTokens = new AtomicLong(0);
    private final AtomicInteger toolCallsSinceLastExtraction = new AtomicInteger(0);
    private volatile boolean initialized = false;
    private volatile boolean extracting = false;

    /** Agent factory for forked extraction agent */
    private Function<String, String> agentFactory;

    public SessionMemoryService(Path projectDir) {
        String sanitized = projectDir.toAbsolutePath().toString()
                .replace(":", "_")
                .replace("\\", "_")
                .replace("/", "_");
        this.memoryDir = Path.of(System.getProperty("user.home"))
                .resolve(".claude")
                .resolve("projects")
                .resolve(sanitized)
                .resolve("memory");
        this.memoryFile = memoryDir.resolve("SESSION_MEMORY.md");
    }

    public void setAgentFactory(Function<String, String> agentFactory) {
        this.agentFactory = agentFactory;
    }

    /** Cumulative token count for threshold tracking */
    private final AtomicLong cumulativeTokens = new AtomicLong(0);

    /**
     * Post-sampling hook: 在每次模型响应后调用。
     * 根据阈值决定是否触发记忆提取。
     *
     * @param tokensThisTurn 本次迭代使用的 token 数
     * @param toolCallCount 本次响应中的工具调用数量
     * @param messageHistory 当前消息历史（用于提取上下文）
     */
    public void onPostSampling(long tokensThisTurn, int toolCallCount, List<Message> messageHistory) {
        if (extracting) return; // Already extracting

        long currentTokens = cumulativeTokens.addAndGet(tokensThisTurn);
        toolCallsSinceLastExtraction.addAndGet(toolCallCount);

        if (shouldExtractMemory(currentTokens)) {
            extractMemoryAsync(currentTokens);
        }
    }

    /**
     * 记录工具调用（用于计数阈值）。
     */
    public void recordToolCall() {
        toolCallsSinceLastExtraction.incrementAndGet();
    }

    /**
     * 判断是否应该提取记忆。
     */
    boolean shouldExtractMemory(long currentTokens) {
        if (!initialized) {
            // First extraction: need enough context
            return currentTokens >= MINIMUM_TOKENS_TO_INIT;
        }

        // Subsequent extractions
        long tokenGrowth = currentTokens - lastExtractionTokens.get();
        if (tokenGrowth < MINIMUM_TOKENS_BETWEEN_UPDATE) {
            return false;
        }

        // Token threshold met + tool call threshold
        int toolCalls = toolCallsSinceLastExtraction.get();
        return toolCalls >= MINIMUM_TOOL_CALLS_BETWEEN_UPDATE;
    }

    /**
     * 异步提取记忆。
     */
    private void extractMemoryAsync(long currentTokens) {
        if (agentFactory == null) {
            log.debug("SessionMemory: no agent factory, skipping extraction");
            return;
        }

        extracting = true;
        Thread.ofVirtual().name("session-memory-extraction").start(() -> {
            try {
                extractMemory(currentTokens);
            } catch (Exception e) {
                log.debug("SessionMemory extraction failed", e);
            } finally {
                extracting = false;
            }
        });
    }

    /**
     * 执行记忆提取。
     */
    void extractMemory(long currentTokens) {
        log.info("SessionMemory: starting extraction (tokens: {}, initialized: {})",
                currentTokens, initialized);

        try {
            // Ensure directory exists
            Files.createDirectories(memoryDir);

            String existingMemory = "";
            if (Files.exists(memoryFile)) {
                existingMemory = Files.readString(memoryFile, StandardCharsets.UTF_8);
            }

            // Build extraction prompt
            String prompt = initialized
                    ? buildUpdatePrompt(existingMemory)
                    : buildInitPrompt();

            // Run forked agent for extraction
            String result = agentFactory.apply(prompt);

            // The agent should have written to the file via FileWrite/FileEdit tools
            // But as a fallback, if it returned content, write it
            if (result != null && !result.isBlank() && !Files.exists(memoryFile)) {
                Files.writeString(memoryFile, result, StandardCharsets.UTF_8);
            }

            // Update tracking
            lastExtractionTokens.set(currentTokens);
            toolCallsSinceLastExtraction.set(0);
            initialized = true;

            log.info("SessionMemory: extraction complete");
        } catch (IOException e) {
            log.warn("SessionMemory: failed to write memory file", e);
        }
    }

    /**
     * 初始化提取提示词。
     */
    String buildInitPrompt() {
        return """
                You are a session memory extractor. Your job is to create a SESSION_MEMORY.md file \
                that captures the key information from this conversation.
                
                Create the file at: %s
                
                The file should include these sections:
                
                # Session Memory
                
                ## Task Overview
                - What is the user working on?
                - What are the main goals?
                
                ## Key Decisions
                - Important decisions made during the conversation
                - Rationale for each decision
                
                ## Code Changes
                - Files modified and why
                - Key patterns or approaches used
                
                ## Discoveries
                - Important findings about the codebase
                - Architecture or design insights
                
                ## Next Steps
                - What remains to be done
                - Known issues or blockers
                
                Extract information from the conversation history. Be concise but comprehensive. \
                Focus on information that would be valuable if the conversation were interrupted \
                and needed to be resumed later.
                """.formatted(memoryFile);
    }

    /**
     * 更新提取提示词。
     */
    String buildUpdatePrompt(String existingMemory) {
        return """
                You are a session memory extractor. Update the existing SESSION_MEMORY.md file \
                with new information from the recent conversation.
                
                File location: %s
                
                Current content:
                ```
                %s
                ```
                
                Update the file with new information. Rules:
                - Keep existing information that is still relevant
                - Add new decisions, changes, and discoveries
                - Update the "Next Steps" section
                - Remove outdated information
                - Be concise — this file should stay under 200 lines
                - Use FileEdit to update specific sections, or FileWrite to rewrite entirely
                """.formatted(memoryFile, existingMemory);
    }

    /**
     * 读取当前记忆内容（用于系统提示注入）。
     */
    public String getMemoryContent() {
        if (!Files.exists(memoryFile)) return null;
        try {
            String content = Files.readString(memoryFile, StandardCharsets.UTF_8);
            return content.isBlank() ? null : content;
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * 获取记忆文件路径。
     */
    public Path getMemoryFile() {
        return memoryFile;
    }

    /**
     * 是否已初始化（至少提取过一次）。
     */
    public boolean isInitialized() {
        return initialized;
    }

    /**
     * 是否正在提取。
     */
    public boolean isExtracting() {
        return extracting;
    }
}
