package com.ailevels.level1;

import com.ailevels.common.dto.ChatRequest;
import com.ailevels.common.dto.ChatResponse;
import com.ailevels.common.dto.PatternHandler;
import com.ailevels.common.dto.TraceStep;
import dev.langchain4j.model.chat.ChatLanguageModel;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * ПАТТЕРН: Few-Shot Prompting
 *
 * Идея: перед реальным запросом пользователя в промпт добавляем 2-3 примера
 * «входные данные → ожидаемый выход». Модель «подхватывает» паттерн из примеров
 * и применяет его к новому входу — без явного обучения.
 *
 * Демо-кейс: классификация тональности отзыва.
 * Попробуй: «Доставили быстро, товар отличный!» или «Ужасное качество, больше не закажу».
 *
 * Почему это работает? Трансформерные модели хорошо улавливают паттерны из контекста.
 * Few-shot — это «обучение в контексте» (in-context learning), без обновления весов.
 */
@Service
public class FewShotHandler implements PatternHandler {

    private final ChatLanguageModel llm;

    // Примеры «вход → выход» для задачи классификации тональности
    private static final String FEW_SHOT_EXAMPLES = """
            Ты классификатор тональности отзывов. Определяй: ПОЗИТИВНЫЙ, НЕГАТИВНЫЙ или НЕЙТРАЛЬНЫЙ.

            Примеры:

            Отзыв: "Товар пришёл быстро, упакован хорошо, соответствует описанию."
            Тональность: ПОЗИТИВНЫЙ

            Отзыв: "Качество отвратительное, распалось через неделю. Деньги на ветер."
            Тональность: НЕГАТИВНЫЙ

            Отзыв: "Обычный товар. Ничего особенного, но и плохого сказать нечего."
            Тональность: НЕЙТРАЛЬНЫЙ

            Теперь определи тональность следующего отзыва (ответь только одним словом):

            Отзыв: "%s"
            Тональность:""";

    public FewShotHandler(ChatLanguageModel llm) {
        this.llm = llm;
    }

    @Override public String level() { return "1"; }
    @Override public String id() { return "few-shot"; }
    @Override public String title() { return "Few-Shot Prompting"; }
    @Override public String description() {
        return "В промпт добавляются примеры «вход→выход» перед реальным запросом. " +
               "Демо: классификация тональности отзыва. Введи текст отзыва.";
    }

    @Override
    public ChatResponse handle(ChatRequest request) {
        // Подставляем вопрос пользователя в шаблон с примерами
        String fullPrompt = FEW_SHOT_EXAMPLES.formatted(request.message());

        // Отправляем составной промпт в LLM
        String answer = llm.generate(fullPrompt);

        // Трассировка: показываем весь промпт с примерами, чтобы пользователь видел «магию»
        List<TraceStep> trace = List.of(
                TraceStep.info("Техника", "Few-Shot: в промпт добавлены 3 примера классификации"),
                TraceStep.prompt("Полный промпт (с примерами)", fullPrompt),
                TraceStep.model("Ответ модели", answer.trim())
        );

        return new ChatResponse(answer.trim(), trace);
    }
}
