package com.ailevels.app;

import com.ailevels.common.dto.ChatRequest;
import com.ailevels.common.dto.ChatResponse;
import com.ailevels.common.dto.PatternHandler;
import com.ailevels.common.dto.PatternInfo;
import com.ailevels.common.dto.TraceStep;
import com.ailevels.level2.RagHandler;
import com.ailevels.level2.SlidingWindowMemoryHandler;
import com.ailevels.level2.SummarizationMemoryHandler;
import com.ailevels.level6.MemoryService;
import com.ailevels.level8.SandboxFileSystem;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*")
public class ChatController {

    private final Map<String, PatternHandler> handlers;
    private final RagHandler ragHandler;
    private final SlidingWindowMemoryHandler slidingWindowHandler;
    private final SummarizationMemoryHandler summarizationHandler;
    private final MemoryService memoryService;
    private final SandboxFileSystem sandboxFileSystem;

    public ChatController(List<PatternHandler> handlers,
                          RagHandler ragHandler,
                          SlidingWindowMemoryHandler slidingWindowHandler,
                          SummarizationMemoryHandler summarizationHandler,
                          MemoryService memoryService,
                          SandboxFileSystem sandboxFileSystem) {
        this.handlers = handlers.stream()
                .collect(Collectors.toMap(h -> h.level() + ":" + h.id(), Function.identity()));
        this.ragHandler = ragHandler;
        this.slidingWindowHandler = slidingWindowHandler;
        this.summarizationHandler = summarizationHandler;
        this.memoryService = memoryService;
        this.sandboxFileSystem = sandboxFileSystem;
    }

    /** GET /api/patterns — все зарегистрированные паттерны для UI */
    @GetMapping("/patterns")
    public List<PatternInfo> getPatterns() {
        return handlers.values().stream()
                .sorted((a, b) -> {
                    int lc = a.level().compareTo(b.level());
                    return lc != 0 ? lc : a.id().compareTo(b.id());
                })
                .map(h -> new PatternInfo(h.level(), h.id(), h.title(), h.description()))
                .toList();
    }

    /** POST /api/chat — основной эндпоинт */
    @PostMapping("/chat")
    public ResponseEntity<ChatResponse> chat(@RequestBody ChatRequest request) {
        String key = request.level() + ":" + request.pattern();
        PatternHandler handler = handlers.get(key);
        if (handler == null) {
            return ResponseEntity.badRequest().body(new ChatResponse(
                    "Паттерн не найден: " + key,
                    List.of(TraceStep.info("Доступные ключи", String.join(", ", handlers.keySet())))));
        }
        try {
            return ResponseEntity.ok(handler.handle(request));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(new ChatResponse(
                    "Ошибка: " + e.getMessage(),
                    List.of(TraceStep.info("Exception", e.getClass().getSimpleName() + ": " + e.getMessage()))));
        }
    }

    /** POST /api/level2/ingest — загрузка RAG-документов */
    @PostMapping("/level2/ingest")
    public ResponseEntity<ChatResponse> ingestDocuments() {
        try {
            List<TraceStep> trace = ragHandler.ingestDocuments();
            return ResponseEntity.ok(new ChatResponse("Документы загружены!", trace));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(new ChatResponse(
                    "Ошибка: " + e.getMessage(), List.of(TraceStep.info("Ошибка", e.getMessage()))));
        }
    }

    /** POST /api/session/reset — сброс памяти сессии (уровень 2) */
    @PostMapping("/session/reset")
    public ResponseEntity<Map<String, String>> resetSession(@RequestBody Map<String, String> body) {
        String sessionId = body.getOrDefault("sessionId", "default");
        slidingWindowHandler.clearSession(sessionId);
        summarizationHandler.clearSession(sessionId);
        return ResponseEntity.ok(Map.of("status", "ok", "sessionId", sessionId));
    }

    /** GET /api/level1/roles — роли для role-prompting */
    @GetMapping("/level1/roles")
    public Map<String, String> getRoles() {
        return com.ailevels.level1.RolePromptingHandler.ROLES.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey,
                        e -> e.getValue().substring(0, Math.min(80, e.getValue().length())) + "..."));
    }

    /** GET /api/level6/memory — показать сохранённые воспоминания пользователя */
    @GetMapping("/level6/memory")
    public ResponseEntity<Map<String, Object>> getMemory(@RequestParam(defaultValue = "default-user") String userId) {
        List<MemoryService.MemoryEntry> all = memoryService.findAll(userId);
        List<Map<String, String>> result = all.stream()
                .filter(m -> !m.content().startsWith("_CLEARED_AT_"))
                .map(m -> Map.of(
                        "type", m.type(),
                        "timestamp", m.timestamp(),
                        "content", m.content()
                ))
                .toList();
        return ResponseEntity.ok(Map.of("userId", userId, "memories", result, "count", result.size()));
    }

    /** DELETE /api/level6/memory — очистить воспоминания пользователя */
    @DeleteMapping("/level6/memory")
    public ResponseEntity<Map<String, String>> clearMemory(@RequestParam(defaultValue = "default-user") String userId) {
        memoryService.clearUser(userId);
        return ResponseEntity.ok(Map.of("status", "ok", "message", "Память пользователя " + userId + " очищена"));
    }

    /** GET /api/level8/sandbox — содержимое виртуальной файловой системы */
    @GetMapping("/level8/sandbox")
    public ResponseEntity<Map<String, Object>> getSandbox() {
        Map<String, String> files = sandboxFileSystem.getAll();
        return ResponseEntity.ok(Map.of("files", files, "count", files.size()));
    }

    /** DELETE /api/level8/sandbox — очистить песочницу */
    @DeleteMapping("/level8/sandbox")
    public ResponseEntity<Map<String, String>> clearSandbox() {
        sandboxFileSystem.clear();
        return ResponseEntity.ok(Map.of("status", "ok", "message", "Песочница очищена"));
    }
}
