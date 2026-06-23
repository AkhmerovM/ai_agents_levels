package com.ailevels.level7;

import com.ailevels.common.dto.ChatRequest;
import com.ailevels.common.dto.ChatResponse;
import com.ailevels.common.dto.PatternHandler;
import com.ailevels.common.dto.TraceStep;
import dev.langchain4j.model.chat.ChatLanguageModel;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * ПАТТЕРН: Self-Critique — самокритика и улучшение
 *
 * Проще Reflexion: всего два прохода.
 *   1. Черновик: агент пишет первый ответ свободно
 *   2. Критика: агент критикует свой же черновик (другой «голос»)
 *   3. Финал: агент пишет улучшенную версию с учётом критики
 *
 * Отличие от Reflexion:
 *   Reflexion: итеративный (много попыток), фокус на исправлении ошибок
 *   Self-Critique: линейный (черновик→критика→финал), фокус на улучшении качества
 *
 * Попробуй: «Напиши Email коллеге с просьбой перенести дедлайн»
 *           «Объясни концепцию Docker для менеджера проекта»
 */
@Service
public class SelfCritiqueHandler implements PatternHandler {

    private final ChatLanguageModel llm;

    public SelfCritiqueHandler(ChatLanguageModel llm) {
        this.llm = llm;
    }

    @Override public String level() { return "7"; }
    @Override public String id() { return "self-critique"; }
    @Override public String title() { return "Self-Critique (черновик → критика → финал)"; }
    @Override public String description() {
        return "Черновик → агент критикует себя → финальная улучшенная версия. " +
               "Попробуй: «Напиши письмо коллеге с просьбой перенести дедлайн»";
    }

    @Override
    public ChatResponse handle(ChatRequest request) {
        List<TraceStep> trace = new ArrayList<>();
        trace.add(TraceStep.info("Паттерн Self-Critique", "Черновик → Критика → Финальная версия"));

        // ─── ШАГ 1: ЧЕРНОВИК ──────────────────────────────────────────────
        // Агент пишет первый вариант без особых ограничений
        String draft = llm.generate("Напиши ответ на следующую задачу:\n\n" + request.message());
        trace.add(TraceStep.model("📝 Черновик (первая версия)", draft));

        // ─── ШАГ 2: САМОКРИТИКА ───────────────────────────────────────────
        // Тот же агент, но в роли критика — намеренно ищет слабые места
        String critiquePrompt = """
                Ты строгий редактор. Прочитай этот текст и найди все его слабые места.

                Задача была: %s

                Текст для критики:
                %s

                Оцени по критериям:
                • Полнота и конкретность (достаточно ли деталей?)
                • Тон и стиль (соответствует ли задаче?)
                • Структура (легко ли читать?)
                • Пропущенные важные моменты
                • Лишнее, что можно убрать

                Напиши критику по каждому пункту. Будь конкретен и строг.
                """.formatted(request.message(), draft);

        String critique = llm.generate(critiquePrompt);
        trace.add(TraceStep.info("🔍 Самокритика (агент критикует свой черновик)", critique));

        // ─── ШАГ 3: ФИНАЛЬНАЯ ВЕРСИЯ ──────────────────────────────────────
        // Агент переписывает с учётом своей же критики
        String finalPrompt = """
                Задача: %s

                Твой черновик:
                %s

                Критика черновика:
                %s

                Напиши финальную, улучшенную версию, устранив ВСЕ замечания из критики.
                Не упоминай процесс редактирования — просто дай лучший ответ.
                """.formatted(request.message(), draft, critique);

        String finalAnswer = llm.generate(finalPrompt);
        trace.add(TraceStep.model("✅ Финальная версия (после самокритики)", finalAnswer));

        return new ChatResponse(finalAnswer, trace);
    }
}
