package com.ailevels.level3;

import com.ailevels.common.dto.ChatRequest;
import com.ailevels.common.dto.ChatResponse;
import com.ailevels.common.dto.PatternHandler;
import com.ailevels.common.dto.TraceStep;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.service.tool.DefaultToolExecutor;
import dev.langchain4j.service.tool.ToolExecutor;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.agent.tool.ToolSpecifications;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * ПАТТЕРН: Function Calling (Tool Use) — вызов функций
 *
 * Это главный паттерн уровня 3. Демонстрирует полный цикл «пинг-понга»:
 *
 * 1. Пользователь задаёт вопрос: «Какая погода в Москве и сколько будет 25*4?»
 * 2. LLM видит описания инструментов и решает: нужны getWeather и calculator
 * 3. LLM возвращает НЕ текст, а tool_call: {name: "getWeather", args: {city: "Москва"}}
 * 4. Мы вызываем Java-метод getWeather("Москва") → получаем результат
 * 5. Результат отправляем обратно в LLM как tool_result
 * 6. LLM формирует финальный ответ, используя результаты инструментов
 *
 * В trace показывается каждый шаг этого цикла — это самая наглядная часть всего стенда.
 *
 * Попробуй: «Какая погода в Сочи?», «Посчитай (123+456)*7», «Сколько сейчас времени в Новосибирске?»
 */
@Service
public class FunctionCallingHandler implements PatternHandler {

    private final ChatLanguageModel llm;
    private final DemoTools tools;
    private final List<ToolSpecification> toolSpecs;
    private final Map<String, ToolExecutor> executors;

    public FunctionCallingHandler(ChatLanguageModel llm) {
        this.llm = llm;
        this.tools = new DemoTools();

        // LangChain4j сканирует класс DemoTools и для каждого @Tool-метода
        // создаёт JSON-схему с именем, описанием и параметрами.
        // Эта схема уходит в API как часть запроса — именно её видит LLM при выборе инструмента.
        this.toolSpecs = ToolSpecifications.toolSpecificationsFrom(tools);

        // Для каждого инструмента создаём исполнитель — объект, который умеет
        // вызвать Java-метод по имени и аргументам из ответа LLM
        this.executors = toolSpecs.stream().collect(Collectors.toMap(
            ToolSpecification::name,
            spec -> new DefaultToolExecutor(tools, tools.getClass()
                    .getMethods()[toolSpecs.indexOf(spec) < tools.getClass().getMethods().length
                    ? toolSpecs.indexOf(spec) : 0])
        ));
    }

    @Override public String level() { return "3"; }
    @Override public String id() { return "function-calling"; }
    @Override public String title() { return "Function Calling / Tool Use"; }
    @Override public String description() {
        return "LLM сама решает, какой инструмент вызвать (погода / калькулятор / время). " +
               "В «Под капотом» виден полный пинг-понг: запрос→tool_call→результат→ответ.";
    }

    @Override
    public ChatResponse handle(ChatRequest request) {
        List<TraceStep> trace = new ArrayList<>();
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(UserMessage.from(request.message()));

        // Показываем описания доступных инструментов
        StringBuilder toolsInfo = new StringBuilder();
        for (ToolSpecification spec : toolSpecs) {
            toolsInfo.append("• ").append(spec.name()).append(": ").append(spec.description()).append("\n");
        }
        trace.add(TraceStep.info("Доступные инструменты", toolsInfo.toString().trim()));

        String finalAnswer = null;
        int iteration = 0;

        // Цикл пинг-понга: продолжаем, пока модель запрашивает инструменты
        while (iteration < 5) { // максимум 5 итераций для защиты от зацикливания
            iteration++;

            // Отправляем запрос в LLM вместе со списком инструментов
            // Модель может вернуть: (а) текстовый ответ, (б) запрос на вызов инструмента
            Response<AiMessage> response = llm.generate(messages, toolSpecs);
            AiMessage aiMessage = response.content();
            messages.add(aiMessage);

            if (aiMessage.hasToolExecutionRequests()) {
                // Модель хочет вызвать один или несколько инструментов
                for (ToolExecutionRequest toolRequest : aiMessage.toolExecutionRequests()) {
                    String toolName = toolRequest.name();
                    String toolArgs = toolRequest.arguments();

                    trace.add(TraceStep.toolCall(
                        "Итерация " + iteration + ": модель вызывает «" + toolName + "»",
                        "Аргументы: " + toolArgs
                    ));

                    // Вызываем соответствующий Java-метод
                    String toolResult = executeToolSafely(toolName, toolRequest);

                    trace.add(TraceStep.toolResult(
                        "Результат инструмента «" + toolName + "»",
                        toolResult
                    ));

                    // Добавляем результат в список сообщений — LLM увидит его на следующей итерации
                    messages.add(ToolExecutionResultMessage.from(toolRequest, toolResult));
                }
                // Продолжаем цикл: снова спрашиваем модель (теперь с результатами инструментов)

            } else {
                // Модель вернула финальный текстовый ответ
                finalAnswer = aiMessage.text();
                trace.add(TraceStep.model("Финальный ответ (после инструментов)", finalAnswer));
                break;
            }
        }

        if (finalAnswer == null) {
            finalAnswer = "Превышено максимальное количество итераций инструментов.";
        }

        return new ChatResponse(finalAnswer, trace);
    }

    /**
     * Безопасный вызов инструмента по имени.
     * LangChain4j передаёт аргументы в виде JSON-строки.
     */
    private String executeToolSafely(String toolName, ToolExecutionRequest request) {
        try {
            return switch (toolName) {
                case "getWeather" -> {
                    // Парсим аргумент city из JSON
                    String args = request.arguments();
                    String city = extractJsonString(args, "city");
                    yield tools.getWeather(city);
                }
                case "calculator" -> {
                    String args = request.arguments();
                    String expression = extractJsonString(args, "expression");
                    yield tools.calculator(expression);
                }
                case "getCurrentTime" -> {
                    String args = request.arguments();
                    String timezone = extractJsonString(args, "timezone");
                    yield tools.getCurrentTime(timezone);
                }
                default -> "Инструмент '" + toolName + "' не найден";
            };
        } catch (Exception e) {
            return "Ошибка при вызове инструмента " + toolName + ": " + e.getMessage();
        }
    }

    /** Простой парсинг строкового значения из JSON без внешних зависимостей */
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
