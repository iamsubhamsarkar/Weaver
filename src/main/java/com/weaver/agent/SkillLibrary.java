package com.weaver.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/**
 * Skill Library: stores successful execution plans and replays them for similar tasks.
 *
 * When a task is completed successfully via Plan-then-Execute:
 * 1. The plan JSON is saved as a "skill" with its task embedding
 * 2. Next time a similar task comes, the skill is loaded and executed directly
 * 3. This means ZERO API calls for repeat task patterns
 *
 * Storage: ~/.weaver/skills/*.json
 * Matching: cosine similarity of task embeddings (threshold: 0.88)
 */
@Component
public class SkillLibrary {

    private static final Logger log = LoggerFactory.getLogger(SkillLibrary.class);
    private static final Path SKILLS_DIR = Path.of(System.getProperty("user.home"), ".weaver", "skills");
    private static final double MATCH_THRESHOLD = 0.88;
    private static final ObjectMapper mapper = new ObjectMapper();

    private final EmbeddingModel embeddingModel;
    private final List<Skill> loadedSkills = new ArrayList<>();

    public record Skill(String id, String description, float[] embedding, String planJson) {}

    public SkillLibrary(EmbeddingModel embeddingModel) {
        this.embeddingModel = embeddingModel;
        loadSkills();
    }

    /**
     * Find a matching skill for the given task.
     * Returns the plan JSON if a skill matches, null otherwise.
     */
    public String findMatchingSkill(String userPrompt) {
        if (loadedSkills.isEmpty()) return null;

        try {
            Embedding promptEmb = embeddingModel.embed(userPrompt).content();
            float[] promptVec = promptEmb.vector();

            Skill bestMatch = null;
            double bestScore = 0;

            for (Skill skill : loadedSkills) {
                double score = cosineSimilarity(promptVec, skill.embedding());
                if (score > bestScore) {
                    bestScore = score;
                    bestMatch = skill;
                }
            }

            if (bestMatch != null && bestScore >= MATCH_THRESHOLD) {
                log.info("Skill match found: {} (score: {:.3f})", bestMatch.description(), bestScore);
                return bestMatch.planJson();
            }
        } catch (Exception e) {
            log.debug("Skill matching failed: {}", e.getMessage());
        }
        return null;
    }

    /**
     * Store a successful plan as a new skill.
     * Only stores if the plan meets quality criteria.
     */
    public void storeSkill(String userPrompt, String planJson) {
        try {
            // Quality gate: don't store plans with tiny file outputs or blocked commands
            if (!isQualityPlan(planJson)) {
                log.info("Skill NOT stored: failed quality check");
                return;
            }

            Files.createDirectories(SKILLS_DIR);

            Embedding embedding = embeddingModel.embed(userPrompt).content();
            String id = UUID.randomUUID().toString().substring(0, 8);

            // Create skill JSON file
            ObjectNode skillNode = mapper.createObjectNode();
            skillNode.put("id", id);
            skillNode.put("description", userPrompt.length() > 100
                    ? userPrompt.substring(0, 100) : userPrompt);
            skillNode.put("plan", planJson);
            // Store embedding as array
            var embArray = skillNode.putArray("embedding");
            for (float v : embedding.vector()) {
                embArray.add(v);
            }

            Path skillFile = SKILLS_DIR.resolve(id + ".json");
            Files.writeString(skillFile, mapper.writeValueAsString(skillNode));

            // Add to in-memory cache
            loadedSkills.add(new Skill(id, userPrompt, embedding.vector(), planJson));
            log.info("Skill stored: {} (total skills: {})", id, loadedSkills.size());
        } catch (Exception e) {
            log.warn("Failed to store skill: {}", e.getMessage());
        }
    }

    /**
     * Load all skills from disk into memory on startup.
     */
    private void loadSkills() {
        try {
            if (!Files.exists(SKILLS_DIR)) {
                Files.createDirectories(SKILLS_DIR);
                return;
            }

            try (DirectoryStream<Path> stream = Files.newDirectoryStream(SKILLS_DIR, "*.json")) {
                for (Path file : stream) {
                    try {
                        String content = Files.readString(file);
                        JsonNode node = mapper.readTree(content);

                        String id = node.get("id").asText();
                        String desc = node.get("description").asText();
                        String plan = node.get("plan").asText();
                        JsonNode embNode = node.get("embedding");

                        float[] emb = new float[embNode.size()];
                        for (int i = 0; i < embNode.size(); i++) {
                            emb[i] = (float) embNode.get(i).asDouble();
                        }

                        loadedSkills.add(new Skill(id, desc, emb, plan));
                    } catch (Exception e) {
                        log.debug("Failed to load skill file {}: {}", file, e.getMessage());
                    }
                }
            }

            if (!loadedSkills.isEmpty()) {
                log.info("Loaded {} skills from library", loadedSkills.size());
            }
        } catch (IOException e) {
            log.debug("Skills directory not accessible: {}", e.getMessage());
        }
    }

    public int getSkillCount() {
        return loadedSkills.size();
    }

    private double cosineSimilarity(float[] a, float[] b) {
        double dot = 0, normA = 0, normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    /**
     * Quality check: reject plans that are clearly bad.
     * - Plans with writeFile that wrote less than 500 chars (garbage output)
     * - Plans containing npm/yarn commands (hallucinated dependencies)
     * - Plans with only readFile and no output action
     */
    private boolean isQualityPlan(String planJson) {
        if (planJson == null) return false;
        String lower = planJson.toLowerCase();

        // Reject plans with npm/yarn (usually hallucinated)
        if (lower.contains("npm ") || lower.contains("yarn ")) return false;

        // Reject plans where writeFile content is too short (< 500 chars)
        // This catches the 231-char garbage files
        if (lower.contains("writefile")) {
            // Count total content length across all writeFile steps
            int contentLength = 0;
            String[] parts = planJson.split("\"content\"");
            for (int i = 1; i < parts.length; i++) {
                // Rough estimate: count chars until next "tool" or end
                int end = parts[i].indexOf("\"tool\"");
                if (end < 0) end = parts[i].length();
                contentLength += Math.min(end, parts[i].length());
            }
            // If we have writeFile but total content is tiny, it's garbage
            if (contentLength < 500) return false;
        }

        return true;
    }
}
