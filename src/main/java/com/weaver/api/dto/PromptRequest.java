package com.weaver.api.dto;

import jakarta.validation.constraints.NotBlank;

public class PromptRequest {
    @NotBlank(message = "Prompt is required")
    private String prompt;
    private String sessionId;
    private String taskType;

    public String getPrompt() { return prompt; }
    public void setPrompt(String prompt) { this.prompt = prompt; }
    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public String getTaskType() { return taskType; }
    public void setTaskType(String taskType) { this.taskType = taskType; }
}
