package com.ailevels.level7;

import com.ailevels.common.dto.ChatRequest;
import com.ailevels.common.dto.ChatResponse;
import com.ailevels.common.dto.PatternHandler;
import com.ailevels.common.dto.TraceStep;
import dev.langchain4j.model.chat.ChatLanguageModel;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * ПАТТЕРН: Tree of Thoughts (ToT) — дерево мыслей
 *
 * Проблема Chain-of-Thought: одна линия рассуждений, нет возможности
 * исследовать альтернативные пути решения.
 *
 * Решение: агент генерирует НЕСКОЛЬКО независимых «веток» решения,
 * оценивает каждую и выбирает лучшую.
 *
 * Структура:
 *   1. Генерируем N веток (N=3) — разные подходы к задаче
 *   2. Оцениваем каждую ветку по критериям
 *   3. Выбираем лучшую ветку и разворачиваем финальный ответ
 *
 * Почему лучше CoT?
 * Если первая мысль неверная — CoT уйдёт в тупик.
 * ToT пробует несколько путей и выбирает оптимальный.
 *
 * Попробуй: «Как организовать хранение документов в небольшом офисе?»
 *           «Предложи 3 способа мотивировать команду разработчиков»
 */
@Service
public class TreeOfThoughtsHandler implements PatternHandler {

    private static final int NUM_BRANCHES = 3;

    private final ChatLanguageModel llm;

    public TreeOfThoughtsHandler(ChatLanguageModel llm) {
        this.llm = llm;
    }

    @Override public String level() { return "7"; }
    @Override public String id() { return "tree-of-thoughts"; }
    @Override public String title() { return "Tree of Thoughts"; }
    @Override public String description() {
        return "Агент генерирует " + NUM_BRANCHES + " разных подхода к задаче, оценивает и выбирает лучший. " +
               "Попробуй: «Как повысить продуктивность команды?»";
    }

    @Override
    public ChatResponse handle(ChatRequest request) {
        List<TraceStep> trace = new ArrayList<>();
        trace.add(TraceStep.info("Паттерн Tree of Thoughts",
                "1) Генерируем " + NUM_BRANCHES + " ветки → 2) Оцениваем → 3) Выбираем лучшую"));

        // ─── ШАГ 1: ГЕНЕРАЦИЯ ВЕТОК ───────────────────────────────────────
        List<String> branches = new ArrayList<>();

        for (int i = 1; i <= NUM_BRANCHES; i++) {
            String branchPrompt = """
                    Задача: %s

                    Предложи ОДИН конкретный подход к решению этой задачи.
                    Это должен быть подход #%d из %d — он должен отличаться от очевидного.
                    Обоснуй логику этого подхода (3-5 предложений).
                    """.formatted(request.message(), i, NUM_BRANCHES);

            String branch = llm.generate(branchPrompt);
            branches.add(branch);
            trace.add(TraceStep.model("🌿 Ветка " + i + "/" + NUM_BRANCHES, branch));
        }

        // ─── ШАГ 2: ОЦЕНКА ВЕТОК ──────────────────────────────────────────
        StringBuilder branchList = new StringBuilder();
        for (int i = 0; i < branches.size(); i++) {
            branchList.append("Вариант ").append(i + 1).append(":\n").append(branches.get(i)).append("\n\n");
        }

        String evaluationPrompt = """
                Ты эксперт-оценщик. Оцени " + NUM_BRANCHES + " варианта решения задачи.

                Задача: %s

                %s

                Для каждого варианта укажи:
                - Сильные стороны
                - Слабые стороны
                - Оценка (1-10)

                В конце укажи: ЛУЧШИЙ ВАРИАНТ: [номер] — [почему]
                """.formatted(request.message(), branchList);

        String evaluation = llm.generate(evaluationPrompt);
        trace.add(TraceStep.info("⚖️ Оценка всех веток", evaluation));

        // ─── ШАГ 3: РАЗВОРАЧИВАЕМ ЛУЧШУЮ ВЕТКУ ───────────────────────────
        // Определяем номер лучшего варианта
        int bestIdx = 0;
        for (int i = 0; i < branches.size(); i++) {
            if (evaluation.contains("ЛУЧШИЙ ВАРИАНТ: " + (i + 1))) { bestIdx = i; break; }
        }
        trace.add(TraceStep.info("✅ Выбранная ветка", "Вариант " + (bestIdx + 1)));

        String finalPrompt = """
                Задача: %s

                Лучший подход к решению:
                %s

                Разверни этот подход в полный, детальный и практичный ответ.
                """.formatted(request.message(), branches.get(bestIdx));

        String finalAnswer = llm.generate(finalPrompt);
        trace.add(TraceStep.model("📋 Финальный ответ (лучшая ветка развёрнута)", finalAnswer));

        return new ChatResponse(finalAnswer, trace);
    }
}
