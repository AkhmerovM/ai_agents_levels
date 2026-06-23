package com.ailevels.level6;

import com.ailevels.common.dto.ChatRequest;
import com.ailevels.common.dto.ChatResponse;
import com.ailevels.common.dto.PatternHandler;
import com.ailevels.common.dto.TraceStep;
import dev.langchain4j.model.chat.ChatLanguageModel;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * ПАТТЕРН: Episodic Memory — эпизодическая память (конкретные события)
 *
 * Хранит конкретные СОБЫТИЯ и РЕШЕНИЯ с временными метками:
 * «В прошлый раз выбрали ноутбук ProBook за 45000»,
 * «Пользователь спрашивал про доставку 2024-01-15»
 *
 * Отличие от Semantic Memory:
 *   Semantic: факты о пользователе (статичные, без времени)
 *   Episodic: события с контекстом «когда», «что произошло», «что решили»
 *
 * При повторном запуске приложения эпизоды остаются в БД —
 * агент может сослаться на прошлые разговоры.
 *
 * Попробуй: «Я выбрал наушники SoundMax» → перезапусти браузер →
 *           задай: «Что я выбирал в прошлый раз?»
 */
@Service
public class EpisodicMemoryHandler implements PatternHandler {

    private final ChatLanguageModel llm;
    private final MemoryService memoryService;

    private static final DateTimeFormatter DISPLAY_FMT = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");

    public EpisodicMemoryHandler(ChatLanguageModel llm, MemoryService memoryService) {
        this.llm = llm;
        this.memoryService = memoryService;
    }

    @Override public String level() { return "6"; }
    @Override public String id() { return "episodic-memory"; }
    @Override public String title() { return "Episodic Memory (события с временем)"; }
    @Override public String description() {
        return "Агент помнит конкретные события с временными метками (переживает перезапуск). " +
               "Попробуй: «Я выбрал ноутбук» → сбрось сессию → «Что я выбирал?»";
    }

    @Override
    public ChatResponse handle(ChatRequest request) {
        String userId = request.userId() != null ? request.userId() : "default-user";
        List<TraceStep> trace = new ArrayList<>();
        trace.add(TraceStep.info("Пользователь", "userId = " + userId));

        String now = LocalDateTime.now().format(DISPLAY_FMT);

        // ─── ШАГ 1: ИЗВЛЕЧЬ СОБЫТИЕ ИЗ СООБЩЕНИЯ ─────────────────────────
        String eventExtractionPrompt = """
                Проанализируй сообщение. Если в нём описывается событие, решение или действие
                пользователя (что выбрал, купил, сделал, решил) — опиши его одним предложением
                в прошедшем времени. Если события нет — ответь: НЕТ

                Сообщение: "%s"
                Событие (или НЕТ):""".formatted(request.message());

        String event = llm.generate(eventExtractionPrompt).trim();
        trace.add(TraceStep.info("Извлечение события", event));

        if (!event.equalsIgnoreCase("НЕТ") && !event.isEmpty() && event.length() < 300) {
            String episodeWithTime = "[" + now + "] " + event;
            memoryService.save(userId, "episodic", episodeWithTime);
            trace.add(TraceStep.memory("💾 Эпизод сохранён (" + now + ")", event));
        }

        // ─── ШАГ 2: НАЙТИ РЕЛЕВАНТНЫЕ ПРОШЛЫЕ ЭПИЗОДЫ ────────────────────
        List<MemoryService.MemoryEntry> episodes = memoryService.findRelevant(userId, request.message(), 5)
                .stream()
                .filter(m -> m.type().equals("episodic"))
                .collect(Collectors.toList());

        if (episodes.isEmpty()) {
            trace.add(TraceStep.memory("Прошлые эпизоды", "Нет релевантных эпизодов в памяти"));
        } else {
            for (MemoryService.MemoryEntry ep : episodes) {
                trace.add(TraceStep.memory(
                        String.format("📅 Эпизод (схожесть: %.2f)", ep.score()),
                        ep.content()
                ));
            }
        }

        // ─── ШАГ 3: ОТВЕТ С ЭПИЗОДИЧЕСКОЙ ПАМЯТЬЮ ────────────────────────
        String episodesContext = episodes.isEmpty() ? "" :
                "\n\nПрошлые события пользователя:\n" +
                episodes.stream().map(e -> "• " + e.content()).collect(Collectors.joining("\n"));

        String prompt = "Ты ассистент с памятью о прошлых взаимодействиях с пользователем. " +
                "Ссылайся на прошлые события когда это уместно." +
                episodesContext + "\n\nТекущий вопрос: " + request.message();

        trace.add(TraceStep.prompt("Промпт с эпизодами", prompt));
        String answer = llm.generate(prompt);
        trace.add(TraceStep.model("Ответ", answer));

        return new ChatResponse(answer, trace);
    }
}
