package com.ailevels.level1;

import com.ailevels.common.dto.ChatRequest;
import com.ailevels.common.dto.ChatResponse;
import com.ailevels.common.dto.PatternHandler;
import com.ailevels.common.dto.TraceStep;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.input.Prompt;
import dev.langchain4j.model.input.PromptTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * ПАТТЕРН: Prompt Template — шаблон промпта с плейсхолдерами
 *
 * Идея: вместо того чтобы каждый раз писать промпт с нуля,
 * определяем шаблон с {{плейсхолдерами}} и подставляем конкретные данные.
 *
 * Это решает несколько проблем:
 * 1. Повторное использование — один шаблон, много вариантов данных
 * 2. Разделение логики и данных — шаблон пишет разработчик, данные приходят от пользователя
 * 3. Безопасность — можно валидировать данные перед подстановкой
 *
 * LangChain4j использует синтаксис {{переменная}}.
 * Под капотом это просто String.replace — но с валидацией наличия всех переменных.
 *
 * Формат запроса: "язык=Python | задача=сортировка списка | стиль=с примерами"
 * Или просто любой текст — он подставится в переменную {{task}}.
 */
@Service
public class PromptTemplateHandler implements PatternHandler {

    private final ChatLanguageModel llm;

    // Шаблон с тремя плейсхолдерами.
    // {{language}} — язык программирования
    // {{task}}     — что нужно сделать
    // {{style}}    — стиль объяснения
    private static final PromptTemplate CODE_TEMPLATE = PromptTemplate.from("""
            Ты опытный разработчик на {{language}}.

            Задача: {{task}}

            Требования к ответу: {{style}}

            Дай чёткий и понятный ответ.
            """);

    // Второй шаблон — для анализа текста
    private static final PromptTemplate ANALYSIS_TEMPLATE = PromptTemplate.from("""
            Проанализируй следующий текст с точки зрения {{perspective}}.

            Текст: {{text}}

            Формат ответа: {{format}}
            """);

    public PromptTemplateHandler(ChatLanguageModel llm) {
        this.llm = llm;
    }

    @Override public String level() { return "1"; }
    @Override public String id() { return "prompt-template"; }
    @Override public String title() { return "Prompt Template"; }
    @Override public String description() {
        return "Шаблон с {{плейсхолдерами}} — данные подставляются перед отправкой в LLM. " +
               "Формат: 'язык=Python | задача=напиши сортировку | стиль=с комментариями'";
    }

    @Override
    public ChatResponse handle(ChatRequest request) {
        List<TraceStep> trace = new ArrayList<>();

        // Парсим входные данные в формате "ключ=значение | ключ=значение"
        // Если формат не совпадает — используем сообщение как задачу целиком
        Map<String, String> params = parseParams(request.message());

        String filledPrompt;
        String templateUsed;

        if (params.containsKey("язык") || params.containsKey("задача")) {
            // Используем шаблон для кода
            String language = params.getOrDefault("язык", "Python");
            String task     = params.getOrDefault("задача", request.message());
            String style    = params.getOrDefault("стиль", "кратко с примером кода");

            // apply() подставляет значения в {{плейсхолдеры}} и валидирует, что все заполнены
            Prompt prompt = CODE_TEMPLATE.apply(Map.of(
                    "language", language,
                    "task",     task,
                    "style",    style
            ));
            filledPrompt = prompt.text();
            templateUsed = "Шаблон кода (язык + задача + стиль)";

            trace.add(TraceStep.info("Переменные шаблона",
                    "{{language}} = " + language + "\n" +
                    "{{task}} = " + task + "\n" +
                    "{{style}} = " + style));

        } else if (params.containsKey("текст") || params.containsKey("угол")) {
            // Используем шаблон анализа текста
            String text        = params.getOrDefault("текст", request.message());
            String perspective = params.getOrDefault("угол", "маркетолога");
            String format      = params.getOrDefault("формат", "3 ключевых пункта");

            Prompt prompt = ANALYSIS_TEMPLATE.apply(Map.of(
                    "text",        text,
                    "perspective", perspective,
                    "format",      format
            ));
            filledPrompt = prompt.text();
            templateUsed = "Шаблон анализа (текст + угол + формат)";

            trace.add(TraceStep.info("Переменные шаблона",
                    "{{text}} = " + text + "\n" +
                    "{{perspective}} = " + perspective + "\n" +
                    "{{format}} = " + format));

        } else {
            // Фолбэк: используем шаблон кода с дефолтными значениями
            Prompt prompt = CODE_TEMPLATE.apply(Map.of(
                    "language", "Python",
                    "task",     request.message(),
                    "style",    "кратко с примером"
            ));
            filledPrompt = prompt.text();
            templateUsed = "Шаблон кода (дефолтные значения)";

            trace.add(TraceStep.info("Использованы дефолты",
                    "Формат не распознан. Попробуй: 'язык=Java | задача=напиши Hello World | стиль=с комментариями'"));
        }

        // Показываем шаблон ДО подстановки
        trace.add(TraceStep.info("Шаблон (до подстановки)",
                "Ты опытный разработчик на {{language}}.\n\nЗадача: {{task}}\n\nТребования: {{style}}"));

        // Показываем промпт ПОСЛЕ подстановки — это и есть то, что уходит в LLM
        trace.add(TraceStep.prompt("Промпт после подстановки → " + templateUsed, filledPrompt));

        String answer = llm.generate(filledPrompt);
        trace.add(TraceStep.model("Ответ модели", answer));

        return new ChatResponse(answer, trace);
    }

    /**
     * Парсит строку вида "ключ=значение | ключ2=значение2" в Map.
     */
    private Map<String, String> parseParams(String input) {
        Map<String, String> result = new java.util.LinkedHashMap<>();
        if (input == null || !input.contains("=")) return result;

        for (String part : input.split("\\|")) {
            String[] kv = part.split("=", 2);
            if (kv.length == 2) {
                result.put(kv[0].trim().toLowerCase(), kv[1].trim());
            }
        }
        return result;
    }
}
