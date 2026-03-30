package io.mybatis.learn.core.tools;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

import java.util.List;

/**
 * 拦截并打印 tool 调用过程的 Advisor。
 * 让用户能看到中间每一步 tool 调用和返回结果。
 */
public class ToolCallLoggingAdvisor implements CallAdvisor {

    private static final String ANSI_YELLOW = "\033[33m";
    private static final String ANSI_RESET = "\033[0m";

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        ChatClientResponse response = chain.nextCall(request);

        ChatResponse chatResponse = response.chatResponse();
        if (chatResponse == null) return response;

        List<Generation> generations = chatResponse.getResults();
        for (Generation generation : generations) {
            AssistantMessage message = generation.getOutput();
            List<AssistantMessage.ToolCall> toolCalls = message.getToolCalls();
            if (toolCalls != null && !toolCalls.isEmpty()) {
                for (AssistantMessage.ToolCall tc : toolCalls) {
                    System.out.printf("%s🔧 Tool Call: %s(%s)%s%n",
                            ANSI_YELLOW, tc.name(), tc.arguments(), ANSI_RESET);
                }
            }
        }

        return response;
    }

    @Override
    public String getName() {
        return "ToolCallLoggingAdvisor";
    }

    @Override
    public int getOrder() {
        return 0;
    }
}
