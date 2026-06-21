package com.ailevels.level1;

import com.ailevels.common.dto.ChatRequest;
import com.ailevels.common.dto.ChatResponse;
import com.ailevels.common.dto.PatternHandler;
import com.ailevels.common.dto.TraceStep;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.input.Prompt;
import dev.langchain4j.model.input.PromptTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * ПАТТЕРН: Zero-Shot (базовая линия)
 *
 * Самый простой вариант: вопрос отправляется в LLM «как есть», без каких-либо
 * примеров, инструкций по рассуждению или ролей.
 *
 * Зачем он нужен? Чтобы было с чем сравнивать другие паттерны уровня 1.
 * Попробуй задать один и тот же вопрос zero-shot и few-shot — разница будет заметна.
 */
@Service
public class ZeroShotHandler implements PatternHandler {

    private final ChatLanguageModel llm;

    public ZeroShotHandler(ChatLanguageModel llm) {
        this.llm = llm;
    }

    @Override public String level() { return "1"; }
    @Override public String id() { return "zero-shot"; }
    @Override public String title() { return "Zero-Shot (базовая линия)"; }
    @Override public String description() {
        return "Вопрос отправляется в LLM без примеров и без дополнительных инструкций. " +
               "Используй как базу для сравнения с few-shot и chain-of-thought.";
    }

    @Override
    public ChatResponse handle(ChatRequest request) {
        // Простейший промпт — просто вопрос пользователя
        String userMessage = request.message();

        // Отправляем в LLM и получаем ответ
        String answer = llm.generate(userMessage);

        // Трассировка: показываем, что именно ушло в модель
        List<TraceStep> trace = List.of(
                TraceStep.prompt("Промпт (без приёмов)", userMessage),
                TraceStep.model("Ответ модели", answer)
        );

        return new ChatResponse(answer, trace);
    }
}
