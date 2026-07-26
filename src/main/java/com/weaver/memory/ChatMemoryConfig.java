package com.weaver.memory;

import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ChatMemoryConfig {

    @Bean
    public ChatMemory chatMemory(WeaverMemoryStore memoryStore) {
        return MessageWindowChatMemory.builder()
                .id("weaver-default")
                .maxMessages(50)
                .chatMemoryStore(memoryStore)
                .build();
    }
}
