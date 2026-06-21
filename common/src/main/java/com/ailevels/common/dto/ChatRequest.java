package com.ailevels.common.dto;

/**
 * Запрос от UI к бэкенду.
 *
 * level     — уровень паттерна: "1", "2" или "3"
 * pattern   — идентификатор паттерна: "few-shot", "rag", "function-calling" и т.д.
 * message   — текст сообщения пользователя
 * sessionId — идентификатор сессии (генерируется на клиенте),
 *             нужен для паттернов с памятью (уровень 2)
 * role      — опциональная роль для паттерна "role-prompting" (уровень 1)
 */
public record ChatRequest(
        String level,
        String pattern,
        String message,
        String sessionId,
        String role
) {}
