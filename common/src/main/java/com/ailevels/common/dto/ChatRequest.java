package com.ailevels.common.dto;

/**
 * Запрос от UI к бэкенду.
 *
 * level     — уровень паттерна: "1"–"8"
 * pattern   — идентификатор паттерна: "react", "rag", "function-calling" и т.д.
 * message   — текст сообщения пользователя
 * sessionId — идентификатор сессии (генерируется на клиенте),
 *             нужен для паттернов с памятью (уровень 2)
 * role      — опциональная роль для паттерна "role-prompting" (уровень 1)
 * userId    — идентификатор пользователя (генерируется на клиенте, хранится в localStorage),
 *             нужен для долгой памяти уровня 6 — привязывает воспоминания к конкретному пользователю
 */
public record ChatRequest(
        String level,
        String pattern,
        String message,
        String sessionId,
        String role,
        String userId
) {}
