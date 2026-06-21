package com.ailevels.level3;

import com.ailevels.common.dto.ChatRequest;
import com.ailevels.common.dto.PatternHandler;
import com.ailevels.common.dto.TraceStep;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.request.ResponseFormat;
import dev.langchain4j.model.chat.request.ResponseFormatType;
import dev.langchain4j.model.chat.request.json.JsonEnumSchema;
import dev.langchain4j.model.chat.request.json.JsonIntegerSchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonSchema;
import dev.langchain4j.model.chat.request.json.JsonSchemaElement;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ПАТТЕРН: Structured Output — структурированный вывод
 *
 * Как это работает под капотом:
 * OpenAI обучили модель на специальном поле response_format в API-запросе.
 * Когда мы передаём JSON Schema через это поле — модель ГАРАНТИРОВАННО
 * вернёт валидный JSON этой схемы. Это не промпт-инжиниринг ("верни JSON"),
 * а нативная возможность, заложенная через файн-тюнинг — аналогично tool use.
 *
 * HTTP-запрос под капотом:
 * {
 *   "model": "gpt-4o-mini",
 *   "messages": [...],
 *   "response_format": {
 *     "type": "json_schema",
 *     "json_schema": {
 *       "name": "ReviewAnalysis",
 *       "schema": { "type": "object", "properties": {...} }
 *     }
 *   }
 * }
 *
 * Демо-кейс: анализ отзыва → объект ReviewAnalysis { score, sentiment, mainComplaint, summary }
 * Попробуй: «Доставили через 3 дня вместо обещанных 1. Сам товар хороший, но логистика ужасная.»
 */
@Service
public class StructuredOutputHandler implements PatternHandler {

    private final ChatLanguageModel llm;
    private final ObjectMapper objectMapper;
    private final ResponseFormat responseFormat;

    public StructuredOutputHandler(ChatLanguageModel llm) {
        this.llm = llm;
        this.objectMapper = new ObjectMapper();

        // Описываем JSON Schema для ответа модели.
        // LangChain4j передаст это в поле response_format.json_schema API-запроса.
        // Модель обучена строго следовать этой схеме — не добавлять лишних полей,
        // не нарушать типы, не оборачивать в markdown.
        Map<String, JsonSchemaElement> properties = new LinkedHashMap<>();
        properties.put("score", JsonIntegerSchema.builder()
                .description("Оценка от 1 (очень плохо) до 5 (отлично)")
                .build());
        properties.put("sentiment", JsonEnumSchema.builder()
                .enumValues("ПОЗИТИВНЫЙ", "НЕГАТИВНЫЙ", "НЕЙТРАЛЬНЫЙ", "СМЕШАННЫЙ")
                .description("Общая тональность отзыва")
                .build());
        properties.put("mainComplaint", JsonStringSchema.builder()
                .description("Главная претензия или 'нет претензий'")
                .build());
        properties.put("summary", JsonStringSchema.builder()
                .description("Одно предложение: суть отзыва")
                .build());

        this.responseFormat = ResponseFormat.builder()
                .type(ResponseFormatType.JSON)
                .jsonSchema(JsonSchema.builder()
                        .name("ReviewAnalysis")
                        .rootElement(JsonObjectSchema.builder()
                                .properties(properties)
                                .required(List.of("score", "sentiment", "mainComplaint", "summary"))
                                .additionalProperties(false)
                                .build())
                        .build())
                .build();
    }

    // Java-структура для десериализации ответа модели
    public record ReviewAnalysis(
            int score,
            String sentiment,
            String mainComplaint,
            String summary
    ) {}

    @Override public String level() { return "3"; }
    @Override public String id() { return "structured-output"; }
    @Override public String title() { return "Structured Output"; }
    @Override public String description() {
        return "LLM возвращает гарантированный JSON через response_format (не промпт-инжиниринг!). " +
               "Демо: анализ отзыва → оценка, тональность, претензия. Введи текст отзыва.";
    }

    @Override
    public com.ailevels.common.dto.ChatResponse handle(ChatRequest request) {
        List<TraceStep> trace = new ArrayList<>();

        trace.add(TraceStep.info("Как это работает",
                "Используем response_format.json_schema в API — нативная возможность модели, " +
                "заложенная через файн-тюнинг. В отличие от промпта «верни JSON», " +
                "здесь модель ГАРАНТИРОВАННО вернёт валидный JSON — нарушить схему невозможно."));

        String schemaStr = """
                {
                  "score": integer (1-5),
                  "sentiment": "ПОЗИТИВНЫЙ" | "НЕГАТИВНЫЙ" | "НЕЙТРАЛЬНЫЙ" | "СМЕШАННЫЙ",
                  "mainComplaint": string,
                  "summary": string
                }""";
        trace.add(TraceStep.info("JSON Schema → поле response_format в API-запросе", schemaStr));
        trace.add(TraceStep.prompt("Сообщения", "System: Ты аналитик отзывов.\nUser: " + request.message()));

        // Формируем ChatRequest с response_format — LangChain4j добавит его в HTTP-запрос
        dev.langchain4j.model.chat.request.ChatRequest chatRequest =
                dev.langchain4j.model.chat.request.ChatRequest.builder()
                        .messages(List.of(
                                SystemMessage.from("Ты аналитик отзывов. Анализируй объективно."),
                                UserMessage.from("Проанализируй отзыв: \"" + request.message() + "\"")
                        ))
                        .responseFormat(responseFormat)
                        .build();

        // Отправляем — модель вернёт строго валидный JSON без лишнего текста
        dev.langchain4j.model.chat.response.ChatResponse chatResponse = llm.chat(chatRequest);
        String rawJson = chatResponse.aiMessage().text();
        trace.add(TraceStep.model("Ответ модели (гарантированный JSON)", rawJson));

        String humanAnswer;
        try {
            ReviewAnalysis analysis = objectMapper.readValue(rawJson, ReviewAnalysis.class);
            humanAnswer = formatAnalysis(analysis);
            trace.add(TraceStep.info("Десериализованный Java-объект",
                    String.format("score=%d | sentiment=%s\nmainComplaint=%s\nsummary=%s",
                            analysis.score(), analysis.sentiment(),
                            analysis.mainComplaint(), analysis.summary())));
        } catch (Exception e) {
            humanAnswer = "Ошибка десериализации: " + e.getMessage() + "\n\nJSON:\n" + rawJson;
            trace.add(TraceStep.info("Ошибка", e.getMessage()));
        }

        return new com.ailevels.common.dto.ChatResponse(humanAnswer, trace);
    }

    private String formatAnalysis(ReviewAnalysis analysis) {
        int score = Math.max(0, Math.min(5, analysis.score()));
        String stars = "⭐".repeat(score) + "☆".repeat(5 - score);
        return String.format("""
                📊 Анализ отзыва:

                Оценка: %s (%d/5)
                Тональность: %s
                Претензия: %s
                Резюме: %s
                """,
                stars, analysis.score(),
                analysis.sentiment(),
                analysis.mainComplaint(),
                analysis.summary()
        );
    }
}
