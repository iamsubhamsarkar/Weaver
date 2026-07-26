package com.weaver.provider;

public class ProviderProperties {
    private String name;
    private String endpoint;
    private String apiKey;
    private String model;
    private int maxTokensPerRequest = 4096;
    private int rpmLimit = 30;
    private int dailyLimit = 1000;
    private int priority = 10;
    private boolean enabled = true;
    private long contextWindow = 8192;

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getEndpoint() { return endpoint; }
    public void setEndpoint(String endpoint) { this.endpoint = endpoint; }
    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public int getMaxTokensPerRequest() { return maxTokensPerRequest; }
    public void setMaxTokensPerRequest(int maxTokensPerRequest) { this.maxTokensPerRequest = maxTokensPerRequest; }
    public int getRpmLimit() { return rpmLimit; }
    public void setRpmLimit(int rpmLimit) { this.rpmLimit = rpmLimit; }
    public int getDailyLimit() { return dailyLimit; }
    public void setDailyLimit(int dailyLimit) { this.dailyLimit = dailyLimit; }
    public int getPriority() { return priority; }
    public void setPriority(int priority) { this.priority = priority; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public long getContextWindow() { return contextWindow; }
    public void setContextWindow(long contextWindow) { this.contextWindow = contextWindow; }
}
