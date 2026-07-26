package com.weaver.semantic;

import com.weaver.config.WeaverConfigProperties;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
public class ExperienceLibraryService {

    private static final Logger log = LoggerFactory.getLogger(ExperienceLibraryService.class);

    private final EmbeddingModel embeddingModel;
    private final EmbeddingStore<TextSegment> embeddingStore;
    private final WeaverConfigProperties config;

    public ExperienceLibraryService(EmbeddingModel embeddingModel,
                                     EmbeddingStore<TextSegment> embeddingStore,
                                     WeaverConfigProperties config) {
        this.embeddingModel = embeddingModel;
        this.embeddingStore = embeddingStore;
        this.config = config;
    }

    public Optional<String> lookupCachedSolution(String userPrompt) {
        try {
            Embedding queryEmbedding = embeddingModel.embed(userPrompt).content();
            EmbeddingSearchRequest request = EmbeddingSearchRequest.builder()
                    .queryEmbedding(queryEmbedding)
                    .maxResults(config.getCacheMaxResults())
                    .minScore(config.getSemanticMinScore())
                    .build();

            EmbeddingSearchResult<TextSegment> result = embeddingStore.search(request);
            List<EmbeddingMatch<TextSegment>> matches = result.matches();

            if (!matches.isEmpty()) {
                EmbeddingMatch<TextSegment> best = matches.get(0);
                log.info("Semantic cache HIT (score: {})", best.score());
                return Optional.of(best.embedded().text());
            }
        } catch (Exception e) {
            log.warn("Semantic cache lookup failed (ChromaDB down?): {}", e.getMessage());
        }
        return Optional.empty();
    }

    public void storeSolution(String userPrompt, String solution) {
        try {
            Embedding embedding = embeddingModel.embed(userPrompt).content();
            TextSegment segment = TextSegment.from(solution);
            embeddingStore.add(embedding, segment);
            log.info("Stored solution in experience library");
        } catch (Exception e) {
            log.warn("Failed to store in experience library: {}", e.getMessage());
        }
    }
}
