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
 * ПАТТЕРН: Orchestrator-Worker — дирижёр и исполнители
 *
 * Архитектура:
 *   Оркестратор (агент-координатор) — принимает задачу, разбивает её на подзадачи,
 *   раздаёт исполнителям и собирает результат.
 *
 *   Исполнители (worker-агенты) — специализированные агенты:
 *   • Исследователь (Researcher) — ищет информацию через mockWebSearch
 *   • Писатель (Writer) — оформляет текст, без инструментов
 *
 * Ключевое: каждый агент — это отдельный вызов LLM с разным system-промптом и
 * разным набором инструментов. Агенты не видят друг друга напрямую —
 * только через оркестратора.
 *
 * Попробуй: «Подготовь справку о наушниках для покупателя»
 */
@Service
public class OrchestratorWorkerHandler implements PatternHandler {

    private final ChatLanguageModel llm;
    private final MultiAgentTools tools;
    private final AgentRunner runner;

    public OrchestratorWorkerHandler(ChatLanguageModel llm) {
        this.llm = llm;
        this.tools = new MultiAgentTools();
        this.runner = new AgentRunner(llm, tools);
    }

    @Override public String level() { return "5"; }
    @Override public String id() { return "orchestrator-worker"; }
    @Override public String title() { return "Orchestrator-Worker"; }
    @Override public String description() {
        return "Оркестратор разбивает задачу и раздаёт агентам (Исследователь + Писатель). " +
               "Попробуй: «Подготовь справку о ноутбуках для покупателя»";
    }

    @Override
    public ChatResponse handle(ChatRequest request) {
        List<TraceStep> trace = new ArrayList<>();
        trace.add(TraceStep.info("Архитектура", "Оркестратор → [Исследователь, Писатель] → Оркестратор собирает итог"));

        // ─── ШАГ 1: ОРКЕСТРАТОР ПЛАНИРУЕТ ────────────────────────────────
        String orchestratorPlan = runner.run(
                "Оркестратор",
                """
                Ты координатор мультиагентной системы. Получив задачу, определи:
                1. Что нужно исследовать/найти (задача для Исследователя)
                2. Как оформить результат (задача для Писателя)
                Ответь двумя пунктами строго в формате:
                ИССЛЕДОВАТЕЛЬ: [конкретная задача поиска]
                ПИСАТЕЛЬ: [как оформить и что написать]
                """,
                request.message(),
                null,
                trace
        );

        // Парсим задачи для исполнителей
        String researchTask = extractSection(orchestratorPlan, "ИССЛЕДОВАТЕЛЬ:");
        String writingTask  = extractSection(orchestratorPlan, "ПИСАТЕЛЬ:");

        if (researchTask.isEmpty()) researchTask = "Найди информацию о: " + request.message();
        if (writingTask.isEmpty())  writingTask  = "Напиши краткую справку по результатам исследования";

        trace.add(TraceStep.info("Оркестратор → задача Исследователю", researchTask));
        trace.add(TraceStep.info("Оркестратор → задача Писателю", writingTask));

        // ─── ШАГ 2: ИССЛЕДОВАТЕЛЬ СОБИРАЕТ ИНФОРМАЦИЮ ────────────────────
        String researchResult = runner.run(
                "Исследователь",
                "Ты агент-исследователь. Твоя задача — найти нужную информацию с помощью инструментов. " +
                "Дай подробный ответ с фактами и данными.",
                researchTask,
                tools.searchSpecs(), // только поиск
                trace
        );

        // ─── ШАГ 3: ПИСАТЕЛЬ ОФОРМЛЯЕТ РЕЗУЛЬТАТ ─────────────────────────
        String writerResult = runner.run(
                "Писатель",
                "Ты агент-редактор. Оформляй информацию структурированно и понятно для пользователя. " +
                "Не добавляй выдуманных данных — только то, что получил от Исследователя.",
                writingTask + "\n\nДанные от Исследователя:\n" + researchResult,
                null, // у Писателя нет инструментов
                trace
        );

        // ─── ШАГ 4: ОРКЕСТРАТОР СОБИРАЕТ ФИНАЛЬНЫЙ ОТВЕТ ─────────────────
        String finalAnswer = runner.run(
                "Оркестратор (финал)",
                "Ты координатор. Проверь результат Писателя и при необходимости доработай финальный ответ.",
                "Задача пользователя: " + request.message() +
                "\n\nРезультат Писателя:\n" + writerResult +
                "\n\nЕсли результат хорош — верни его. Если нужно доработать — улучши.",
                null,
                trace
        );

        return new ChatResponse(finalAnswer, trace);
    }

    private String extractSection(String text, String prefix) {
        int idx = text.indexOf(prefix);
        if (idx < 0) return "";
        int start = idx + prefix.length();
        int end = text.indexOf('\n', start);
        return (end < 0 ? text.substring(start) : text.substring(start, end)).trim();
    }
}
