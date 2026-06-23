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
 * ПАТТЕРН: Reflexion — рефлексия и самоулучшение
 *
 * Идея: после первой попытки агент сам оценивает свой ответ,
 * формулирует «что пошло не так» и делает вторую попытку с учётом этого.
 *
 * Цикл: Попытка → Рефлексия (самооценка) → Улучшенная попытка
 * Повторяется до MAX_ATTEMPTS или до оценки «хорошо».
 *
 * Почему это важно?
 * Это имитирует то, как человек улучшает свою работу через саморефлексию.
 * LLM часто делает ошибки в первой попытке, которые она же может исправить
 * если явно попросить её оценить и переделать.
 *
 * Попробуй задачу, где легко ошибиться:
 * «Напиши 5 слов, в каждом из которых ровно 3 буквы "а"»
 * «Перечисли 5 стран, название которых начинается и заканчивается на одну букву»
 */
@Service
public class ReflexionHandler implements PatternHandler {

    private static final int MAX_ATTEMPTS = 3;

    private final ChatLanguageModel llm;

    public ReflexionHandler(ChatLanguageModel llm) {
        this.llm = llm;
    }

    @Override public String level() { return "7"; }
    @Override public String id() { return "reflexion"; }
    @Override public String title() { return "Reflexion (рефлексия и улучшение)"; }
    @Override public String description() {
        return "Агент делает попытку → сам оценивает ошибки → переделывает (до 3 раз). " +
               "Попробуй: «Напиши 5 слов, в каждом ровно 3 буквы а»";
    }

    @Override
    public ChatResponse handle(ChatRequest request) {
        List<TraceStep> trace = new ArrayList<>();
        trace.add(TraceStep.info("Паттерн Reflexion", "До " + MAX_ATTEMPTS + " попыток. Цикл: попытка → самооценка → улучшение"));

        String currentAnswer = null;
        String reflection = null;

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            // ─── ПОПЫТКА ──────────────────────────────────────────────────
            String attemptPrompt = attempt == 1
                    ? request.message()
                    : """
                      Задача: %s

                      Твоя предыдущая попытка:
                      %s

                      Твоя рефлексия (что пошло не так):
                      %s

                      Сделай улучшенную попытку, исправив все найденные ошибки:
                      """.formatted(request.message(), currentAnswer, reflection);

            currentAnswer = llm.generate(attemptPrompt);
            trace.add(TraceStep.model("Попытка " + attempt + "/" + MAX_ATTEMPTS, currentAnswer));

            if (attempt == MAX_ATTEMPTS) break;

            // ─── РЕФЛЕКСИЯ: агент оценивает свой ответ ────────────────────
            String reflectionPrompt = """
                    Ты строгий критик. Проверь ответ на задачу.

                    Задача: %s

                    Ответ:
                    %s

                    Оцени строго:
                    1. Правильно ли выполнено задание? Проверь КАЖДЫЙ пункт
                    2. Если есть ошибки — перечисли их конкретно
                    3. Оцени: ПРИНЯТО (ошибок нет) или ДОРАБОТАТЬ (есть ошибки)

                    Рефлексия:""".formatted(request.message(), currentAnswer);

            reflection = llm.generate(reflectionPrompt);
            trace.add(TraceStep.info("🔍 Рефлексия (попытка " + attempt + ")", reflection));

            // Если агент сам говорит «ПРИНЯТО» — останавливаемся
            if (reflection.contains("ПРИНЯТО")) {
                trace.add(TraceStep.info("✅ Агент доволен результатом", "Остановка после попытки " + attempt));
                break;
            }

            trace.add(TraceStep.info("🔄 Есть ошибки — идём на следующую попытку", ""));
        }

        trace.add(TraceStep.info("Итого попыток", String.valueOf(
                trace.stream().filter(t -> t.label().startsWith("Попытка")).count())));

        return new ChatResponse(currentAnswer, trace);
    }
}
