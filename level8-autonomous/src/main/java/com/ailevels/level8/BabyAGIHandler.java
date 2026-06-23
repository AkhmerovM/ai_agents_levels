package com.ailevels.level8;

import com.ailevels.common.dto.ChatRequest;
import com.ailevels.common.dto.ChatResponse;
import com.ailevels.common.dto.PatternHandler;
import com.ailevels.common.dto.TraceStep;
import dev.langchain4j.model.chat.ChatLanguageModel;
import org.springframework.stereotype.Service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.stream.Collectors;

/**
 * ПАТТЕРН: BabyAGI-style Task Loop — динамическая очередь задач
 *
 * Инспирирован проектом BabyAGI (Yohei Nakajima, 2023).
 *
 * Идея: агент ведёт очередь задач. На каждом шаге:
 *   1. Берёт первую задачу из очереди
 *   2. Выполняет её (создаёт файл в песочнице)
 *   3. По результату может добавить НОВЫЕ задачи в конец очереди
 *   4. Повторяет пока очередь не пуста или не достигнут лимит шагов
 *
 * Ключевое отличие от GoalDecomposition:
 *   GoalDecomposition: план фиксируется в начале, потом выполняется
 *   BabyAGI: план ДИНАМИЧЕСКИЙ — задачи добавляются в процессе выполнения
 *
 * В trace видна эволюция очереди на каждом шаге — это самое наглядное.
 *
 * Попробуй: «Исследуй рынок ноутбуков и подготовь отчёт для руководства»
 *           «Создай план обучения Python с нуля за 3 месяца»
 */
@Service
public class BabyAGIHandler implements PatternHandler {

    private static final int MAX_STEPS = 10;

    private final ChatLanguageModel llm;
    private final SandboxFileSystem sandbox;

    public BabyAGIHandler(ChatLanguageModel llm, SandboxFileSystem sandbox) {
        this.llm = llm;
        this.sandbox = sandbox;
    }

    @Override public String level() { return "8"; }
    @Override public String id() { return "baby-agi"; }
    @Override public String title() { return "BabyAGI (динамическая очередь задач)"; }
    @Override public String description() {
        return "Агент ведёт очередь задач: выполняет → по результату добавляет новые задачи. " +
               "Попробуй: «Подготовь отчёт по ноутбукам для руководства»";
    }

    @Override
    public ChatResponse handle(ChatRequest request) {
        List<TraceStep> trace = new ArrayList<>();
        sandbox.clear();
        trace.add(TraceStep.info("⚠️ Безопасный режим", "Только виртуальная файловая система. Лимит: " + MAX_STEPS + " шагов."));

        // ─── ИНИЦИАЛИЗАЦИЯ ОЧЕРЕДИ ────────────────────────────────────────
        // Создаём начальный список задач из цели
        String initPrompt = """
                Ты автономный агент. Для достижения цели составь начальный список из 2-3 задач.
                Каждая задача — это создание одного документа/файла.

                Цель: %s

                Выведи задачи строго в формате (одна строка = одна задача):
                ЗАДАЧА: [описание задачи, укажи имя файла который нужно создать]
                """.formatted(request.message());

        String initTasks = llm.generate(initPrompt);
        Deque<String> taskQueue = new ArrayDeque<>(
                Arrays.stream(initTasks.split("\n"))
                        .filter(l -> l.startsWith("ЗАДАЧА:"))
                        .map(l -> l.replace("ЗАДАЧА:", "").trim())
                        .limit(3)
                        .collect(Collectors.toList())
        );

        trace.add(TraceStep.info("📋 Начальная очередь задач (" + taskQueue.size() + " задач)", String.join("\n", taskQueue)));

        // ─── ГЛАВНЫЙ ЦИКЛ ─────────────────────────────────────────────────
        int step = 0;
        List<String> completedTasks = new ArrayList<>();

        while (!taskQueue.isEmpty() && step < MAX_STEPS) {
            step++;
            String currentTask = taskQueue.poll();

            trace.add(TraceStep.info(
                    "🔄 Шаг " + step + " | В очереди осталось: " + taskQueue.size(),
                    "▶ Текущая задача: " + currentTask
            ));

            // ─── ВЫПОЛНЕНИЕ ЗАДАЧИ ────────────────────────────────────────
            String executePrompt = """
                    Цель проекта: %s
                    Уже сделано: %s

                    Выполни задачу: %s

                    Создай содержимое для файла по этой задаче.
                    В первой строке укажи имя файла: ИМЯ_ФАЙЛА: имя.txt
                    Затем содержимое файла.
                    """.formatted(
                    request.message(),
                    completedTasks.isEmpty() ? "ничего" : String.join(", ", completedTasks),
                    currentTask
            );

            String result = llm.generate(executePrompt);

            // Извлекаем имя файла из ответа
            String fileName = extractFileName(result, step);
            String content = result.replaceFirst("ИМЯ_ФАЙЛА:.*\n?", "").trim();

            sandbox.write(fileName, content);
            completedTasks.add(currentTask);
            trace.add(TraceStep.toolResult("💾 Создан файл: " + fileName,
                    content.length() > 200 ? content.substring(0, 200) + "..." : content));

            // ─── ГЕНЕРАЦИЯ НОВЫХ ЗАДАЧ (если нужно) ──────────────────────
            if (taskQueue.isEmpty() && step < MAX_STEPS - 1) {
                String newTasksPrompt = """
                        Цель: %s
                        Уже сделано: %s
                        Файлы в проекте: %s

                        Нужны ли ещё задачи для полного достижения цели?
                        Если да — выведи 1-2 задачи в формате:
                        ЗАДАЧА: [описание]
                        Если цель достигнута — ответь: ГОТОВО
                        """.formatted(
                        request.message(),
                        String.join("; ", completedTasks),
                        sandbox.list()
                );

                String newTasksResponse = llm.generate(newTasksPrompt);
                trace.add(TraceStep.info("🤔 Агент решает: нужны ли новые задачи?", newTasksResponse));

                if (!newTasksResponse.contains("ГОТОВО")) {
                    List<String> newTasks = Arrays.stream(newTasksResponse.split("\n"))
                            .filter(l -> l.startsWith("ЗАДАЧА:"))
                            .map(l -> l.replace("ЗАДАЧА:", "").trim())
                            .limit(2)
                            .collect(Collectors.toList());
                    taskQueue.addAll(newTasks);

                    if (!newTasks.isEmpty()) {
                        trace.add(TraceStep.info("➕ Добавлено новых задач: " + newTasks.size(),
                                String.join("\n", newTasks)));
                    }
                } else {
                    trace.add(TraceStep.info("✅ Агент: цель достигнута", "Очередь завершена после шага " + step));
                }
            }

            // Показываем текущее состояние очереди
            trace.add(TraceStep.info("📊 Очередь после шага " + step,
                    taskQueue.isEmpty() ? "Очередь пуста" : "Задач в очереди: " + taskQueue.size() + "\n" + String.join("\n", taskQueue)));
        }

        if (step >= MAX_STEPS) {
            trace.add(TraceStep.info("⚠️ Лимит шагов достигнут", "Остановлен после " + MAX_STEPS + " шагов"));
        }

        String summary = String.format(
                "✅ BabyAGI завершил работу.\n\nВыполнено задач: %d\nСоздано файлов: %d\n\n%s",
                completedTasks.size(), sandbox.getAll().size(), sandbox.list()
        );

        trace.add(TraceStep.info("📁 Итог", sandbox.list()));
        return new ChatResponse(summary, trace);
    }

    private String extractFileName(String text, int step) {
        for (String line : text.split("\n")) {
            if (line.startsWith("ИМЯ_ФАЙЛА:")) {
                return line.replace("ИМЯ_ФАЙЛА:", "").trim();
            }
        }
        return "document_" + step + ".txt";
    }
}
