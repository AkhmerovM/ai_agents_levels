package com.ailevels.level1;

import com.ailevels.common.dto.ChatRequest;
import com.ailevels.common.dto.ChatResponse;
import com.ailevels.common.dto.PatternHandler;
import com.ailevels.common.dto.TraceStep;
import dev.langchain4j.model.chat.ChatLanguageModel;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * ПАТТЕРН: Chain-of-Thought (CoT) — цепочка рассуждений
 *
 * Идея: добавляем в промпт инструкцию «рассуждай пошагово» (или пример пошагового
 * рассуждения). Это заставляет модель «думать вслух», что значительно улучшает
 * результаты на логических и математических задачах.
 *
 * Почему это работает? При авторегрессивной генерации каждый токен «видит» предыдущие.
 * Явное рассуждение создаёт промежуточный контекст, который помогает модели прийти
 * к правильному финальному ответу.
 *
 * Демо-кейс: задачи на логику/математику.
 * Попробуй: «У Ани было 5 яблок. Она отдала половину Пете, Петя съел 1 и вернул Ане.
 *            Сколько яблок у Ани?»
 */
@Service
public class ChainOfThoughtHandler implements PatternHandler {

    private final ChatLanguageModel llm;

    // Системная инструкция для CoT: просим рассуждать пошагово
    private static final String SYSTEM_PROMPT = """
            Ты точный и внимательный помощник. При решении любой задачи ОБЯЗАТЕЛЬНО:
            1. Разбей задачу на шаги и рассуди вслух по каждому шагу
            2. Явно укажи, что делаешь на каждом шаге
            3. В конце напиши ИТОГ: и дай финальный ответ

            Формат ответа:
            Шаг 1: [рассуждение]
            Шаг 2: [рассуждение]
            ...
            ИТОГ: [финальный ответ]""";

    public ChainOfThoughtHandler(ChatLanguageModel llm) {
        this.llm = llm;
    }

    @Override public String level() { return "1"; }
    @Override public String id() { return "chain-of-thought"; }
    @Override public String title() { return "Chain-of-Thought"; }
    @Override public String description() {
        return "Модель получает инструкцию рассуждать пошагово. " +
               "Попробуй логическую или математическую задачку — " +
               "и сравни с zero-shot на том же вопросе.";
    }

    @Override
    public ChatResponse handle(ChatRequest request) {
        // Формируем промпт: системная инструкция CoT + вопрос пользователя
        String fullPrompt = SYSTEM_PROMPT + "\n\nЗадача: " + request.message();

        // Отправляем в LLM и получаем развёрнутый ответ с рассуждениями
        String rawAnswer = llm.generate(fullPrompt);

        // Пытаемся выделить финальный ответ после «ИТОГ:»
        String finalAnswer = rawAnswer;
        String reasoning = rawAnswer;
        int итогIndex = rawAnswer.indexOf("ИТОГ:");
        if (итогIndex >= 0) {
            reasoning = rawAnswer.substring(0, итогIndex).trim();
            finalAnswer = rawAnswer.substring(итогIndex).trim();
        }

        // Трассировка: показываем системную инструкцию, рассуждения и финальный ответ отдельно
        List<TraceStep> trace = new ArrayList<>();
        trace.add(TraceStep.info("Техника", "Chain-of-Thought: модель получает инструкцию рассуждать пошагово"));
        trace.add(TraceStep.prompt("Системная инструкция CoT", SYSTEM_PROMPT));
        trace.add(TraceStep.prompt("Вопрос пользователя", request.message()));
        if (итогIndex >= 0) {
            trace.add(TraceStep.model("Пошаговые рассуждения", reasoning));
            trace.add(TraceStep.model("Финальный ответ", finalAnswer));
        } else {
            trace.add(TraceStep.model("Полный ответ модели", rawAnswer));
        }

        return new ChatResponse(rawAnswer, trace);
    }
}
