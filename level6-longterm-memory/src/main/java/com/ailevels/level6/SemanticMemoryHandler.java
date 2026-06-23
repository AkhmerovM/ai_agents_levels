package com.ailevels.level6;

import com.ailevels.common.dto.ChatRequest;
import com.ailevels.common.dto.ChatResponse;
import com.ailevels.common.dto.PatternHandler;
import com.ailevels.common.dto.TraceStep;
import dev.langchain4j.model.chat.ChatLanguageModel;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * ПАТТЕРН: Semantic Memory — семантическая долгая память
 *
 * Хранит ФАКТЫ о пользователе: предпочтения, настройки, личные данные.
 * Примеры: «живёт в Москве», «любит краткие ответы», «бюджет до 50000 руб».
 *
 * Как работает:
 *   1. Агент анализирует сообщение — не содержит ли новых фактов о пользователе?
 *   2. Если да — сохраняет их в pgvector с привязкой к userId
 *   3. При новом вопросе — ищет релевантные факты и подставляет в промпт
 *   4. Память ПЕРЕЖИВАЕТ перезапуск (хранится в БД, не в памяти JVM)
 *
 * Ключевое отличие от уровня 2 (Sliding Window):
 *   Уровень 2: помнит последние N сообщений (краткосрочно, в RAM)
 *   Уровень 6: помнит факты о пользователе вечно (долгосрочно, в БД)
 *
 * Попробуй: сначала скажи «Я предпочитаю краткие ответы» и «Мой бюджет 30000 руб»,
 *           затем спроси «Посоветуй смартфон» — агент учтёт твои предпочтения.
 */
@Service
public class SemanticMemoryHandler implements PatternHandler {

    private final ChatLanguageModel llm;
    private final MemoryService memoryService;

    public SemanticMemoryHandler(ChatLanguageModel llm, MemoryService memoryService) {
        this.llm = llm;
        this.memoryService = memoryService;
    }

    @Override public String level() { return "6"; }
    @Override public String id() { return "semantic-memory"; }
    @Override public String title() { return "Semantic Memory (факты о пользователе)"; }
    @Override public String description() {
        return "Агент запоминает факты о тебе в БД и использует их в будущих ответах. " +
               "Попробуй: «Я люблю краткие ответы» → потом задай любой вопрос.";
    }

    @Override
    public ChatResponse handle(ChatRequest request) {
        String userId = request.userId() != null ? request.userId() : "default-user";
        List<TraceStep> trace = new ArrayList<>();
        trace.add(TraceStep.info("Пользователь", "userId = " + userId + " (память хранится в pgvector)"));

        // ─── ШАГ 1: ИЗВЛЕЧЕНИЕ НОВОГО ФАКТА ──────────────────────────────
        // Проверяем: содержит ли сообщение новый факт о пользователе?
        String extractionPrompt = """
                Проанализируй сообщение пользователя. Если в нём есть факт о САМОМ пользователе
                (его предпочтения, характеристики, настройки, цели) — извлеки его одним предложением.
                Если факта нет — ответь точно: НЕТ

                Примеры фактов: "предпочитает краткие ответы", "живёт в Москве", "бюджет до 30000 руб"
                Примеры не-фактов: вопросы, просьбы о помощи, команды

                Сообщение: "%s"
                Факт (или НЕТ):""".formatted(request.message());

        String extractedFact = llm.generate(extractionPrompt).trim();
        trace.add(TraceStep.info("Извлечение факта из сообщения", extractedFact));

        if (!extractedFact.equalsIgnoreCase("НЕТ") && !extractedFact.isEmpty() && extractedFact.length() < 200) {
            memoryService.save(userId, "semantic", extractedFact);
            trace.add(TraceStep.memory("💾 Новый факт сохранён в pgvector", extractedFact));
        }

        // ─── ШАГ 2: ПОИСК РЕЛЕВАНТНЫХ ВОСПОМИНАНИЙ ───────────────────────
        List<MemoryService.MemoryEntry> memories = memoryService.findRelevant(userId, request.message(), 5);

        if (memories.isEmpty()) {
            trace.add(TraceStep.memory("Воспоминания", "Нет релевантных фактов для этого вопроса"));
        } else {
            for (MemoryService.MemoryEntry m : memories) {
                trace.add(TraceStep.memory(
                        String.format("🧠 Факт (релевантность: %.2f)", m.score()),
                        m.content()
                ));
            }
        }

        // ─── ШАГ 3: ОТВЕТ С УЧЁТОМ ПАМЯТИ ────────────────────────────────
        String memoryContext = memories.isEmpty() ? "" :
                "\n\nИзвестные факты о пользователе:\n" +
                memories.stream().map(m -> "• " + m.content()).collect(Collectors.joining("\n"));

        String fullPrompt = "Ты персональный ассистент. Учитывай известные факты о пользователе." +
                memoryContext + "\n\nВопрос пользователя: " + request.message();

        trace.add(TraceStep.prompt("Промпт с памятью", fullPrompt));
        String answer = llm.generate(fullPrompt);
        trace.add(TraceStep.model("Ответ", answer));

        return new ChatResponse(answer, trace);
    }
}
