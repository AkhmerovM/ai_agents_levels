package com.ailevels.level4;

import com.ailevels.common.dto.ChatRequest;
import com.ailevels.common.dto.ChatResponse;
import com.ailevels.common.dto.PatternHandler;
import com.ailevels.common.dto.TraceStep;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.agent.tool.ToolSpecifications;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * ПАТТЕРН: ReAct (Reason + Act) — рассуждай и действуй
 *
 * Это главный паттерн уровня 4 и основа большинства современных агентов.
 *
 * Отличие от уровня 3 (Function Calling):
 *   Уровень 3: один запрос → модель решает какие инструменты вызвать → ответ.
 *   ReAct:     цикл из N шагов, на каждом шаге:
 *              1. Модель РАССУЖДАЕТ, что нужно сделать (мысль)
 *              2. Выбирает и вызывает инструмент (действие)
 *              3. Получает результат (наблюдение)
 *              4. Снова рассуждает — продолжать или есть финальный ответ?
 *
 * Ключевое: агент сам решает, когда задача решена.
 * Мы реализуем цикл ЯВНО (через while), а не прячем его в библиотеке.
 *
 * Демо: «Найди наушники дешевле 5000 и посчитай, сколько будет стоить 3 штуки с НДС 20%»
 */
@Service
public class ReActHandler implements PatternHandler {

    private static final int MAX_STEPS = 8;

    private final ChatLanguageModel llm;
    private final AgentTools tools;
    private final List<ToolSpecification> toolSpecs;

    public ReActHandler(ChatLanguageModel llm) {
        this.llm = llm;
        this.tools = new AgentTools();
        // LangChain4j читает @Tool-аннотации и строит JSON-схемы для каждого метода
        this.toolSpecs = ToolSpecifications.toolSpecificationsFrom(tools);
    }

    @Override public String level() { return "4"; }
    @Override public String id() { return "react"; }
    @Override public String title() { return "ReAct (Reason + Act)"; }
    @Override public String description() {
        return "Агент рассуждает → вызывает инструмент → наблюдает → повторяет, пока не решит задачу. " +
               "Попробуй: «Найди ноутбук и посчитай его цену с 20% скидкой»";
    }

    @Override
    public ChatResponse handle(ChatRequest request) {
        List<TraceStep> trace = new ArrayList<>();

        // Системный промпт настраивает агента на ReAct-поведение.
        // Явно описываем формат: Мысль → Действие → Наблюдение.
        String systemPrompt = """
                Ты агент-помощник, использующий паттерн ReAct (Reason + Act).
                Для каждого шага:
                1. Сначала рассуди, что нужно сделать (это твоя мысль)
                2. Если нужна информация — вызови инструмент
                3. Получив результат, снова рассуди и реши: нужен ещё шаг или можно ответить?

                Когда задача решена — дай финальный ответ на русском языке.
                Доступны инструменты: поиск товаров, цены, погода, калькулятор, время.
                """;

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.from(systemPrompt));
        messages.add(UserMessage.from(request.message()));

        trace.add(TraceStep.info("Паттерн ReAct", "Максимум шагов: " + MAX_STEPS + ". Цикл: мысль → действие → наблюдение"));
        trace.add(TraceStep.prompt("Задача агенту", request.message()));

        String finalAnswer = null;
        int step = 0;

        // ─── ГЛАВНЫЙ ЦИКЛ РЕACT ───────────────────────────────────────────
        // Это сердце агента. Каждая итерация — один шаг рассуждения/действия.
        while (step < MAX_STEPS) {
            step++;

            // Отправляем всю историю (вопрос + предыдущие инструменты + их результаты) в LLM.
            // Модель видит весь контекст и решает: вызвать инструмент или дать финальный ответ.
            Response<AiMessage> response = llm.generate(messages, toolSpecs);
            AiMessage aiMsg = response.content();
            messages.add(aiMsg); // добавляем ответ модели в историю

            if (aiMsg.hasToolExecutionRequests()) {
                // ─── ДЕЙСТВИЕ: модель хочет вызвать инструмент ──────────────
                for (ToolExecutionRequest req : aiMsg.toolExecutionRequests()) {
                    trace.add(TraceStep.toolCall(
                            "Шаг " + step + " → Действие: «" + req.name() + "»",
                            "Аргументы: " + req.arguments()
                    ));

                    // Вызываем реальный Java-метод
                    String result = tools.dispatch(req.name(), req.arguments());

                    trace.add(TraceStep.toolResult(
                            "Наблюдение (результат «" + req.name() + "»)",
                            result
                    ));

                    // Результат инструмента возвращается в историю — модель увидит его на следующем шаге
                    messages.add(ToolExecutionResultMessage.from(req, result));
                }

            } else {
                // ─── ФИНАЛЬНЫЙ ОТВЕТ: агент решил, что задача выполнена ────
                finalAnswer = aiMsg.text();
                trace.add(TraceStep.model("Шаг " + step + " → Финальный ответ агента", finalAnswer));
                break;
            }
        }
        // ─────────────────────────────────────────────────────────────────────

        if (finalAnswer == null) {
            finalAnswer = "⚠️ Агент остановлен по лимиту шагов (" + MAX_STEPS + "). " +
                          "Частичный результат смотри в трассировке.";
            trace.add(TraceStep.info("⚠️ Лимит шагов достигнут", "Выполнено " + step + "/" + MAX_STEPS + " шагов"));
        }

        trace.add(TraceStep.info("Статистика", "Всего шагов: " + step + " из " + MAX_STEPS + " допустимых"));
        return new ChatResponse(finalAnswer, trace);
    }
}
