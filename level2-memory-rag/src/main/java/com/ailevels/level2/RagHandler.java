package com.ailevels.level2;

import com.ailevels.common.dto.ChatRequest;
import com.ailevels.common.dto.ChatResponse;
import com.ailevels.common.dto.PatternHandler;
import com.ailevels.common.dto.TraceStep;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * ПАТТЕРН: RAG (Retrieval-Augmented Generation) — генерация с дополнением из хранилища
 *
 * Что делает RAG:
 * 1. Пользователь задаёт вопрос
 * 2. Вопрос преобразуется в вектор (эмбеддинг) через локальную модель
 * 3. В векторном хранилище ищем top-k наиболее похожих чанков документов
 * 4. Найденные чанки добавляются в промпт как «контекст»
 * 5. LLM отвечает НА ОСНОВЕ этого контекста, а не из своих «воспоминаний»
 *
 * Зачем это нужно?
 * LLM «знает» только то, что было в обучающих данных. RAG позволяет отвечать
 * на вопросы о вашей конкретной документации, которой в обучении не было.
 *
 * Демо: документы вымышленного магазина «ТехноМаг».
 * Попробуй: «Как вернуть товар?», «Когда работает магазин?», «Сколько стоит доставка?»
 */
@Service
public class RagHandler implements PatternHandler {

    private final ChatLanguageModel llm;
    private final EmbeddingModel embeddingModel;
    private final EmbeddingStore<TextSegment> embeddingStore;

    // Флаг: загружены ли демо-документы в хранилище
    private volatile boolean documentsLoaded = false;

    // Демо-документы: описание вымышленного магазина «ТехноМаг»
    private static final List<String> DEMO_DOCUMENTS = List.of(
        """
        Магазин ТехноМаг — Часы работы и контакты

        Режим работы: понедельник-пятница с 9:00 до 21:00, суббота-воскресенье с 10:00 до 20:00.
        Праздничные дни: магазин закрыт 1 января и 9 мая, в остальные праздники работаем по расписанию выходного дня.
        Адрес: г. Москва, ул. Технологическая, д. 42.
        Телефон горячей линии: 8-800-100-42-42 (бесплатно по России, с 8:00 до 22:00).
        Email для обращений: support@technomag.ru
        """,
        """
        Магазин ТехноМаг — Политика возврата и обмена

        Срок возврата: товар надлежащего качества можно вернуть в течение 14 дней с момента покупки.
        Условия возврата: товар должен быть в оригинальной упаковке, без следов использования,
        с полным комплектом аксессуаров и документов.

        Товары, не подлежащие возврату: программное обеспечение, аудио/видео записи, карты памяти
        и флеш-накопители (после вскрытия упаковки).

        Возврат товара ненадлежащего качества: в течение гарантийного срока (обычно 12-24 месяца).
        Для возврата обратитесь в магазин с чеком или позвоните на горячую линию.

        Обмен товара: возможен в течение 14 дней на аналогичный или другой товар с доплатой/возвратом разницы.
        """,
        """
        Магазин ТехноМаг — Условия доставки

        Доставка по Москве: от 300 рублей, срок 1-2 рабочих дня.
        Экспресс-доставка по Москве: 600 рублей, доставка в день заказа (заказ до 14:00).

        Доставка по России: от 500 рублей, срок 3-7 рабочих дней (зависит от региона).
        Доставка в отдалённые регионы: от 800 рублей, срок до 14 рабочих дней.

        Бесплатная доставка: при заказе от 5000 рублей доставка по Москве бесплатна,
        при заказе от 10000 рублей — бесплатна по всей России.

        Способы получения: курьер, пункт выдачи заказов (более 500 точек по России),
        почтомат, Почта России.

        Отслеживание заказа: трек-номер отправляется на email после отправки.
        """,
        """
        Магазин ТехноМаг — Программа лояльности «ТехноБаллы»

        Как работает: за каждые 100 рублей покупки начисляется 1 балл.
        1 балл = 1 рубль при следующих покупках.

        Уровни участника:
        - Стандарт: 0-999 баллов (накопление 1% от суммы)
        - Серебро: 1000-4999 баллов (накопление 1.5%)
        - Золото: 5000+ баллов (накопление 2%)

        Срок действия баллов: 12 месяцев с момента последней покупки.
        Максимальное списание: до 30% стоимости товара.
        """,
        """
        Магазин ТехноМаг — Гарантийное обслуживание

        Гарантия на технику: 12 месяцев (на отдельные категории — 24 месяца).
        Дополнительная гарантия: можно приобрести при покупке товара (+1 или +2 года).

        Гарантийный ремонт: бесплатно в авторизованных сервисных центрах.
        Список сервисных центров доступен на сайте technomag.ru/service.

        Случаи, не покрываемые гарантией: механические повреждения, попадание жидкости,
        самостоятельный ремонт или вскрытие корпуса, неправильная эксплуатация.

        Срок ремонта: до 45 дней по закону, обычно 7-14 дней.
        """
    );

    public RagHandler(ChatLanguageModel llm, EmbeddingModel embeddingModel,
                      EmbeddingStore<TextSegment> embeddingStore) {
        this.llm = llm;
        this.embeddingModel = embeddingModel;
        this.embeddingStore = embeddingStore;
    }

    @Override public String level() { return "2"; }
    @Override public String id() { return "rag"; }
    @Override public String title() { return "RAG (Retrieval-Augmented Generation)"; }
    @Override public String description() {
        return "Вопрос преобразуется в вектор, похожие чанки находятся в БД и подставляются в промпт. " +
               "Сначала нажми «Загрузить документы», затем задай вопрос о магазине ТехноМаг.";
    }

    /**
     * Загружает демо-документы в векторное хранилище.
     * Вызывается через POST /api/level2/ingest.
     *
     * Процесс:
     * 1. Текст документа разбивается на чанки (сплиттер)
     * 2. Каждый чанк преобразуется в вектор (эмбеддинг)
     * 3. Пара (вектор, текст) сохраняется в pgvector
     */
    public List<TraceStep> ingestDocuments() {
        List<TraceStep> trace = new ArrayList<>();

        // Сплиттер: разбивает документ на чанки по ~300 символов с перекрытием 30 символов.
        // Перекрытие нужно, чтобы не потерять контекст на границах чанков.
        DocumentSplitter splitter = DocumentSplitters.recursive(300, 30);

        int totalChunks = 0;
        for (int i = 0; i < DEMO_DOCUMENTS.size(); i++) {
            String docText = DEMO_DOCUMENTS.get(i);
            Document doc = Document.from(docText);

            // Разбиваем документ на чанки
            List<TextSegment> chunks = splitter.split(doc);

            for (TextSegment chunk : chunks) {
                // Преобразуем текст чанка в вектор через локальную модель all-MiniLM-L6-v2
                // Под капотом: токенизация → трансформер → усреднение токен-векторов
                Embedding embedding = embeddingModel.embed(chunk).content();
                // Сохраняем пару (вектор, текст) в PostgreSQL
                embeddingStore.add(embedding, chunk);
                totalChunks++;
            }

            trace.add(TraceStep.info(
                "Документ " + (i + 1) + " загружен",
                "Разбит на " + chunks.size() + " чанков: " +
                docText.substring(0, Math.min(80, docText.length())).trim() + "..."
            ));
        }

        documentsLoaded = true;
        trace.add(TraceStep.info("Итого", "Загружено " + DEMO_DOCUMENTS.size() +
                " документов, создано " + totalChunks + " векторов в pgvector"));
        return trace;
    }

    @Override
    public ChatResponse handle(ChatRequest request) {
        List<TraceStep> trace = new ArrayList<>();

        if (!documentsLoaded) {
            return new ChatResponse(
                "Сначала загрузите документы через кнопку «Загрузить документы».",
                List.of(TraceStep.info("Предупреждение", "Документы не загружены в векторное хранилище"))
            );
        }

        String question = request.message();

        // Шаг 1: Преобразуем вопрос пользователя в вектор (тем же способом, что и документы)
        Embedding questionEmbedding = embeddingModel.embed(question).content();
        trace.add(TraceStep.info("Эмбеддинг вопроса",
            "Вопрос преобразован в вектор размерностью 384 с помощью all-MiniLM-L6-v2"));

        // Шаг 2: Ищем в pgvector top-3 похожих чанка
        // SQL под капотом: SELECT * FROM rag_embeddings ORDER BY embedding <=> $question_vector LIMIT 3
        List<EmbeddingMatch<TextSegment>> matches = embeddingStore.findRelevant(questionEmbedding, 3);

        // Шаг 3: Показываем найденные чанки в трассировке
        StringBuilder contextBuilder = new StringBuilder();
        for (int i = 0; i < matches.size(); i++) {
            EmbeddingMatch<TextSegment> match = matches.get(i);
            String chunkText = match.embedded().text();
            double score = match.score();
            contextBuilder.append(chunkText).append("\n\n");

            trace.add(TraceStep.retrieved(
                String.format("Чанк #%d (релевантность: %.2f)", i + 1, score),
                chunkText
            ));
        }

        // Шаг 4: Подставляем найденные чанки в промпт как «контекст»
        String context = contextBuilder.toString().trim();
        String fullPrompt = """
                Ты ассистент магазина ТехноМаг. Отвечай ТОЛЬКО на основе предоставленного контекста.
                Если в контексте нет ответа, так и скажи.

                Контекст:
                %s

                Вопрос пользователя: %s
                """.formatted(context, question);

        trace.add(TraceStep.prompt("Промпт с контекстом RAG", fullPrompt));

        // Шаг 5: Отправляем промпт с контекстом в LLM
        String answer = llm.generate(fullPrompt);
        trace.add(TraceStep.model("Ответ модели", answer));

        return new ChatResponse(answer, trace);
    }
}
