package com.ailevels.level5;

import com.ailevels.common.dto.ChatRequest;
import com.ailevels.common.dto.ChatResponse;
import com.ailevels.common.dto.PatternHandler;
import com.ailevels.common.dto.TraceStep;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * ПАТТЕРН: Debate — дебаты агентов с судьёй
 *
 * Идея: по спорному вопросу 2 агента занимают противоположные позиции
 * и проводят несколько раундов дебатов. Агент-судья выносит финальный вердикт.
 *
 * Зачем это нужно?
 *   - Помогает найти слабые места в аргументах
 *   - Позволяет рассмотреть проблему с разных сторон
 *   - Снижает confirmation bias (предвзятость подтверждения) одного агента
 *
 * Попробуй: «Что лучше: ноутбук ProBook за 45000 или планшет за 15000?»
 *           «Стоит ли покупать дорогие наушники или хватит бюджетных?»
 */
@Service
public class DebateHandler implements PatternHandler {

    private static final int DEBATE_ROUNDS = 2;

    private final ChatLanguageModel llm;

    public DebateHandler(ChatLanguageModel llm) {
        this.llm = llm;
    }

    @Override public String level() { return "5"; }
    @Override public String id() { return "debate"; }
    @Override public String title() { return "Debate (дебаты + судья)"; }
    @Override public String description() {
        return "2 агента с противоположными позициями спорят, судья выбирает победителя. " +
               "Попробуй: «Что лучше для офиса: ноутбук за 45000 или планшет за 15000?»";
    }

    @Override
    public ChatResponse handle(ChatRequest request) {
        List<TraceStep> trace = new ArrayList<>();
        trace.add(TraceStep.info("Архитектура",
                "Агент А vs Агент Б → " + DEBATE_ROUNDS + " раунда дебатов → Судья выносит вердикт"));

        String topic = request.message();

        // ─── НАЧАЛЬНЫЕ ПОЗИЦИИ ────────────────────────────────────────────
        // Агент А занимает ПЕРВУЮ позицию (за первый вариант / «да»)
        String positionA = debate("Агент А",
                "Ты участник дебатов. Твоя задача — убедительно отстаивать ПЕРВЫЙ вариант " +
                "или позицию «ЗА» по данному вопросу. Аргументируй конкретно.",
                "Тема дебатов: " + topic + "\nПредставь свою начальную позицию (3-4 аргумента).",
                trace);

        // Агент Б занимает ВТОРУЮ позицию (за второй вариант / «нет»)
        String positionB = debate("Агент Б",
                "Ты участник дебатов. Твоя задача — убедительно отстаивать ВТОРОЙ вариант " +
                "или позицию «ПРОТИВ» по данному вопросу. Аргументируй конкретно.",
                "Тема дебатов: " + topic + "\nАргументы оппонента:\n" + positionA +
                "\nПредставь свою позицию и возрази оппоненту (3-4 аргумента).",
                trace);

        String lastA = positionA;
        String lastB = positionB;

        // ─── РАУНДЫ ДЕБАТОВ ───────────────────────────────────────────────
        for (int round = 1; round <= DEBATE_ROUNDS; round++) {
            trace.add(TraceStep.info("─── Раунд " + round + "/" + DEBATE_ROUNDS + " ───", ""));

            String responseA = debate("Агент А (раунд " + round + ")",
                    "Ты участник дебатов. Отстаивай первый вариант, отвечай на аргументы оппонента.",
                    "Тема: " + topic + "\nАргументы оппонента:\n" + lastB + "\nОтветь и усиль свою позицию.",
                    trace);

            String responseB = debate("Агент Б (раунд " + round + ")",
                    "Ты участник дебатов. Отстаивай второй вариант, отвечай на аргументы оппонента.",
                    "Тема: " + topic + "\nАргументы оппонента:\n" + responseA + "\nОтветь и усиль свою позицию.",
                    trace);

            lastA = responseA;
            lastB = responseB;
        }

        // ─── СУДЬЯ ВЫНОСИТ ВЕРДИКТ ────────────────────────────────────────
        String judgePrompt = """
                Ты беспристрастный судья. Изучи дебаты и вынеси обоснованный вердикт.

                Тема: %s

                Позиция Агента А (первый вариант):
                %s

                Позиция Агента Б (второй вариант):
                %s

                Вынеси вердикт:
                1. Кто привёл более убедительные аргументы и почему
                2. Какой вариант лучше подходит в данной ситуации
                3. Финальная рекомендация для пользователя
                """.formatted(topic, lastA, lastB);

        String verdict = llm.generate(judgePrompt);
        trace.add(TraceStep.model("⚖️ Судья — финальный вердикт", verdict));

        return new ChatResponse(verdict, trace);
    }

    private String debate(String agentName, String systemPrompt, String userMessage, List<TraceStep> trace) {
        var response = llm.generate(List.of(
                SystemMessage.from(systemPrompt),
                UserMessage.from(userMessage)
        ));
        String text = response.content().text();
        trace.add(TraceStep.model("🗣️ " + agentName, text));
        return text;
    }
}
