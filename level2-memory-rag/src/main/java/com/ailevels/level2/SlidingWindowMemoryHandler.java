package com.ailevels.level2;

import com.ailevels.common.dto.ChatRequest;
import com.ailevels.common.dto.ChatResponse;
import com.ailevels.common.dto.PatternHandler;
import com.ailevels.common.dto.TraceStep;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ПАТТЕРН: Sliding Window Memory — память со скользящим окном
 *
 * Проблема: LLM stateless — каждый запрос независим, она не помнит предыдущие.
 * Решение: мы сами храним историю диалога и добавляем её в каждый новый запрос.
 *
 * Sliding Window: храним последние N сообщений, старые удаляем.
 * Это аналог «окна контекста» — модель видит только последнюю часть диалога.
 *
 * LangChain4j предоставляет MessageWindowChatMemory:
 * - автоматически добавляет/удаляет сообщения
 * - удерживает не более maxMessages сообщений в памяти
 *
 * Попробуй: представься, затем задай несколько вопросов, потом спроси «как меня зовут?»
 */
@Service
public class SlidingWindowMemoryHandler implements PatternHandler {

    private final ChatLanguageModel llm;

    // Окно памяти для каждой сессии: sessionId → ChatMemory
    // ConcurrentHashMap потому что запросы могут приходить одновременно
    private final Map<String, ChatMemory> sessionMemories = new ConcurrentHashMap<>();

    // Размер окна: сколько последних сообщений помним
    private static final int WINDOW_SIZE = 6; // 3 пары «вопрос-ответ»

    public SlidingWindowMemoryHandler(ChatLanguageModel llm) {
        this.llm = llm;
    }

    @Override public String level() { return "2"; }
    @Override public String id() { return "sliding-window"; }
    @Override public String title() { return "Sliding Window Memory"; }
    @Override public String description() {
        return "Хранит последние " + WINDOW_SIZE + " сообщений диалога. " +
               "Старые выпадают из окна. Попробуй длинный диалог и увидишь, как ранние сообщения исчезают.";
    }

    @Override
    public ChatResponse handle(ChatRequest request) {
        String sessionId = request.sessionId() != null ? request.sessionId() : "default";

        // Получаем или создаём память для этой сессии
        // MessageWindowChatMemory — это буфер: как только > WINDOW_SIZE сообщений,
        // самые старые удаляются. System-сообщение не считается.
        ChatMemory memory = sessionMemories.computeIfAbsent(
            sessionId,
            id -> MessageWindowChatMemory.withMaxMessages(WINDOW_SIZE)
        );

        // Добавляем новое сообщение пользователя в память
        memory.add(UserMessage.from(request.message()));

        // Получаем список всех сообщений в текущем окне
        List<ChatMessage> messagesInWindow = memory.messages();

        // Добавляем системное сообщение В НАЧАЛО для LLM (не хранится в памяти)
        List<ChatMessage> allMessages = new ArrayList<>();
        allMessages.add(SystemMessage.from("Ты дружелюбный ассистент. Помни контекст разговора."));
        allMessages.addAll(messagesInWindow);

        // Отправляем всю историю в LLM
        Response<AiMessage> response = llm.generate(allMessages);
        String answer = response.content().text();

        // Сохраняем ответ ассистента в память
        memory.add(AiMessage.from(answer));

        // Трассировка: показываем что в окне памяти
        List<TraceStep> trace = new ArrayList<>();
        trace.add(TraceStep.info("Размер окна", WINDOW_SIZE + " сообщений (не считая system)"));

        // Показываем все сообщения, которые попали в промпт
        for (int i = 0; i < messagesInWindow.size(); i++) {
            ChatMessage msg = messagesInWindow.get(i);
            String role = msg instanceof UserMessage ? "Пользователь" : "Ассистент";
            String msgText = msg instanceof UserMessage
                    ? ((UserMessage) msg).singleText()
                    : ((AiMessage) msg).text();
            trace.add(TraceStep.memory(
                "Сообщение в окне #" + (i + 1) + " (" + role + ")",
                msgText
            ));
        }

        trace.add(TraceStep.model("Ответ модели", answer));

        return new ChatResponse(answer, trace);
    }

    /** Сбросить память сессии (вызывается кнопкой «Сбросить сессию» в UI) */
    public void clearSession(String sessionId) {
        sessionMemories.remove(sessionId);
    }
}
