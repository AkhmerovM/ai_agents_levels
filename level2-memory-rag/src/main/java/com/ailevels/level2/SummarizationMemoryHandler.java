package com.ailevels.level2;

import com.ailevels.common.dto.ChatRequest;
import com.ailevels.common.dto.ChatResponse;
import com.ailevels.common.dto.PatternHandler;
import com.ailevels.common.dto.TraceStep;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ПАТТЕРН: Summarization Memory — память с суммаризацией
 *
 * Проблема со скользящим окном: при большом диалоге старый контекст теряется.
 * Решение: когда история становится длинной, «сворачиваем» её в краткое резюме
 * с помощью дополнительного вызова LLM, и дальше работаем с «резюме + свежие сообщения».
 *
 * Это позволяет сохранять важные факты из начала диалога, не передавая весь текст.
 *
 * Попробуй: расскажи о себе несколько фактов в отдельных сообщениях,
 * наберёшь порог — увидишь, как история схлопывается в резюме.
 * Потом спроси о себе — модель должна помнить из резюме.
 */
@Service
public class SummarizationMemoryHandler implements PatternHandler {

    private final ChatLanguageModel llm;

    // Состояние памяти каждой сессии
    private final Map<String, SessionState> sessions = new ConcurrentHashMap<>();

    // Порог: при достижении этого числа обменов запускаем суммаризацию
    private static final int SUMMARIZE_THRESHOLD = 4; // 4 пары вопрос-ответ

    // После суммаризации сколько последних обменов оставляем «свежими»
    private static final int KEEP_RECENT = 2;

    public SummarizationMemoryHandler(ChatLanguageModel llm) {
        this.llm = llm;
    }

    @Override public String level() { return "2"; }
    @Override public String id() { return "summarization"; }
    @Override public String title() { return "Summarization Memory"; }
    @Override public String description() {
        return "После " + SUMMARIZE_THRESHOLD + " обменов старая история сворачивается в резюме. " +
               "В промпт идут: резюме + последние " + KEEP_RECENT + " сообщения.";
    }

    @Override
    public ChatResponse handle(ChatRequest request) {
        String sessionId = request.sessionId() != null ? request.sessionId() : "default";
        SessionState state = sessions.computeIfAbsent(sessionId, id -> new SessionState());

        List<TraceStep> trace = new ArrayList<>();

        // Добавляем новое сообщение в историю
        state.history.add(new Exchange(request.message(), null));

        // Проверяем: нужна ли суммаризация?
        // Суммаризируем полностью завершённые обмены (у которых есть ответ), не текущий
        long completedExchanges = state.history.stream().filter(e -> e.answer != null).count();

        if (completedExchanges >= SUMMARIZE_THRESHOLD) {
            // Суммаризируем старые обмены, оставляя последние KEEP_RECENT
            int keepFrom = (int) completedExchanges - KEEP_RECENT;
            List<Exchange> toSummarize = state.history.stream()
                .filter(e -> e.answer != null)
                .limit(keepFrom)
                .toList();

            if (!toSummarize.isEmpty()) {
                String summarizationInput = buildHistoryText(toSummarize);
                String newSummary = summarizeHistory(summarizationInput);

                trace.add(TraceStep.info("Суммаризация запущена",
                    "История достигла " + completedExchanges + " обменов. Суммаризируем первые " + toSummarize.size()));
                trace.add(TraceStep.info("Исходная история (до суммаризации)", summarizationInput));
                trace.add(TraceStep.info("Резюме (результат суммаризации)", newSummary));

                // Обновляем резюме и удаляем старые обмены из истории
                state.summary = newSummary;
                state.history.removeIf(e -> toSummarize.contains(e));
            }
        }

        // Строим промпт: резюме + свежие завершённые обмены + текущий вопрос
        StringBuilder promptBuilder = new StringBuilder();
        promptBuilder.append("Ты дружелюбный ассистент. Контекст разговора:\n\n");

        if (state.summary != null && !state.summary.isEmpty()) {
            promptBuilder.append("=== Резюме предыдущего разговора ===\n");
            promptBuilder.append(state.summary).append("\n\n");
            trace.add(TraceStep.memory("Резюме в промпте", state.summary));
        }

        // Добавляем свежие завершённые обмены
        List<Exchange> recentCompleted = state.history.stream()
            .filter(e -> e.answer != null)
            .toList();
        if (!recentCompleted.isEmpty()) {
            promptBuilder.append("=== Последние сообщения ===\n");
            for (Exchange ex : recentCompleted) {
                promptBuilder.append("Пользователь: ").append(ex.question).append("\n");
                promptBuilder.append("Ассистент: ").append(ex.answer).append("\n\n");
                trace.add(TraceStep.memory("Свежее сообщение в окне", ex.question + "\n→ " + ex.answer));
            }
        }

        promptBuilder.append("Текущий вопрос пользователя: ").append(request.message());
        String fullPrompt = promptBuilder.toString();
        trace.add(TraceStep.prompt("Итоговый промпт", fullPrompt));

        // Отправляем в LLM
        String answer = llm.generate(fullPrompt);
        trace.add(TraceStep.model("Ответ модели", answer));

        // Записываем ответ в последний обмен
        Exchange current = state.history.get(state.history.size() - 1);
        current.answer = answer;

        return new ChatResponse(answer, trace);
    }

    /** Дополнительный вызов LLM для суммаризации истории диалога */
    private String summarizeHistory(String historyText) {
        String summarizationPrompt = """
                Создай краткое резюме следующего диалога, сохранив все важные факты,
                имена, предпочтения и договорённости. Резюме должно быть компактным (3-5 предложений).

                Диалог:
                %s

                Резюме:""".formatted(historyText);

        return llm.generate(summarizationPrompt);
    }

    private String buildHistoryText(List<Exchange> exchanges) {
        StringBuilder sb = new StringBuilder();
        for (Exchange ex : exchanges) {
            sb.append("Пользователь: ").append(ex.question).append("\n");
            sb.append("Ассистент: ").append(ex.answer).append("\n\n");
        }
        return sb.toString().trim();
    }

    public void clearSession(String sessionId) {
        sessions.remove(sessionId);
    }

    // Состояние одной сессии
    private static class SessionState {
        String summary = null;              // Резюме старых обменов
        List<Exchange> history = new ArrayList<>();  // Текущая история
    }

    // Один обмен: вопрос + ответ
    private static class Exchange {
        String question;
        String answer;

        Exchange(String question, String answer) {
            this.question = question;
            this.answer = answer;
        }
    }
}
