package com.ailevels.common.config;

import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Конфигурация LLM-клиента.
 *
 * Мы используем OpenRouter — сервис-прокси, совместимый с OpenAI API.
 * Поэтому подключаемся через langchain4j-open-ai, просто меняя baseUrl.
 *
 * Почему OpenRouter, а не напрямую OpenAI?
 * OpenRouter даёт доступ к десяткам моделей через один API-ключ,
 * что удобно для учебного стенда.
 */
@Configuration
public class LlmConfig {

    @Value("${openrouter.api-key}")
    private String apiKey;

    @Value("${openrouter.base-url:https://openrouter.ai/api/v1}")
    private String baseUrl;

    @Value("${llm.model:openai/gpt-4o-mini}")
    private String modelName;

    /**
     * Создаём единственный бин ChatLanguageModel, который будут использовать
     * все паттерны всех уровней. Spring автоматически инжектирует его туда,
     * где он объявлен через @Autowired или конструктор.
     */
    @Bean
    public ChatLanguageModel chatLanguageModel() {
        return OpenAiChatModel.builder()
                // Указываем базовый URL OpenRouter (он совместим с форматом OpenAI API)
                .baseUrl(baseUrl)
                // API-ключ из переменной окружения OPENROUTER_API_KEY
                .apiKey(apiKey)
                // Модель по умолчанию: дешёвая gpt-4o-mini, поддерживает function calling
                .modelName(modelName)
                // Таймаут на ответ — 60 секунд (некоторые модели медленные)
                .timeout(Duration.ofSeconds(60))
                // Температура 0.7 — баланс между креативностью и предсказуемостью
                .temperature(0.7)
                .build();
    }
}
