package com.ailevels.level8;

import com.ailevels.common.dto.ChatRequest;
import com.ailevels.common.dto.ChatResponse;
import com.ailevels.common.dto.PatternHandler;
import com.ailevels.common.dto.TraceStep;
import dev.langchain4j.model.chat.ChatLanguageModel;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * ПАТТЕРН: Goal Decomposition — декомпозиция цели
 *
 * Автономный агент получает высокоуровневую ЦЕЛЬ и сам:
 *   1. Разбивает её на конкретные подзадачи (дерево задач)
 *   2. Последовательно выполняет каждую (создаёт «файлы» в sandboxFiles)
 *   3. Показывает итоговое дерево подзадач в trace
 *
 * Это БЕЗОПАСНАЯ иллюстрация: агент работает только с виртуальной
 * файловой системой в памяти, никаких реальных действий нет.
 *
 * Попробуй: «Создай структуру учебного проекта по Python для начинающих»
 *           «Подготовь документы для запуска стартапа»
 */
@Service
public class GoalDecompositionHandler implements PatternHandler {

    private static final int MAX_STEPS = 10;

    private final ChatLanguageModel llm;
    private final SandboxFileSystem sandbox;

    public GoalDecompositionHandler(ChatLanguageModel llm, SandboxFileSystem sandbox) {
        this.llm = llm;
        this.sandbox = sandbox;
    }

    @Override public String level() { return "8"; }
    @Override public String id() { return "goal-decomposition"; }
    @Override public String title() { return "Goal Decomposition (декомпозиция цели)"; }
    @Override public String description() {
        return "Агент разбивает цель на подзадачи и выполняет их, создавая файлы в песочнице. " +
               "Попробуй: «Создай структуру учебного проекта по Python»";
    }

    @Override
    public ChatResponse handle(ChatRequest request) {
        List<TraceStep> trace = new ArrayList<>();
        sandbox.clear(); // Очищаем песочницу перед новым запуском
        trace.add(TraceStep.info("⚠️ Безопасный режим",
                "Агент работает только с виртуальной файловой системой в памяти. Лимит: " + MAX_STEPS + " шагов."));

        // ─── ШАГ 1: ДЕКОМПОЗИЦИЯ ЦЕЛИ ─────────────────────────────────────
        String decompositionPrompt = """
                Ты автономный агент. Тебе дана цель — разбей её на конкретные подзадачи.

                Цель: %s

                Правила:
                - Каждая подзадача = создание одного текстового «файла» в виртуальной файловой системе
                - Имя файла в формате: имя_файла.txt (без пробелов, латиница или кириллица)
                - Не более %d подзадач

                Выведи список в формате (строго):
                ФАЙЛ: имя_файла.txt | ЗАДАЧА: что написать в этом файле
                ФАЙЛ: имя_файла2.txt | ЗАДАЧА: что написать в этом файле
                """.formatted(request.message(), Math.min(MAX_STEPS, 6));

        String decomposition = llm.generate(decompositionPrompt);
        trace.add(TraceStep.info("🎯 Декомпозиция цели", decomposition));

        // Парсим список подзадач
        List<SubTask> subtasks = Arrays.stream(decomposition.split("\n"))
                .filter(l -> l.startsWith("ФАЙЛ:"))
                .map(this::parseSubTask)
                .filter(t -> t != null)
                .limit(MAX_STEPS)
                .collect(Collectors.toList());

        trace.add(TraceStep.info("Подзадач в плане", String.valueOf(subtasks.size())));

        // ─── ШАГ 2: ВЫПОЛНЕНИЕ КАЖДОЙ ПОДЗАДАЧИ ──────────────────────────
        int step = 0;
        for (SubTask task : subtasks) {
            step++;
            trace.add(TraceStep.info("▶ Шаг " + step + "/" + subtasks.size(),
                    "Создаю файл «" + task.fileName + "»: " + task.description));

            // Агент генерирует содержимое файла
            String content = llm.generate(
                    "Цель проекта: " + request.message() + "\n\n" +
                    "Создай содержимое для файла «" + task.fileName + "».\n" +
                    "Задача: " + task.description + "\n\n" +
                    "Напиши конкретное содержимое (не описание, а сам текст файла)."
            );

            sandbox.write(task.fileName, content);
            trace.add(TraceStep.toolResult("💾 Файл создан: " + task.fileName,
                    content.length() > 300 ? content.substring(0, 300) + "..." : content));
        }

        // ─── ФИНАЛЬНЫЙ ОТЧЁТ ──────────────────────────────────────────────
        if (step >= MAX_STEPS) {
            trace.add(TraceStep.info("⚠️ Лимит шагов достигнут", "Выполнено " + step + "/" + MAX_STEPS));
        }

        String summary = "✅ Цель выполнена! Создано файлов: " + step + "\n\n" +
                "Структура:\n" + sandbox.list() + "\n\n" +
                "Нажми «Показать файлы» в UI чтобы увидеть содержимое.";

        trace.add(TraceStep.info("📁 Итоговая структура файлов", sandbox.list()));
        return new ChatResponse(summary, trace);
    }

    private SubTask parseSubTask(String line) {
        try {
            String[] parts = line.split("\\|");
            if (parts.length < 2) return null;
            String fileName = parts[0].replace("ФАЙЛ:", "").trim();
            String task = parts[1].replace("ЗАДАЧА:", "").trim();
            return new SubTask(fileName, task);
        } catch (Exception e) { return null; }
    }

    private record SubTask(String fileName, String description) {}
}
