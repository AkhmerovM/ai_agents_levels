package com.ailevels.level5;

import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.agent.tool.ToolSpecifications;

import java.util.List;
import java.util.Map;

/** Инструменты для уровня 5: поиск и веб. */
public class MultiAgentTools {

    private static final Map<String, String> SEARCH_DB = Map.of(
            "ноутбук",   "ТОП ноутбуков: 1. ProBook X360 (45 000 р.) — универсальный. 2. GamePro RTX (89 000 р.) — для игр.",
            "смартфон",  "Лучшие смартфоны: 1. QuickPhone 12 (28 000 р.) — флагман среднего сегмента.",
            "наушники",  "Рейтинг наушников: 1. SoundMax Pro (4 500 р.) — шумоподавление. 2. Bass Elite (12 000 р.).",
            "клавиатура","Клавиатуры: MechType K100 (6 800 р.) — механическая. UltraSlim (2 500 р.) — мембранная.",
            "мышь",      "Мыши: SpeedClick G7 (2 200 р.) — игровая. OfficePoint (800 р.) — для офиса.",
            "монитор",   "Мониторы: ViewPro 27\" (18 000 р.) — IPS. GameView 144Hz (24 000 р.) — для игр.",
            "доставка",  "Доставка: Москва — 1-2 дня (300 р.), Россия — 3-7 дней (от 500 р.).",
            "гарантия",  "Гарантия: 12-24 месяца. Возврат 14 дней. 50+ сервисных центров."
    );

    private static final Map<String, Map<String, Object>> CATALOG = Map.of(
            "laptop-001",     Map.of("name", "Ноутбук ProBook X360",     "price", 45000),
            "phone-001",      Map.of("name", "Смартфон QuickPhone 12",   "price", 28000),
            "headphones-001", Map.of("name", "Наушники SoundMax Pro",    "price", 4500),
            "keyboard-001",   Map.of("name", "Клавиатура MechType K100", "price", 6800),
            "mouse-001",      Map.of("name", "Мышь SpeedClick G7",       "price", 2200)
    );

    @Tool("Ищет товары в каталоге по ключевому слову.")
    public String searchProducts(String query) {
        String result = CATALOG.entrySet().stream()
                .filter(e -> e.getValue().get("name").toString().toLowerCase().contains(query.toLowerCase()))
                .map(e -> e.getKey() + ": " + e.getValue().get("name") + " — " + e.getValue().get("price") + " руб.")
                .reduce("", (a, b) -> a.isEmpty() ? b : a + "\n" + b);
        return result.isEmpty() ? "Ничего не найдено по: " + query : result;
    }

    @Tool("Выполняет поиск информации по теме (мок, без интернета).")
    public String mockWebSearch(String query) {
        String q = query.toLowerCase();
        for (Map.Entry<String, String> e : SEARCH_DB.entrySet())
            if (q.contains(e.getKey())) return e.getValue();
        return "По запросу «" + query + "»: информация не найдена. Уточните запрос.";
    }

    public List<ToolSpecification> searchSpecs() {
        return ToolSpecifications.toolSpecificationsFrom(this).stream()
                .filter(s -> s.name().equals("mockWebSearch"))
                .toList();
    }

    public List<ToolSpecification> allSpecs() {
        return ToolSpecifications.toolSpecificationsFrom(this);
    }

    public String dispatch(String name, String args) {
        String q = com.ailevels.level5.MultiAgentTools.extractArg(args, "query");
        return switch (name) {
            case "searchProducts" -> searchProducts(q);
            case "mockWebSearch"  -> mockWebSearch(q);
            default -> "Инструмент '" + name + "' не найден.";
        };
    }

    public static String extractArg(String json, String key) {
        String s = "\"" + key + "\"";
        int ki = json.indexOf(s); if (ki < 0) return json;
        int ci = json.indexOf(":", ki + s.length());
        int si = ci + 1; while (si < json.length() && json.charAt(si) == ' ') si++;
        if (si < json.length() && json.charAt(si) == '"') {
            int ei = json.indexOf('"', si + 1); return ei < 0 ? "" : json.substring(si + 1, ei);
        }
        int ei = si; while (ei < json.length() && json.charAt(ei) != ',' && json.charAt(ei) != '}') ei++;
        return json.substring(si, ei).trim().replaceAll("\"", "");
    }
}
