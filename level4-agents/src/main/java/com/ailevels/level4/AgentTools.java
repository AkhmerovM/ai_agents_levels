package com.ailevels.level4;

import dev.langchain4j.agent.tool.Tool;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.stream.Collectors;

/**
 * Набор детерминированных мок-инструментов для уровней 4–5.
 * Все инструменты локальные — не обращаются к внешним API.
 */
public class AgentTools {

    // ─────────── Базовые инструменты (дублируем из level3) ───────────

    private static final Map<String, String[]> WEATHER_DATA = Map.of(
            "москва",          new String[]{"облачно", "15°C", "ветер 3 м/с"},
            "санкт-петербург", new String[]{"дождь", "12°C", "ветер 5 м/с"},
            "новосибирск",     new String[]{"ясно", "8°C", "ветер 2 м/с"},
            "сочи",            new String[]{"солнечно", "24°C", "ветер 1 м/с"},
            "казань",          new String[]{"переменная облачность", "13°C", "ветер 4 м/с"},
            "екатеринбург",    new String[]{"туман", "10°C", "ветер 2 м/с"}
    );

    @Tool("Возвращает текущую погоду в указанном городе России.")
    public String getWeather(String city) {
        String[] d = WEATHER_DATA.get(city.toLowerCase());
        if (d == null) {
            String[] conds = {"ясно", "облачно", "дождь"};
            int temp = new Random(city.hashCode()).nextInt(25);
            return "Погода в " + city + ": " + conds[Math.abs(city.hashCode()) % 3] + ", " + temp + "°C";
        }
        return "Погода в " + city + ": " + d[0] + ", " + d[1] + ", " + d[2];
    }

    @Tool("Вычисляет математическое выражение (+,-,*,/ и скобки). Пример: '(100 + 50) * 1.2'")
    public String calculator(String expression) {
        try {
            double result = eval(expression);
            return result == Math.floor(result)
                    ? "Результат: " + (long) result
                    : String.format("Результат: %.2f", result);
        } catch (Exception e) {
            return "Ошибка вычисления: " + e.getMessage();
        }
    }

    @Tool("Возвращает текущее время в указанном часовом поясе (формат IANA, например 'Europe/Moscow').")
    public String getCurrentTime(String timezone) {
        try {
            ZonedDateTime now = ZonedDateTime.now(ZoneId.of(timezone));
            return "Время (" + timezone + "): " + now.format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss z"));
        } catch (Exception e) {
            return "Время (UTC): " + ZonedDateTime.now(ZoneId.of("UTC"))
                    .format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss z"));
        }
    }

    // ─────────── Каталог товаров ───────────

    private static final Map<String, Map<String, Object>> CATALOG = Map.of(
            "laptop-001",      Map.of("name", "Ноутбук ProBook X360",      "price", 45000, "category", "электроника"),
            "phone-001",       Map.of("name", "Смартфон QuickPhone 12",    "price", 28000, "category", "электроника"),
            "headphones-001",  Map.of("name", "Наушники SoundMax Pro",     "price", 4500,  "category", "аксессуары"),
            "keyboard-001",    Map.of("name", "Клавиатура MechType K100",  "price", 6800,  "category", "периферия"),
            "mouse-001",       Map.of("name", "Мышь SpeedClick G7",        "price", 2200,  "category", "периферия"),
            "monitor-001",     Map.of("name", "Монитор ViewPro 27\"",      "price", 18000, "category", "мониторы"),
            "tablet-001",      Map.of("name", "Планшет TabMax 10",         "price", 15000, "category", "электроника"),
            "speaker-001",     Map.of("name", "Колонка BassBox 200",       "price", 3200,  "category", "аксессуары")
    );

    @Tool("Ищет товары в каталоге по ключевому слову (название или категория). Возвращает список найденных товаров с их ID.")
    public String searchProducts(String query) {
        String q = query.toLowerCase();
        List<String> found = CATALOG.entrySet().stream()
                .filter(e -> e.getValue().get("name").toString().toLowerCase().contains(q)
                          || e.getValue().get("category").toString().toLowerCase().contains(q))
                .map(e -> e.getKey() + ": " + e.getValue().get("name") + " — " + e.getValue().get("price") + " руб.")
                .collect(Collectors.toList());
        return found.isEmpty()
                ? "Товары по запросу «" + query + "» не найдены."
                : "Найдено " + found.size() + " товар(а):\n" + String.join("\n", found);
    }

    @Tool("Возвращает цену товара по его ID (например 'laptop-001'). Используй ID из результатов searchProducts.")
    public String getPrice(String productId) {
        Map<String, Object> p = CATALOG.get(productId.toLowerCase());
        if (p == null) return "Товар с ID '" + productId + "' не найден.";
        return p.get("name") + " — цена: " + p.get("price") + " руб. (ID: " + productId + ")";
    }

    // ─────────── Мок веб-поиска ───────────

    private static final Map<String, String> SEARCH_DB = Map.of(
            "ноутбук",    "ТОП ноутбуков 2024: 1. ProBook X360 (45 000 р.) — отличный баланс цены и производительности. 2. UltraBook Slim (62 000 р.) — для дизайнеров. 3. GamePro RTX (89 000 р.) — для игр.",
            "смартфон",   "Лучшие смартфоны 2024: 1. QuickPhone 12 (28 000 р.) — хорошая камера. 2. ProMax 15 (95 000 р.) — флагман. 3. BudgetPhone A5 (8 000 р.) — бюджетный.",
            "наушники",   "Рейтинг наушников: 1. SoundMax Pro (4 500 р.) — отличное шумоподавление. 2. Bass Elite (12 000 р.) — студийные. 3. SportBuds (2 000 р.) — для спорта.",
            "доставка",   "Условия доставки: Москва — 1 день (300 р.), Россия — 3-7 дней (от 500 р.), бесплатно от 5 000 р.",
            "гарантия",   "Гарантия на электронику: 12-24 месяца. Возврат в течение 14 дней. Сервисные центры в 50+ городах.",
            "акция",      "Текущие акции: скидка 10% на периферию до конца месяца, бесплатная доставка при заказе от 3 000 р."
    );

    @Tool("Выполняет поиск в базе знаний (мок, без интернета). Возвращает заранее заготовленные результаты по теме.")
    public String mockWebSearch(String query) {
        String q = query.toLowerCase();
        for (Map.Entry<String, String> e : SEARCH_DB.entrySet()) {
            if (q.contains(e.getKey())) return "Результат поиска по «" + query + "»:\n" + e.getValue();
        }
        return "По запросу «" + query + "» найдено: информация о товарах и услугах интернет-магазина ТехноМаг. " +
               "Уточните запрос (ноутбук, смартфон, наушники, доставка, гарантия, акция).";
    }

    // ─────────── Вспомогательный вычислитель ───────────

    /** Разбирает и вычисляет арифметическое выражение */
    public static double eval(String expr) {
        return new Object() {
            int pos = -1, ch;
            void next() { ch = (++pos < expr.length()) ? expr.charAt(pos) : -1; }
            boolean eat(int c) { while (ch == ' ') next(); if (ch == c) { next(); return true; } return false; }
            double parse() { next(); double x = expr(); if (pos < expr.length()) throw new RuntimeException("неожиданный символ: " + (char)ch); return x; }
            double expr() { double x = term(); for(;;) { if(eat('+')) x+=term(); else if(eat('-')) x-=term(); else return x; } }
            double term() { double x = factor(); for(;;) { if(eat('*')) x*=factor(); else if(eat('/')) x/=factor(); else return x; } }
            double factor() {
                if (eat('+')) return factor(); if (eat('-')) return -factor();
                int s = pos; double x;
                if (eat('(')) { x = expr(); if (!eat(')')) throw new RuntimeException("нет ')'"); }
                else if (Character.isDigit(ch)||ch=='.') { while(Character.isDigit(ch)||ch=='.') next(); x = Double.parseDouble(expr.substring(s,pos)); }
                else throw new RuntimeException("неожиданный символ: " + (char)ch);
                return x;
            }
        }.parse();
    }

    /** Диспетчеризация вызова инструмента по имени и JSON-аргументам */
    public String dispatch(String name, String argsJson) {
        return switch (name) {
            case "getWeather"    -> getWeather(arg(argsJson, "city"));
            case "calculator"    -> calculator(arg(argsJson, "expression"));
            case "getCurrentTime"-> getCurrentTime(arg(argsJson, "timezone"));
            case "searchProducts"-> searchProducts(arg(argsJson, "query"));
            case "getPrice"      -> getPrice(arg(argsJson, "productId"));
            case "mockWebSearch" -> mockWebSearch(arg(argsJson, "query"));
            default -> "Инструмент '" + name + "' не найден.";
        };
    }

    /** Простое извлечение строкового значения из JSON */
    public static String arg(String json, String key) {
        String search = "\"" + key + "\"";
        int ki = json.indexOf(search); if (ki < 0) return "";
        int ci = json.indexOf(":", ki + search.length());
        // значение может быть строкой или числом
        int si = ci + 1; while (si < json.length() && json.charAt(si) == ' ') si++;
        if (si < json.length() && json.charAt(si) == '"') {
            int ei = json.indexOf('"', si + 1); return ei < 0 ? "" : json.substring(si + 1, ei);
        }
        // число
        int ei = si; while (ei < json.length() && (Character.isDigit(json.charAt(ei)) || json.charAt(ei) == '.' || json.charAt(ei) == '-')) ei++;
        return json.substring(si, ei).trim();
    }
}
