package io.mybatis.learn.core.config;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class AiConfig {

    private final Map<String, ChatModel> modelMap = new ConcurrentHashMap<>();

    @Value("${ai.provider}")
    private String aiProvider;

    @Autowired
    public AiConfig(@Qualifier("anthropicChatModel") ChatModel anthropicChatModel,
                            @Qualifier("openAiChatModel") ChatModel openAiChatModel) {
        modelMap.put("openai", openAiChatModel);
        modelMap.put("anthropic", anthropicChatModel);
    }

    public ChatModel get() {
        return modelMap.get(aiProvider);
    }

}
