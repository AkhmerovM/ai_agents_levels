package com.ailevels.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Точка входа в приложение.
 *
 * @SpringBootApplication включает:
 *   - @EnableAutoConfiguration — автоконфигурация Spring Boot
 *   - @ComponentScan(basePackages = "com.ailevels") — сканирует бины во всех модулях
 */
@SpringBootApplication(scanBasePackages = "com.ailevels")
public class AiLevelsApplication {
    public static void main(String[] args) {
        SpringApplication.run(AiLevelsApplication.class, args);
    }
}
