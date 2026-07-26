package com.weaver.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties(prefix = "weaver")
public class WeaverConfigProperties {
    private double semanticMinScore = 0.92;
    private int cacheMaxResults = 1;
    private int maxAgentSteps = 15;
    private int defaultContextTokenBudget = 8000;
    private int complexTaskThresholdScore = 70;
    private List<String> allowedFileExtensions = List.of(
        ".java", ".py", ".js", ".ts", ".go", ".rs", ".c", ".cpp", ".h",
        ".html", ".css", ".json", ".yml", ".yaml", ".xml", ".md", ".txt",
        ".sh", ".bash", ".sql", ".toml", ".gradle", ".kt", ".swift"
    );
    private String workspaceRoot = System.getProperty("user.dir");

    public double getSemanticMinScore() { return semanticMinScore; }
    public void setSemanticMinScore(double semanticMinScore) { this.semanticMinScore = semanticMinScore; }
    public int getCacheMaxResults() { return cacheMaxResults; }
    public void setCacheMaxResults(int cacheMaxResults) { this.cacheMaxResults = cacheMaxResults; }
    public int getMaxAgentSteps() { return maxAgentSteps; }
    public void setMaxAgentSteps(int maxAgentSteps) { this.maxAgentSteps = maxAgentSteps; }
    public int getDefaultContextTokenBudget() { return defaultContextTokenBudget; }
    public void setDefaultContextTokenBudget(int defaultContextTokenBudget) { this.defaultContextTokenBudget = defaultContextTokenBudget; }
    public int getComplexTaskThresholdScore() { return complexTaskThresholdScore; }
    public void setComplexTaskThresholdScore(int complexTaskThresholdScore) { this.complexTaskThresholdScore = complexTaskThresholdScore; }
    public List<String> getAllowedFileExtensions() { return allowedFileExtensions; }
    public void setAllowedFileExtensions(List<String> allowedFileExtensions) { this.allowedFileExtensions = allowedFileExtensions; }
    public String getWorkspaceRoot() { return workspaceRoot; }
    public void setWorkspaceRoot(String workspaceRoot) { this.workspaceRoot = workspaceRoot; }
}
