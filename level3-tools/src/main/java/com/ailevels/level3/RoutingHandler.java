package com.ailevels.level3;

import com.ailevels.common.dto.ChatRequest;
import com.ailevels.common.dto.ChatResponse;
import com.ailevels.common.dto.PatternHandler;
import com.ailevels.common.dto.TraceStep;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.agent.tool.ToolSpecifications;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * ПАТТЕРН: Routing через описания инструментов
 *
 * По сути это тот же Function Calling, но акцент на другом:
 * мы показываем, что ОТДЕЛЬНОГО кода маршрутизации нет.
 * Модель сама выбирает инструмент, читая их описания (@Tool).
 *
 * Это важное концептуальное отличие от традиционного роутинга (if/else, switch):
 * маршрут определяется LLM на основе семантики запроса,
 * а не жёстко заданными правилами.
 *
 * Попробуй разные типы запросов и смотри, какой инструмент выбирает модель:
 * - «Сколько будет 1000 / 8?»       → calculator
 * - «Погода в Екатеринбурге»         → getWeather
 * - «Который час в Токио?»           → getCurrentTime
 * - «Погода в Сочи и сколько 15*15?» → оба инструмента
 */
@Service
public class RoutingHandler implements PatternHandler {

    private final ChatLanguageModel llm;
    private final DemoTools tools;
    private final List<ToolSpecification> toolSpecs;

    public RoutingHandler(ChatLanguageModel llm) {
        this.llm = llm;
        this.tools = new DemoTools();
        this.toolSpecs = ToolSpecifications.toolSpecificationsFrom(tools);
    }

    @Override public String level() { return "3"; }
    @Override public String id() { return "routing"; }
    @Override public String title() { return "Schema-based Routing"; }
    @Override public String description() {
        return "LLM маршрутизирует запрос к нужному инструменту по описанию. " +
               "Никакого if/else — выбор делает модель. " +
               "Погода / калькулятор / время — посмотри, какой выберет.";
    }

    @Override
    public ChatResponse handle(ChatRequest request) {
        List<TraceStep> trace = new ArrayList<>();

        // Ключевое объяснение для пользователя: нет кода маршрутизации
        trace.add(TraceStep.info("Как работает роутинг",
            "Код маршрутизации отсутствует! LLM читает описания @Tool и сама решает, " +
            "какой инструмент вызвать. Описание — это и есть маршрут."));

        // Показываем описания инструментов как «маршрутную карту»
        StringBuilder routeMap = new StringBuilder();
        for (ToolSpecification spec : toolSpecs) {
            routeMap.append("• ").append(spec.name()).append(":\n  ")
                    .append(spec.description()).append("\n\n");
        }
        trace.add(TraceStep.info("«Маршрутная карта» (описания инструментов)", routeMap.toString().trim()));

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(UserMessage.from(request.message()));

        String finalAnswer = null;
        int iteration = 0;

        while (iteration < 5) {
            iteration++;
            Response<AiMessage> response = llm.generate(messages, toolSpecs);
            AiMessage aiMessage = response.content();
            messages.add(aiMessage);

            if (aiMessage.hasToolExecutionRequests()) {
                for (ToolExecutionRequest toolRequest : aiMessage.toolExecutionRequests()) {
                    String toolName = toolRequest.name();

                    // Показываем ВЫБОР модели — это и есть роутинг
                    trace.add(TraceStep.toolCall(
                        "🔀 Модель выбрала маршрут: «" + toolName + "»",
                        "Запрос: «" + request.message() + "»\n" +
                        "Выбранный инструмент: " + toolName + "\n" +
                        "Аргументы: " + toolRequest.arguments()
                    ));

                    String toolResult = executeToolSafely(toolName, toolRequest);
                    trace.add(TraceStep.toolResult("Результат «" + toolName + "»", toolResult));
                    messages.add(ToolExecutionResultMessage.from(toolRequest, toolResult));
                }
            } else {
                finalAnswer = aiMessage.text();
                trace.add(TraceStep.model("Финальный ответ", finalAnswer));
                break;
            }
        }

        if (finalAnswer == null) finalAnswer = "Превышен лимит итераций.";
        return new ChatResponse(finalAnswer, trace);
    }

    private String executeToolSafely(String toolName, ToolExecutionRequest request) {
        try {
            String args = request.arguments();
            return switch (toolName) {
                case "getWeather" -> tools.getWeather(extractJsonString(args, "city"));
                case "calculator" -> tools.calculator(extractJsonString(args, "expression"));
                case "getCurrentTime" -> tools.getCurrentTime(extractJsonString(args, "timezone"));
                default -> "Инструмент '" + toolName + "' не найден";
            };
        } catch (Exception e) {
            return "Ошибка: " + e.getMessage();
        }
    }

    private String extractJsonString(String json, String key) {
        String search = "\"" + key + "\"";
        int keyIdx = json.indexOf(search);
        if (keyIdx == -1) return "";
        int colonIdx = json.indexOf(":", keyIdx + search.length());
        int startIdx = json.indexOf("\"", colonIdx + 1) + 1;
        int endIdx = json.indexOf("\"", startIdx);
        if (startIdx <= 0 || endIdx <= 0) return "";
        return json.substring(startIdx, endIdx);
    }
}
