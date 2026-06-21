package com.ailevels.level3;

import dev.langchain4j.agent.tool.Tool;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Random;

/**
 * Набор детерминированных инструментов для демонстрации Function Calling.
 *
 * Все инструменты намеренно простые и не требуют внешних API.
 * Аннотация @Tool — это то, как LangChain4j сообщает LLM о доступных инструментах:
 *   1. LangChain4j читает @Tool и генерирует JSON-схему для каждого метода
 *   2. Эта схема отправляется в API вместе с запросом (в поле "tools")
 *   3. Модель решает, какой инструмент вызвать, и возвращает tool_call с аргументами
 *   4. LangChain4j автоматически вызывает нужный Java-метод с этими аргументами
 *   5. Результат снова отправляется в LLM для формирования финального ответа
 *
 * Этот «пинг-понг» между LLM и инструментами — и есть суть Function Calling.
 */
public class DemoTools {

    // Псевдослучайные данные о погоде для городов
    private static final Map<String, String[]> WEATHER_DATA = Map.of(
        "москва",    new String[]{"облачно", "15°C", "ветер 3 м/с", "влажность 72%"},
        "санкт-петербург", new String[]{"дождь", "12°C", "ветер 5 м/с", "влажность 85%"},
        "новосибирск", new String[]{"ясно", "8°C", "ветер 2 м/с", "влажность 45%"},
        "сочи",      new String[]{"солнечно", "24°C", "ветер 1 м/с", "влажность 60%"},
        "екатеринбург", new String[]{"переменная облачность", "10°C", "ветер 4 м/с", "влажность 55%"}
    );

    /**
     * Инструмент 1: Погода
     * @Tool описание — это то, что видит LLM при выборе инструмента.
     * Чем чётче описание, тем точнее модель выбирает нужный инструмент.
     */
    @Tool("Возвращает текущую погоду в указанном городе России. " +
          "Используй для вопросов о погоде, температуре, климате в конкретном городе.")
    public String getWeather(String city) {
        String[] data = WEATHER_DATA.get(city.toLowerCase());
        if (data == null) {
            // Для неизвестных городов генерируем псевдослучайные данные
            String[] conditions = {"ясно", "облачно", "дождь", "туман", "снег"};
            int temp = new Random(city.hashCode()).nextInt(30) - 5;
            return String.format("Погода в %s: %s, %d°C", city,
                conditions[Math.abs(city.hashCode()) % conditions.length], temp);
        }
        return String.format("Погода в %s: %s, %s, %s, %s", city, data[0], data[1], data[2], data[3]);
    }

    /**
     * Инструмент 2: Калькулятор
     * Реально вычисляет математические выражения — это важно для наглядности:
     * модель вызывает инструмент, а не «угадывает» ответ.
     */
    @Tool("Вычисляет математическое выражение и возвращает результат. " +
          "Поддерживает +, -, *, /, скобки. Пример: '(15 + 7) * 3'. " +
          "Используй для любых математических вычислений.")
    public String calculator(String expression) {
        try {
            // Простой вычислитель без eval (безопасно)
            double result = evaluateExpression(expression);
            // Проверяем, целое ли число
            if (result == Math.floor(result) && !Double.isInfinite(result)) {
                return "Результат: " + (long) result;
            }
            return String.format("Результат: %.4f", result);
        } catch (Exception e) {
            return "Ошибка вычисления: " + e.getMessage() + ". Проверь выражение.";
        }
    }

    /**
     * Инструмент 3: Текущее время
     */
    @Tool("Возвращает текущее дату и время в указанном часовом поясе. " +
          "Принимает строку часового пояса в формате IANA, например 'Europe/Moscow', 'UTC', 'Asia/Novosibirsk'. " +
          "По умолчанию используй 'Europe/Moscow' для России.")
    public String getCurrentTime(String timezone) {
        try {
            ZoneId zoneId = ZoneId.of(timezone);
            ZonedDateTime now = ZonedDateTime.now(zoneId);
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss z");
            return "Текущее время (" + timezone + "): " + now.format(formatter);
        } catch (Exception e) {
            // Если часовой пояс неверный, возвращаем UTC
            ZonedDateTime now = ZonedDateTime.now(ZoneId.of("UTC"));
            return "Текущее время (UTC, пояс '" + timezone + "' не найден): " +
                   now.format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss z"));
        }
    }

    /**
     * Простой вычислитель выражений без использования eval/ScriptEngine.
     * Поддерживает +, -, *, /, скобки и числа с плавающей точкой.
     */
    private double evaluateExpression(String expr) {
        return new Object() {
            int pos = -1, ch;

            void nextChar() { ch = (++pos < expr.length()) ? expr.charAt(pos) : -1; }

            boolean eat(int charToEat) {
                while (ch == ' ') nextChar();
                if (ch == charToEat) { nextChar(); return true; }
                return false;
            }

            double parse() {
                nextChar();
                double x = parseExpression();
                if (pos < expr.length()) throw new RuntimeException("Неожиданный символ: " + (char)ch);
                return x;
            }

            double parseExpression() {
                double x = parseTerm();
                while (true) {
                    if      (eat('+')) x += parseTerm();
                    else if (eat('-')) x -= parseTerm();
                    else return x;
                }
            }

            double parseTerm() {
                double x = parseFactor();
                while (true) {
                    if      (eat('*')) x *= parseFactor();
                    else if (eat('/')) x /= parseFactor();
                    else return x;
                }
            }

            double parseFactor() {
                if (eat('+')) return +parseFactor();
                if (eat('-')) return -parseFactor();
                double x;
                int startPos = pos;
                if (eat('(')) {
                    x = parseExpression();
                    if (!eat(')')) throw new RuntimeException("Нет закрывающей скобки");
                } else if (Character.isDigit(ch) || ch == '.') {
                    while (Character.isDigit(ch) || ch == '.') nextChar();
                    x = Double.parseDouble(expr.substring(startPos, pos));
                } else {
                    throw new RuntimeException("Неожиданный символ: " + (char)ch);
                }
                return x;
            }
        }.parse();
    }
}
