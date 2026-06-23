package com.ailevels.level5;

import com.ailevels.common.dto.ChatRequest;
import com.ailevels.common.dto.ChatResponse;
import com.ailevels.common.dto.PatternHandler;
import com.ailevels.common.dto.TraceStep;
import dev.langchain4j.model.chat.ChatLanguageModel;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * ПАТТЕРН: Supervisor — контроль качества с доработкой
 *
 * Расширение Orchestrator-Worker:
 *   После работы исполнителя появляется Супервайзер (контролёр),
 *   который оценивает качество результата. Если результат недостаточно хорош —
 *   исполнитель получает обратную связь и переделывает (до 2 раундов).
 *
 * Ключевое: в trace видно «вердикт» супервайзера и факт доработки.
 * Это демонстрирует, как агентные системы могут самоулучшаться.
 *
 * Попробуй: «Напиши описание товара "Наушники SoundMax Pro" для интернет-магазина»
 */
@Service
public class SupervisorHandler implements PatternHandler {

    private static final int MAX_REVIEW_ROUNDS = 2;

    private final ChatLanguageModel llm;
    private final MultiAgentTools tools;
    private final AgentRunner runner;

    public SupervisorHandler(ChatLanguageModel llm) {
        this.llm = llm;
        this.tools = new MultiAgentTools();
        this.runner = new AgentRunner(llm, tools);
    }

    @Override public String level() { return "5"; }
    @Override public String id() { return "supervisor"; }
    @Override public String title() { return "Supervisor (контроль качества)"; }
    @Override public String description() {
        return "Супервайзер проверяет работу исполнителя и при необходимости отправляет на доработку (до 2 раундов). " +
               "Попробуй: «Напиши описание ноутбука ProBook для магазина»";
    }

    @Override
    public ChatResponse handle(ChatRequest request) {
        List<TraceStep> trace = new ArrayList<>();
        trace.add(TraceStep.info("Архитектура",
                "Исполнитель → Супервайзер (оценка) → [доработка если нужно, до " + MAX_REVIEW_ROUNDS + " раундов]"));

        // Сначала собираем контекстную информацию
        String researchResult = runner.run(
                "Исследователь",
                "Найди информацию о запрошенном товаре или теме с помощью инструментов.",
                request.message(),
                tools.searchSpecs(),
                trace
        );

        String workerResult = null;
        String supervisorFeedback = null;

        for (int round = 1; round <= MAX_REVIEW_ROUNDS + 1; round++) {
            // ─── ИСПОЛНИТЕЛЬ ВЫПОЛНЯЕТ ЗАДАЧУ ─────────────────────────────
            String workerPrompt = round == 1
                    ? request.message() + "\n\nДоступная информация:\n" + researchResult
                    : request.message() + "\n\nДоступная информация:\n" + researchResult
                      + "\n\nТвой предыдущий ответ:\n" + workerResult
                      + "\n\nЗамечания супервайзера:\n" + supervisorFeedback
                      + "\n\nПожалуйста, исправь ответ с учётом замечаний.";

            workerResult = runner.run(
                    "Исполнитель (раунд " + round + ")",
                    "Ты профессиональный копирайтер и эксперт по товарам. " +
                    "Пиши конкретно, убедительно и структурированно.",
                    workerPrompt,
                    null,
                    trace
            );

            if (round > MAX_REVIEW_ROUNDS) {
                // Исчерпали лимит раундов
                trace.add(TraceStep.info("✅ Принято по лимиту раундов",
                        "Достигнут лимит " + MAX_REVIEW_ROUNDS + " доработок"));
                break;
            }

            // ─── СУПЕРВАЙЗЕР ПРОВЕРЯЕТ РЕЗУЛЬТАТ ──────────────────────────
            String supervisorEval = llm.generate(
                    "Ты строгий супервайзер. Оцени результат по критериям:\n" +
                    "1. Наличие конкретных данных (цены, характеристики)\n" +
                    "2. Структурированность\n" +
                    "3. Убедительность для покупателя\n\n" +
                    "Задача была: " + request.message() + "\n\n" +
                    "Результат исполнителя:\n" + workerResult + "\n\n" +
                    "Ответь строго в формате:\n" +
                    "ВЕРДИКТ: ПРИНЯТО или ДОРАБОТАТЬ\n" +
                    "ПРИЧИНА: [одно предложение]\n" +
                    "ЗАМЕЧАНИЯ: [конкретные пожелания если ДОРАБОТАТЬ]"
            );

            trace.add(TraceStep.info("🔍 Супервайзер (раунд " + round + ") — вердикт", supervisorEval));

            boolean approved = supervisorEval.contains("ПРИНЯТО");
            if (approved) {
                trace.add(TraceStep.info("✅ Супервайзер принял результат", "Раунд " + round));
                break;
            }

            supervisorFeedback = supervisorEval;
            trace.add(TraceStep.info("🔄 Отправлено на доработку (раунд " + round + ")", supervisorFeedback));
        }

        return new ChatResponse(workerResult, trace);
    }
}
