package com.weaver.api.dto;

public class AgentResponse {
    private String solution;
    private boolean cacheHit;
    private String providerUsed;
    private String sessionId;
    private long durationMs;

    public AgentResponse() {}

    public AgentResponse(String solution, boolean cacheHit, String providerUsed, String sessionId, long durationMs) {
        this.solution = solution;
        this.cacheHit = cacheHit;
        this.providerUsed = providerUsed;
        this.sessionId = sessionId;
        this.durationMs = durationMs;
    }

    public static AgentResponseBuilder builder() { return new AgentResponseBuilder(); }

    public String getSolution() { return solution; }
    public void setSolution(String solution) { this.solution = solution; }
    public boolean isCacheHit() { return cacheHit; }
    public void setCacheHit(boolean cacheHit) { this.cacheHit = cacheHit; }
    public String getProviderUsed() { return providerUsed; }
    public void setProviderUsed(String providerUsed) { this.providerUsed = providerUsed; }
    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public long getDurationMs() { return durationMs; }
    public void setDurationMs(long durationMs) { this.durationMs = durationMs; }

    public static class AgentResponseBuilder {
        private String solution;
        private boolean cacheHit;
        private String providerUsed;
        private String sessionId;
        private long durationMs;

        public AgentResponseBuilder solution(String solution) { this.solution = solution; return this; }
        public AgentResponseBuilder cacheHit(boolean cacheHit) { this.cacheHit = cacheHit; return this; }
        public AgentResponseBuilder providerUsed(String providerUsed) { this.providerUsed = providerUsed; return this; }
        public AgentResponseBuilder sessionId(String sessionId) { this.sessionId = sessionId; return this; }
        public AgentResponseBuilder durationMs(long durationMs) { this.durationMs = durationMs; return this; }
        public AgentResponse build() {
            return new AgentResponse(solution, cacheHit, providerUsed, sessionId, durationMs);
        }
    }
}
