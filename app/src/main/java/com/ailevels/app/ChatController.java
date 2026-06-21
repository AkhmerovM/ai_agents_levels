package com.ailevels.app;

import com.ailevels.common.dto.ChatRequest;
import com.ailevels.common.dto.ChatResponse;
import com.ailevels.common.dto.PatternHandler;
import com.ailevels.common.dto.PatternInfo;
import com.ailevels.level2.RagHandler;
import com.ailevels.level2.SlidingWindowMemoryHandler;
import com.ailevels.level2.SummarizationMemoryHandler;
import com.ailevels.common.dto.TraceStep;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * REST-контроллер — точка входа для всех запросов из UI.
 *
 * Spring автоматически инжектирует все бины, реализующие PatternHandler.
 * Это значит, что при добавлении нового паттерна достаточно создать @Service,
 * реализующий PatternHandler — контроллер подхватит его автоматически.
 */
@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*") // Разрешаем запросы от любого origin (нужно для разработки)
public class ChatController {

    // Spring соберёт все бины PatternHandler из всех модулей в один список
    private final Map<String, PatternHandler> handlers;
    private final RagHandler ragHandler;
    private final SlidingWindowMemoryHandler slidingWindowHandler;
    private final SummarizationMemoryHandler summarizationHandler;

    public ChatController(List<PatternHandler> handlers,
                          RagHandler ragHandler,
                          SlidingWindowMemoryHandler slidingWindowHandler,
                          SummarizationMemoryHandler summarizationHandler) {
        // Создаём Map: "level:id" → handler для быстрого поиска
        this.handlers = handlers.stream()
                .collect(Collectors.toMap(
                        h -> h.level() + ":" + h.id(),
                        Function.identity()
                ));
        this.ragHandler = ragHandler;
        this.slidingWindowHandler = slidingWindowHandler;
        this.summarizationHandler = summarizationHandler;
    }

    /**
     * GET /api/patterns — возвращает список всех зарегистрированных паттернов.
     * UI использует это для заполнения выпадающих списков.
     */
    @GetMapping("/patterns")
    public List<PatternInfo> getPatterns() {
        return handlers.values().stream()
                .sorted((a, b) -> {
                    int levelCmp = a.level().compareTo(b.level());
                    if (levelCmp != 0) return levelCmp;
                    return a.id().compareTo(b.id());
                })
                .map(h -> new PatternInfo(h.level(), h.id(), h.title(), h.description()))
                .toList();
    }

    /**
     * POST /api/chat — основной эндпоинт для отправки сообщения.
     * Находит нужный PatternHandler по level+pattern и делегирует ему обработку.
     */
    @PostMapping("/chat")
    public ResponseEntity<ChatResponse> chat(@RequestBody ChatRequest request) {
        String key = request.level() + ":" + request.pattern();
        PatternHandler handler = handlers.get(key);

        if (handler == null) {
            return ResponseEntity.badRequest()
                    .body(new ChatResponse(
                            "Паттерн не найден: уровень=" + request.level() + ", id=" + request.pattern(),
                            List.of(TraceStep.info("Ошибка", "Доступные паттерны: " + handlers.keySet()))
                    ));
        }

        try {
            ChatResponse response = handler.handle(request);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(new ChatResponse(
                            "Ошибка: " + e.getMessage(),
                            List.of(TraceStep.info("Исключение", e.getClass().getSimpleName() + ": " + e.getMessage()))
                    ));
        }
    }

    /**
     * POST /api/level2/ingest — загружает демо-документы в векторное хранилище.
     * Вызывается один раз перед использованием RAG-паттерна.
     */
    @PostMapping("/level2/ingest")
    public ResponseEntity<ChatResponse> ingestDocuments() {
        try {
            List<TraceStep> trace = ragHandler.ingestDocuments();
            return ResponseEntity.ok(new ChatResponse(
                    "Документы успешно загружены в векторное хранилище!", trace));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(new ChatResponse("Ошибка загрузки: " + e.getMessage(),
                            List.of(TraceStep.info("Ошибка", e.getMessage()))));
        }
    }

    /**
     * POST /api/session/reset — сбрасывает память сессии.
     * Вызывается кнопкой «Сбросить сессию» в UI.
     */
    @PostMapping("/session/reset")
    public ResponseEntity<Map<String, String>> resetSession(@RequestBody Map<String, String> body) {
        String sessionId = body.getOrDefault("sessionId", "default");
        slidingWindowHandler.clearSession(sessionId);
        summarizationHandler.clearSession(sessionId);
        return ResponseEntity.ok(Map.of("status", "ok", "message", "Сессия " + sessionId + " сброшена"));
    }

    /** GET /api/level1/roles — возвращает доступные роли для role-prompting */
    @GetMapping("/level1/roles")
    public Map<String, String> getRoles() {
        return com.ailevels.level1.RolePromptingHandler.ROLES.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey,
                        e -> e.getValue().substring(0, Math.min(80, e.getValue().length())) + "..."));
    }
}
