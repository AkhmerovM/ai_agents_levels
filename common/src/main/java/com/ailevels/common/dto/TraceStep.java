package com.ailevels.common.dto;

/**
 * Один шаг «трассировки» — запись о том, что произошло внутри паттерна.
 * Это ключевой объект для обучения: пользователь видит не просто ответ,
 * а весь процесс: какой промпт ушёл, что вернула модель, какой инструмент вызвался.
 *
 * type — тип шага:
 *   "prompt"       — итоговый промпт, отправленный в LLM
 *   "model"        — ответ от LLM
 *   "tool-call"    — запрос модели на вызов инструмента (имя + аргументы)
 *   "tool-result"  — результат выполнения инструмента
 *   "retrieved"    — чанки, найденные в векторном хранилище (RAG)
 *   "memory"       — сообщения, попавшие в окно памяти
 *   "info"         — любая другая поясняющая информация
 */
public record TraceStep(String type, String label, String content) {

    // Удобные фабричные методы для каждого типа шага

    public static TraceStep prompt(String label, String content) {
        return new TraceStep("prompt", label, content);
    }

    public static TraceStep model(String label, String content) {
        return new TraceStep("model", label, content);
    }

    public static TraceStep toolCall(String label, String content) {
        return new TraceStep("tool-call", label, content);
    }

    public static TraceStep toolResult(String label, String content) {
        return new TraceStep("tool-result", label, content);
    }

    public static TraceStep retrieved(String label, String content) {
        return new TraceStep("retrieved", label, content);
    }

    public static TraceStep memory(String label, String content) {
        return new TraceStep("memory", label, content);
    }

    public static TraceStep info(String label, String content) {
        return new TraceStep("info", label, content);
    }
}
