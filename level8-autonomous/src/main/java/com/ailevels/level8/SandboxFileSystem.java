package com.ailevels.level8;

import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Виртуальная файловая система в памяти JVM.
 * НЕ трогает реальный диск — все «файлы» живут в Map<String, String>.
 *
 * Это синглтон-бин: состояние сохраняется между HTTP-запросами в рамках
 * одного запуска контейнера (сбрасывается при рестарте, это нормально для демо).
 *
 * Endpoints UI:
 *   GET /api/level8/sandbox — список файлов с содержимым
 */
@Service
public class SandboxFileSystem {

    // Файлы: имя → содержимое. LinkedHashMap чтобы порядок сохранялся.
    private final Map<String, String> files = Collections.synchronizedMap(new LinkedHashMap<>());

    public void write(String name, String content) {
        files.put(sanitize(name), content);
    }

    public String read(String name) {
        return files.getOrDefault(sanitize(name), "(файл не найден: " + name + ")");
    }

    public String list() {
        if (files.isEmpty()) return "Файлов нет.";
        return files.entrySet().stream()
                .map(e -> "• " + e.getKey() + " (" + e.getValue().length() + " симв.)")
                .reduce("", (a, b) -> a + "\n" + b).trim();
    }

    public Map<String, String> getAll() {
        return Collections.unmodifiableMap(files);
    }

    public void clear() {
        files.clear();
    }

    /** Убираем опасные символы из имён файлов */
    private String sanitize(String name) {
        return name.replaceAll("[^a-zA-Zа-яА-Я0-9._\\-]", "_").toLowerCase();
    }
}
