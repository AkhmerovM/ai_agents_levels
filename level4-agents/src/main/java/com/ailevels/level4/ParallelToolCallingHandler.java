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
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * ПАТТЕРН: Parallel Tool Calling — параллельный вызов инструментов
 *
 * Проблема ReAct: инструменты вызываются последовательно — каждый следующий
 * ждёт завершения предыдущего. Если запросы независимы — это лишняя задержка.
 *
 * Решение: когда модель возвращает несколько tool_calls за раз
 * (OpenAI/Qwen поддерживают это), выполняем их параллельно через CompletableFuture.
 *
 * Как работает: модель в одном ответе возвращает список tool_calls.
 * Мы запускаем все вызовы параллельно, ждём завершения всех (allOf),
 * и собираем результаты обратно в одно сообщение.
 *
 * Попробуй: «Какая погода в Москве, Сочи и Казани?» или
 *           «Найди ноутбуки и наушники одновременно»
 */
@Service
public class ParallelToolCallingHandler implements PatternHandler {

    private static final int MAX_STEPS = 6;

    private final ChatLanguageModel llm;
    private final AgentTools tools;
    private final List<ToolSpecification> toolSpecs;

    public ParallelToolCallingHandler(ChatLanguageModel llm) {
        this.llm = llm;
        this.tools = new AgentTools();
        this.toolSpecs = ToolSpecifications.toolSpecificationsFrom(tools);
    }

    @Override public String level() { return "4"; }
    @Override public String id() { return "parallel-tools"; }
    @Override public String title() { return "Parallel Tool Calling"; }
    @Override public String description() {
        return "Независимые инструменты вызываются параллельно (CompletableFuture). " +
               "Попробуй: «Погода в Москве, Сочи и Казани?» — три вызова одновременно";
    }

    @Override
    public ChatResponse handle(ChatRequest request) {
        List<TraceStep> trace = new ArrayList<>();
        List<dev.langchain4j.data.message.ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.from("Ты помощник. Для независимых подзадач вызывай инструменты одновременно."));
        messages.add(UserMessage.from(request.message()));

        trace.add(TraceStep.info("Паттерн", "Parallel Tool Calling: независимые вызовы выполняются одновременно через CompletableFuture"));

        String finalAnswer = null;
        int step = 0;

        while (step < MAX_STEPS) {
            step++;
            Response<AiMessage> response = llm.generate(messages, toolSpecs);
            AiMessage aiMsg = response.content();
            messages.add(aiMsg);

            if (!aiMsg.hasToolExecutionRequests()) {
                finalAnswer = aiMsg.text();
                trace.add(TraceStep.model("Финальный ответ", finalAnswer));
                break;
            }

            List<ToolExecutionRequest> requests = aiMsg.toolExecutionRequests();

            if (requests.size() > 1) {
                // ─── ПАРАЛЛЕЛЬНЫЙ ПУТЬ ─────────────────────────────────────
                // Модель вернула несколько tool_calls сразу — запускаем параллельно
                trace.add(TraceStep.info(
                        "Шаг " + step + " → Параллельный запуск " + requests.size() + " инструментов",
                        requests.stream()
                                .map(r -> "• " + r.name() + "(" + r.arguments() + ")")
                                .collect(Collectors.joining("\n"))
                ));

                long startMs = System.currentTimeMillis();

                // Запускаем каждый вызов в отдельном потоке
                Map<String, CompletableFuture<String>> futures = new ConcurrentHashMap<>();
                for (ToolExecutionRequest req : requests) {
                    CompletableFuture<String> future = CompletableFuture.supplyAsync(
                            () -> tools.dispatch(req.name(), req.arguments())
                    );
                    futures.put(req.id() != null ? req.id() : req.name(), future);
                }

                // Ждём завершения ВСЕХ параллельных вызовов
                CompletableFuture.allOf(futures.values().toArray(new CompletableFuture[0])).join();
                long elapsed = System.currentTimeMillis() - startMs;

                // Собираем результаты и добавляем в историю
                for (ToolExecutionRequest req : requests) {
                    String key = req.id() != null ? req.id() : req.name();
                    String result = futures.get(key).join();

                    trace.add(TraceStep.toolResult(
                            "↳ «" + req.name() + "» (параллельно)",
                            result
                    ));
                    messages.add(ToolExecutionResultMessage.from(req, result));
                }

                trace.add(TraceStep.info(
                        "⚡ Выполнено параллельно за " + elapsed + " мс",
                        "Последовательно заняло бы ~" + (elapsed * requests.size()) + " мс"
                ));

            } else {
                // ─── ПОСЛЕДОВАТЕЛЬНЫЙ ПУТЬ (только один инструмент) ────────
                ToolExecutionRequest req = requests.get(0);
                trace.add(TraceStep.toolCall("Шаг " + step + " → «" + req.name() + "»", req.arguments()));
                String result = tools.dispatch(req.name(), req.arguments());
                trace.add(TraceStep.toolResult("Результат", result));
                messages.add(ToolExecutionResultMessage.from(req, result));
            }
        }

        if (finalAnswer == null) {
            finalAnswer = "⚠️ Остановлен по лимиту шагов (" + MAX_STEPS + ").";
            trace.add(TraceStep.info("⚠️ Лимит", "Достигнут лимит " + MAX_STEPS + " шагов"));
        }

        return new ChatResponse(finalAnswer, trace);
    }
}
