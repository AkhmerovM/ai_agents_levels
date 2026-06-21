package com.ailevels.common.dto;

/**
 * Общий интерфейс для всех паттернов работы с LLM.
 * Каждый паттерн регистрируется как Spring-бин и автоматически
 * подхватывается контроллером в модуле app.
 */
public interface PatternHandler {

    /** Уровень: "1", "2" или "3" */
    String level();

    /** Уникальный идентификатор паттерна: "few-shot", "rag", "function-calling" и т.д. */
    String id();

    /** Человекочитаемое название для отображения в UI */
    String title();

    /** Краткое описание того, что демонстрирует паттерн */
    String description();

    /** Обработать запрос и вернуть ответ с трассировкой */
    ChatResponse handle(ChatRequest request);
}
