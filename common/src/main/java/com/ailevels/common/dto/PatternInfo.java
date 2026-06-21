package com.ailevels.common.dto;

/**
 * Метаданные паттерна для GET /api/patterns.
 * UI использует это для заполнения выпадающих списков.
 */
public record PatternInfo(String level, String id, String title, String description) {}
