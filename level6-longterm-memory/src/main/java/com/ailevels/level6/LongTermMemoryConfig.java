package com.ailevels.level6;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.pgvector.PgVectorEmbeddingStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Конфигурация отдельного векторного хранилища для долгой памяти.
 *
 * Отличие от level2 (RAG):
 *   level2: таблица "rag_embeddings" — документы магазина, общие для всех
 *   level6: таблица "longterm_memory" — воспоминания, привязанные к userId
 *
 * Оба хранилища живут в одной PostgreSQL БД, но в разных таблицах.
 * Бин помечен @Bean("longTermMemoryStore"), чтобы не конфликтовать с @Primary из level2.
 */
@Configuration
public class LongTermMemoryConfig {

    @Value("${spring.datasource.url:jdbc:postgresql://localhost:5432/ailevels}")
    private String jdbcUrl;

    @Value("${spring.datasource.username:ailevels}")
    private String username;

    @Value("${spring.datasource.password:ailevels}")
    private String password;

    @Bean("longTermMemoryStore")
    public EmbeddingStore<TextSegment> longTermMemoryStore() {
        String withoutPrefix = jdbcUrl.replace("jdbc:postgresql://", "");
        String[] hostAndRest = withoutPrefix.split("/", 2);
        String[] hostPort = hostAndRest[0].split(":");
        String host = hostPort[0];
        int port = hostPort.length > 1 ? Integer.parseInt(hostPort[1]) : 5432;
        String database = hostAndRest.length > 1 ? hostAndRest[1].split("\\?")[0] : "ailevels";

        return PgVectorEmbeddingStore.builder()
                .host(host)
                .port(port)
                .database(database)
                .user(username)
                .password(password)
                .table("longterm_memory")  // отдельная таблица!
                .dimension(384)
                .createTable(true)
                .build();
    }
}
