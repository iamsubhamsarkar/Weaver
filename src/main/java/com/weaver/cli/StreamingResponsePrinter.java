package com.weaver.cli;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;

import java.util.concurrent.CompletableFuture;

/**
 * Handles streaming LLM responses, printing tokens to terminal as they arrive.
 */
public class StreamingResponsePrinter implements StreamingChatResponseHandler {

    private final CompletableFuture<ChatResponse> future = new CompletableFuture<>();
    private final StringBuilder fullResponse = new StringBuilder();
    private boolean firstToken = true;

    @Override
    public void onPartialResponse(String partialResponse) {
        if (firstToken) {
            firstToken = false;
        }
        System.out.print(partialResponse);
        System.out.flush();
        fullResponse.append(partialResponse);
    }

    @Override
    public void onCompleteResponse(ChatResponse response) {
        System.out.println(); // newline after streaming ends
        future.complete(response);
    }

    @Override
    public void onError(Throwable error) {
        System.out.println();
        future.completeExceptionally(error);
    }

    public CompletableFuture<ChatResponse> getFuture() {
        return future;
    }

    public String getFullResponse() {
        return fullResponse.toString();
    }
}
