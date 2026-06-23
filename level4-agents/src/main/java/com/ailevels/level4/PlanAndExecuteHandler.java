package com.ailevels.level4;

import com.ailevels.common.dto.ChatRequest;
import com.ailevels.common.dto.ChatResponse;
import com.ailevels.common.dto.PatternHandler;
import com.ailevels.common.dto.TraceStep;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.agent.tool.ToolSpecifications;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * ПАТТЕРН: Plan-and-Execute — сначала план, потом выполнение
 *
 * Проблема ReAct: агент принимает решение о следующем шаге «на ходу»,
 * без общей стратегии. Это может приводить к нелогичным путям.
 *
 * Решение: разделяем на два отдельных этапа:
 *   1. PLANNING: отдельный вызов LLM составляет пошаговый план
 *   2. EXECUTION: каждый шаг плана выполняется последовательно
 *
 * Преимущества:
 *   - Более предсказуемое поведение (план виден заранее)
 *   - Можно вмешаться/откорректировать план перед выполнением
 *   - Лучше для сложных многошаговых задач
 *
 * Попробуй: «Подбери периферию (мышь + клавиатуру) для офиса до 10000 руб
 *            и посчитай итоговую сумму с доставкой 500 руб»
 */
@Service
public class PlanAndExecuteHandler implements PatternHandler {

    private static final int MAX_PLAN_STEPS = 6;

    private final ChatLanguageModel llm;
    private final AgentTools tools;
    private final List<ToolSpecification> toolSpecs;

    public PlanAndExecuteHandler(ChatLanguageModel llm) {
        this.llm = llm;
        this.tools = new AgentTools();
        this.toolSpecs = ToolSpecifications.toolSpecificationsFrom(tools);
    }

    @Override public String level() { return "4"; }
    @Override public String id() { return "plan-and-execute"; }
    @Override public String title() { return "Plan-and-Execute"; }
    @Override public String description() {
        return "Агент сначала строит план шагов, затем выполняет каждый. " +
               "Попробуй: «Подбери мышь и клавиатуру до 10000 руб и посчитай итог с доставкой»";
    }

    @Override
    public ChatResponse handle(ChatRequest request) {
        List<TraceStep> trace = new ArrayList<>();

        // ─── ЭТАП 1: ПЛАНИРОВАНИЕ ─────────────────────────────────────────
        // Отдельный вызов LLM, цель которого — составить план, а не выполнять.
        // Важно: у «планировщика» нет доступа к инструментам!
        String plannerPrompt = """
                Ты планировщик задач. Получив задачу, составь конкретный план действий.
                Выводи план в виде нумерованного списка, каждый шаг на отдельной строке:
                1. [шаг первый]
                2. [шаг второй]
                ...

                Доступные инструменты: searchProducts(поиск товара), getPrice(цена по ID),
                getWeather(погода), calculator(вычисление), getCurrentTime(время).

                Составь план для задачи:
                """ + request.message();

        String plan = llm.generate(plannerPrompt);

        trace.add(TraceStep.info("Этап 1: Планирование", "Отдельный вызов LLM составляет план без использования инструментов"));
        trace.add(TraceStep.prompt("Промпт планировщика", plannerPrompt));
        trace.add(TraceStep.model("📋 Составленный план", plan));

        // Парсим план в список шагов
        List<String> planSteps = Arrays.stream(plan.split("\n"))
                .map(String::trim)
                .filter(l -> l.matches("^\\d+\\..*") || l.matches("^[-•].*"))
                .map(l -> l.replaceFirst("^\\d+\\.\\s*", "").replaceFirst("^[-•]\\s*", ""))
                .filter(l -> !l.isEmpty())
                .limit(MAX_PLAN_STEPS)
                .collect(Collectors.toList());

        if (planSteps.isEmpty()) {
            // Если модель не вернула нумерованный список — используем план целиком
            planSteps = List.of("Выполни задачу: " + request.message());
        }

        trace.add(TraceStep.info("Шагов в плане", String.valueOf(planSteps.size())));

        // ─── ЭТАП 2: ВЫПОЛНЕНИЕ ───────────────────────────────────────────
        // Теперь выполняем каждый шаг плана последовательно.
        // Исполнитель видит задачу, шаг плана и результаты предыдущих шагов.
        trace.add(TraceStep.info("Этап 2: Выполнение", "Каждый шаг плана выполняется отдельным ReAct-мини-циклом"));

        List<String> stepResults = new ArrayList<>();

        for (int i = 0; i < planSteps.size(); i++) {
            String planStep = planSteps.get(i);
            trace.add(TraceStep.info("▶ Шаг " + (i + 1) + "/" + planSteps.size(), planStep));

            // Контекст для исполнителя: задача + текущий шаг + предыдущие результаты
            String executorContext = "Общая задача: " + request.message() + "\n\n" +
                    (stepResults.isEmpty() ? "" : "Уже сделано:\n" + String.join("\n", stepResults) + "\n\n") +
                    "Выполни текущий шаг: " + planStep;

            // Мини-ReAct цикл для выполнения одного шага (максимум 3 итерации)
            String stepResult = executeStep(executorContext, trace, i + 1);
            stepResults.add("Шаг " + (i + 1) + ": " + stepResult);
        }

        // ─── ФИНАЛЬНАЯ СИНТЕЗАЦИЯ ─────────────────────────────────────────
        String synthesisPrompt = "Задача была: " + request.message() + "\n\n" +
                "Результаты выполнения шагов:\n" + String.join("\n", stepResults) + "\n\n" +
                "Дай итоговый ответ пользователю, обобщив все результаты.";

        String finalAnswer = llm.generate(synthesisPrompt);
        trace.add(TraceStep.model("✅ Итоговый ответ (синтез)", finalAnswer));

        return new ChatResponse(finalAnswer, trace);
    }

    /** Выполняет один шаг плана: мини-ReAct с лимитом 3 итерации */
    private String executeStep(String stepContext, List<TraceStep> trace, int stepNum) {
        List<dev.langchain4j.data.message.ChatMessage> msgs = new ArrayList<>();
        msgs.add(SystemMessage.from("Ты агент-исполнитель. Выполни конкретный шаг задачи."));
        msgs.add(UserMessage.from(stepContext));

        for (int iter = 0; iter < 3; iter++) {
            Response<AiMessage> resp = llm.generate(msgs, toolSpecs);
            AiMessage ai = resp.content();
            msgs.add(ai);

            if (!ai.hasToolExecutionRequests()) {
                String result = ai.text();
                trace.add(TraceStep.model("  Результат шага " + stepNum, result));
                return result;
            }

            for (ToolExecutionRequest req : ai.toolExecutionRequests()) {
                trace.add(TraceStep.toolCall("  Шаг " + stepNum + " → вызов «" + req.name() + "»", req.arguments()));
                String r = tools.dispatch(req.name(), req.arguments());
                trace.add(TraceStep.toolResult("  Результат «" + req.name() + "»", r));
                msgs.add(ToolExecutionResultMessage.from(req, r));
            }
        }
        return "(шаг завершён по лимиту итераций)";
    }
}
