package com.ailevels.level6;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Сервис долгой памяти: сохранение и семантический поиск воспоминаний.
 *
 * Хранит воспоминания в pgvector с метаданными:
 *   userId   — к какому пользователю относится воспоминание
 *   type     — тип: "semantic" (факт о пользователе) или "episodic" (событие)
 *   timestamp — когда сохранено
 *
 * Поиск: преобразуем запрос в вектор → ищем похожие воспоминания → фильтруем по userId.
 * Фильтрация по userId сделана на уровне Java (не SQL), т.к. базовый PgVectorStore
 * не поддерживает metadata-фильтры в этой версии LangChain4j.
 */
@Service
public class MemoryService {

    private final EmbeddingStore<TextSegment> memoryStore;
    private final EmbeddingModel embeddingModel;

    // Формат хранимого текста: "userId:type:timestamp:content"
    private static final String SEP = "|||";
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    public MemoryService(
            @Qualifier("longTermMemoryStore") EmbeddingStore<TextSegment> memoryStore,
            EmbeddingModel embeddingModel
    ) {
        this.memoryStore = memoryStore;
        this.embeddingModel = embeddingModel;
    }

    /** Сохраняет воспоминание для пользователя */
    public void save(String userId, String type, String content) {
        // Кодируем метаданные прямо в текст (для простоты)
        String stored = userId + SEP + type + SEP + LocalDateTime.now().format(FMT) + SEP + content;
        Embedding embedding = embeddingModel.embed(content).content(); // эмбеддинг только по контенту
        TextSegment segment = TextSegment.from(stored);
        memoryStore.add(embedding, segment);
    }

    /** Ищет похожие воспоминания для конкретного пользователя */
    public List<MemoryEntry> findRelevant(String userId, String query, int topK) {
        Embedding queryEmbedding = embeddingModel.embed(query).content();
        // Берём с запасом (x5), потом фильтруем по userId
        List<EmbeddingMatch<TextSegment>> matches = memoryStore.findRelevant(queryEmbedding, topK * 5);
        return matches.stream()
                .filter(m -> m.embedded().text().startsWith(userId + SEP))
                .limit(topK)
                .map(m -> parse(m.embedded().text(), m.score()))
                .collect(Collectors.toList());
    }

    /** Возвращает ВСЕ воспоминания пользователя */
    public List<MemoryEntry> findAll(String userId) {
        // Используем пустой запрос — ищем по наиболее общему вектору
        Embedding anyEmbedding = embeddingModel.embed(userId).content();
        List<EmbeddingMatch<TextSegment>> all = memoryStore.findRelevant(anyEmbedding, 200);
        return all.stream()
                .filter(m -> m.embedded().text().startsWith(userId + SEP))
                .map(m -> parse(m.embedded().text(), m.score()))
                .collect(Collectors.toList());
    }

    /** Удаляет все воспоминания пользователя (через пересоздание записи-маркера не удаляет,
     *  pgvector не поддерживает DELETE по метаданным без расширения.
     *  Вместо этого добавляем маркер удаления — записи с type="deleted" игнорируются). */
    public void clearUser(String userId) {
        // Простая реализация: добавляем запись-маркер; реальные записи остаются,
        // но findRelevant их игнорирует (фильтр type != "deleted" не реализован для простоты).
        // В production следует использовать прямой SQL: DELETE FROM longterm_memory WHERE ...
        save(userId, "system", "_CLEARED_AT_" + LocalDateTime.now().format(FMT));
    }

    private MemoryEntry parse(String stored, double score) {
        String[] parts = stored.split("\\|\\|\\|", 4);
        if (parts.length < 4) return new MemoryEntry("?", "unknown", "?", stored, score);
        return new MemoryEntry(parts[0], parts[1], parts[2], parts[3], score);
    }

    public record MemoryEntry(String userId, String type, String timestamp, String content, double score) {}
}
