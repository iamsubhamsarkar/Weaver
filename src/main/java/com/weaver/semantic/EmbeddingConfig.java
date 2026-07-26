package com.weaver.semantic;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.onnx.allminilml6v2q.AllMiniLmL6V2QuantizedEmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.chroma.ChromaEmbeddingStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class EmbeddingConfig {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingConfig.class);

    @Value("${weaver.chroma.url:http://localhost:8000}")
    private String chromaUrl;

    @Value("${weaver.chroma.collection:weaver-experience}")
    private String collectionName;

    @Bean
    public EmbeddingModel embeddingModel() {
        log.info("Loading local ONNX embedding model (AllMiniLmL6V2-Quantized)...");
        return new AllMiniLmL6V2QuantizedEmbeddingModel();
    }

    @Bean
    public EmbeddingStore<TextSegment> embeddingStore() {
        try {
            ChromaEmbeddingStore store = ChromaEmbeddingStore.builder()
                    .baseUrl(chromaUrl)
                    .collectionName(collectionName)
                    .build();
            log.info("Connected to ChromaDB at {} (collection: {})", chromaUrl, collectionName);
            return store;
        } catch (Exception e) {
            log.warn("ChromaDB not available at {}. Semantic cache disabled.", chromaUrl);
            return new NoOpEmbeddingStore();
        }
    }

    private static class NoOpEmbeddingStore implements EmbeddingStore<TextSegment> {
        @Override
        public String add(dev.langchain4j.data.embedding.Embedding embedding) { return "noop"; }
        @Override
        public void add(String id, dev.langchain4j.data.embedding.Embedding embedding) {}
        @Override
        public String add(dev.langchain4j.data.embedding.Embedding embedding, TextSegment embedded) { return "noop"; }
        @Override
        public List<String> addAll(List<dev.langchain4j.data.embedding.Embedding> embeddings) {
            return List.of();
        }
        @Override
        public dev.langchain4j.store.embedding.EmbeddingSearchResult<TextSegment> search(
                dev.langchain4j.store.embedding.EmbeddingSearchRequest request) {
            return new dev.langchain4j.store.embedding.EmbeddingSearchResult<>(List.of());
        }
    }
}
