package com.ailevels.level1;

import com.ailevels.common.dto.ChatRequest;
import com.ailevels.common.dto.ChatResponse;
import com.ailevels.common.dto.PatternHandler;
import com.ailevels.common.dto.TraceStep;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * ПАТТЕРН: Role Prompting — ролевой промпт
 *
 * Идея: через system-сообщение задаём модели конкретную роль/персону.
 * Это меняет стиль, тон, словарный запас и угол зрения на проблему.
 *
 * Почему это работает? LLM обучены на огромном корпусе текстов разных людей.
 * Задавая роль, мы активируем соответствующее «подпространство» обученных паттернов.
 *
 * Попробуй: задай один и тот же вопрос с разными ролями — разница будет разительной.
 * Например: «Объясни, что такое квантовая запутанность» с ролями:
 *   - учитель физики
 *   - пятилетний ребёнок (ответ от лица ребёнка)
 *   - строгий юрист
 */
@Service
public class RolePromptingHandler implements PatternHandler {

    private final ChatLanguageModel llm;

    // Доступные роли: ключ — id роли, значение — system-промпт
    public static final Map<String, String> ROLES = Map.of(
        "teacher",
            "Ты дружелюбный и терпеливый учитель. Объясняешь сложные вещи просто, " +
            "используешь аналогии и примеры из повседневной жизни. Поощряешь любопытство.",
        "lawyer",
            "Ты строгий и точный юрист. Говоришь формальным языком, ссылаешься на принципы " +
            "и нормы. Указываешь на риски и оговорки. Избегаешь неточностей.",
        "child",
            "Ты отвечаешь так, как будто тебе 5 лет. Используешь простые слова, " +
            "задаёшь наивные вопросы, удивляешься, иногда отвлекаешься на что-то смешное.",
        "scientist",
            "Ты учёный-аналитик. Говоришь точно и структурированно. Указываешь на " +
            "неопределённости, ссылаешься на данные и исследования. Не делаешь выводов без оснований.",
        "comedian",
            "Ты остроумный комик. Отвечаешь с юмором, добавляешь шутки и каламбуры, " +
            "но при этом по существу. Делаешь общение лёгким и весёлым."
    );

    public RolePromptingHandler(ChatLanguageModel llm) {
        this.llm = llm;
    }

    @Override public String level() { return "1"; }
    @Override public String id() { return "role-prompting"; }
    @Override public String title() { return "Role Prompting"; }
    @Override public String description() {
        return "System-промпт задаёт модели роль (учитель / юрист / ребёнок / учёный / комик). " +
               "Выбери роль в поле «Роль» и задай любой вопрос.";
    }

    @Override
    public ChatResponse handle(ChatRequest request) {
        // Определяем роль: берём из запроса или используем учителя по умолчанию
        String roleId = (request.role() != null && ROLES.containsKey(request.role()))
                ? request.role() : "teacher";
        String systemPrompt = ROLES.get(roleId);

        // LangChain4j поддерживает отправку системного и пользовательского сообщений раздельно.
        // Под капотом они преобразуются в стандартный формат {role: "system"/"user", content: "..."}
        Response<AiMessage> response = llm.generate(
                List.of(
                        SystemMessage.from(systemPrompt),
                        UserMessage.from(request.message())
                )
        );

        String answer = response.content().text();

        // Трассировка: показываем применённый system-промпт
        List<TraceStep> trace = List.of(
                TraceStep.info("Выбранная роль", roleId),
                TraceStep.prompt("System-промпт (роль)", systemPrompt),
                TraceStep.prompt("Вопрос пользователя", request.message()),
                TraceStep.model("Ответ модели", answer)
        );

        return new ChatResponse(answer, trace);
    }
}
