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
 * ПАТТЕРН: Retrieval-Augmented Memory (RAM) — RAG поверх воспоминаний
 *
 * Комбинирует две идеи:
 *   - RAG (уровень 2): поиск по внешним документам
 *   - Долгая память (уровень 6): личные воспоминания пользователя
 *
 * Перед ответом агент ищет в своей долгой памяти релевантные записи
 * обоих типов (semantic + episodic) и подставляет их в контекст.
 * Это ближе всего к тому, как работает человеческая память при разговоре.
 *
 * Чем больше общений — тем богаче контекст, тем персонализированнее ответы.
 *
 * Попробуй последовательность:
 *   1. «Меня зовут Иван, я инженер»
 *   2. «Я искал ноутбук для работы»
 *   3. «Посоветуй что-то для меня» (агент вспомнит имя, профессию и интерес)
 */
@Service
public class MemoryRagHandler implements PatternHandler {

    private final ChatLanguageModel llm;
    private final MemoryService memoryService;

    public MemoryRagHandler(ChatLanguageModel llm, MemoryService memoryService) {
        this.llm = llm;
        this.memoryService = memoryService;
    }

    @Override public String level() { return "6"; }
    @Override public String id() { return "memory-rag"; }
    @Override public String title() { return "Retrieval-Augmented Memory"; }
    @Override public String description() {
        return "RAG поверх личной памяти: агент ищет все типы воспоминаний и строит богатый контекст. " +
               "Попробуй серию: «Я Иван» → «Ищу ноутбук» → «Посоветуй что-то для меня»";
    }

    @Override
    public ChatResponse handle(ChatRequest request) {
        String userId = request.userId() != null ? request.userId() : "default-user";
        List<TraceStep> trace = new ArrayList<>();
        trace.add(TraceStep.info("Пользователь", "userId = " + userId));

        // ─── ШАГ 1: СОХРАНЯЕМ ВСЁ ВАЖНОЕ ИЗ ТЕКУЩЕГО СООБЩЕНИЯ ──────────
        // Агент решает: есть ли в сообщении факт или событие для сохранения?
        String savePrompt = """
                Из сообщения пользователя извлеки:
                ФАКТ: [личный факт о пользователе, или НЕТ]
                СОБЫТИЕ: [действие/решение пользователя, или НЕТ]

                Сообщение: "%s"
                """.formatted(request.message());

        String extracted = llm.generate(savePrompt);
        trace.add(TraceStep.info("Извлечение из сообщения", extracted));

        String fact  = extractLine(extracted, "ФАКТ:");
        String event = extractLine(extracted, "СОБЫТИЕ:");

        if (!fact.equalsIgnoreCase("НЕТ")  && !fact.isEmpty())  {
            memoryService.save(userId, "semantic",  fact);
            trace.add(TraceStep.memory("💾 Сохранён факт",   fact));
        }
        if (!event.equalsIgnoreCase("НЕТ") && !event.isEmpty()) {
            memoryService.save(userId, "episodic", event);
            trace.add(TraceStep.memory("💾 Сохранено событие", event));
        }

        // ─── ШАГ 2: RETRIEVAL — ПОИСК ВСЕХ РЕЛЕВАНТНЫХ ВОСПОМИНАНИЙ ──────
        // Ищем и семантические (факты) и эпизодические (события) записи
        List<MemoryService.MemoryEntry> allMemories = memoryService.findRelevant(userId, request.message(), 8);

        List<MemoryService.MemoryEntry> facts  = allMemories.stream().filter(m -> m.type().equals("semantic")).toList();
        List<MemoryService.MemoryEntry> events = allMemories.stream().filter(m -> m.type().equals("episodic")).toList();

        trace.add(TraceStep.info("Результаты поиска в памяти",
                "Найдено фактов: " + facts.size() + ", событий: " + events.size()));

        for (MemoryService.MemoryEntry m : allMemories) {
            trace.add(TraceStep.retrieved(
                    String.format("[%s] схожесть: %.2f", m.type(), m.score()),
                    m.content()
            ));
        }

        // ─── ШАГ 3: AUGMENTED GENERATION ──────────────────────────────────
        // Строим контекст из найденных воспоминаний
        StringBuilder ctx = new StringBuilder();
        if (!facts.isEmpty()) {
            ctx.append("Факты о пользователе:\n");
            facts.forEach(f -> ctx.append("• ").append(f.content()).append("\n"));
        }
        if (!events.isEmpty()) {
            ctx.append("\nПрошлые события:\n");
            events.forEach(e -> ctx.append("• ").append(e.content()).append("\n"));
        }

        String prompt = "Ты персональный ассистент с доступом к памяти о пользователе. " +
                "Используй контекст для персонализированного ответа.\n\n" +
                (ctx.length() > 0 ? "=== Память о пользователе ===\n" + ctx + "\n" : "") +
                "Вопрос: " + request.message();

        trace.add(TraceStep.prompt("Промпт (память + вопрос)", prompt));
        String answer = llm.generate(prompt);
        trace.add(TraceStep.model("Персонализированный ответ", answer));

        return new ChatResponse(answer, trace);
    }

    private String extractLine(String text, String prefix) {
        for (String line : text.split("\n")) {
            if (line.startsWith(prefix)) {
                return line.substring(prefix.length()).trim();
            }
        }
        return "НЕТ";
    }
}
