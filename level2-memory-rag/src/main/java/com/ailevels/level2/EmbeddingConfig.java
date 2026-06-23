package com.ailevels.level2;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.onnx.allminilml6v2.AllMiniLmL6V2EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.pgvector.PgVectorEmbeddingStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Конфигурация эмбеддингов и векторного хранилища.
 *
 * ВАЖНО: почему НЕ используем OpenRouter для эмбеддингов?
 * OpenRouter — прокси для генерации текста, он не гарантирует эндпоинт
 * для эмбеддингов (/embeddings). Поэтому используем локальную модель
 * AllMiniLmL6V2, которая работает прямо в JVM через ONNX Runtime.
 * Преимущества:
 *   - Не нужны API-ключи и интернет для эмбеддингов
 *   - Детерминированный результат (нет версионирования API)
 *   - Бесплатно и без ограничений на количество запросов
 */
@Configuration
public class EmbeddingConfig {

    @Value("${spring.datasource.url:jdbc:postgresql://localhost:5432/ailevels}")
    private String jdbcUrl;

    @Value("${spring.datasource.username:ailevels}")
    private String username;

    @Value("${spring.datasource.password:ailevels}")
    private String password;

    /**
     * Локальная модель эмбеддингов all-MiniLM-L6-v2.
     * Производит векторы размерностью 384 — компактные и быстрые.
     * Первый вызов загружает ONNX-модель в память (~25MB), последующие — быстрые.
     */
    @Bean
    public EmbeddingModel embeddingModel() {
        return new AllMiniLmL6V2EmbeddingModel();
    }

    /**
     * Векторное хранилище на базе PostgreSQL с расширением pgvector.
     * LangChain4j автоматически создаёт таблицу с колонкой vector(384),
     * а также индекс для быстрого поиска ближайших соседей (HNSW или IVFFlat).
     *
     * Под капотом поиск — это SQL-запрос вида:
     * SELECT * FROM embeddings ORDER BY embedding <=> $1 LIMIT $2
     * где <=> — оператор косинусного расстояния из pgvector.
     */
    @Bean
    @Primary // level6 создаёт второй EmbeddingStore — этот главный (для RAG уровня 2)
    public EmbeddingStore<TextSegment> embeddingStore() {
        // Парсим параметры подключения из JDBC URL
        // jdbc:postgresql://host:port/dbname
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
                // Имя таблицы для хранения векторов документов
                .table("rag_embeddings")
                // Размерность вектора должна совпадать с моделью эмбеддингов (all-MiniLM-L6-v2 → 384)
                .dimension(384)
                // Создать таблицу, если не существует
                .createTable(true)
                .build();
    }
}
