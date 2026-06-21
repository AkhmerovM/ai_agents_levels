package com.ailevels.common.dto;

import java.util.List;

/**
 * Ответ от бэкенда к UI.
 *
 * answer — итоговый ответ для пользователя
 * trace  — список шагов «под капотом», которые UI показывает в отдельной панели
 */
public record ChatResponse(String answer, List<TraceStep> trace) {}
