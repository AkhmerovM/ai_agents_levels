# Multi-stage сборка: сначала компилируем, потом создаём минимальный runtime-образ

# STAGE 1: Сборка
FROM maven:3.9-eclipse-temurin-21 AS builder

WORKDIR /build

# Копируем POM-файлы для кэширования зависимостей
# Docker кэширует этот слой и не перекачивает зависимости, если POM не менялся
COPY pom.xml .
COPY common/pom.xml common/pom.xml
COPY level1-pure-llm/pom.xml level1-pure-llm/pom.xml
COPY level2-memory-rag/pom.xml level2-memory-rag/pom.xml
COPY level3-tools/pom.xml level3-tools/pom.xml
COPY level4-agents/pom.xml level4-agents/pom.xml
COPY level5-multiagent/pom.xml level5-multiagent/pom.xml
COPY level6-longterm-memory/pom.xml level6-longterm-memory/pom.xml
COPY level7-planning/pom.xml level7-planning/pom.xml
COPY level8-autonomous/pom.xml level8-autonomous/pom.xml
COPY app/pom.xml app/pom.xml

# Загружаем зависимости (этот слой кэшируется)
RUN mvn dependency:go-offline -q

# Копируем исходники и собираем
COPY common/src common/src
COPY level1-pure-llm/src level1-pure-llm/src
COPY level2-memory-rag/src level2-memory-rag/src
COPY level3-tools/src level3-tools/src
COPY level4-agents/src level4-agents/src
COPY level5-multiagent/src level5-multiagent/src
COPY level6-longterm-memory/src level6-longterm-memory/src
COPY level7-planning/src level7-planning/src
COPY level8-autonomous/src level8-autonomous/src
COPY app/src app/src

RUN mvn package -q -DskipTests

# STAGE 2: Runtime
# ВАЖНО: используем Debian (не Alpine), потому что ONNX Runtime (внутри AllMiniLmL6V2)
# требует libstdc++.so.6 из GNU C++ Runtime. Alpine использует musl libc и не содержит
# этой библиотеки, что приводит к ошибке при старте EmbeddingModel.
FROM eclipse-temurin:21-jre-jammy

WORKDIR /app

# Копируем только собранный JAR из предыдущего этапа
COPY --from=builder /build/app/target/app-*.jar app.jar

# Порт приложения
EXPOSE 8080

# Запуск: настройки JVM для контейнера
ENTRYPOINT ["java", \
            "-XX:+UseContainerSupport", \
            "-XX:MaxRAMPercentage=75.0", \
            "-jar", "app.jar"]
