# Spendings

Личный учёт расходов, доходов, вкладов и целей. Spring Boot 4 (Web MVC) + Thymeleaf + htmx, PostgreSQL 18, Java 25.

План и исследование рынка — в [docs/](docs/).

## Запуск

Нужен Docker. JDK 25 ставить не обязательно: Gradle скачает его сам (toolchain).

**Разработка** — приложение из IDE или Gradle, PostgreSQL поднимается автоматически из `compose.yaml`:

```bash
./gradlew bootRun
```

**Всё в контейнерах:**

```bash
docker compose --profile app up --build
```

Откройте http://localhost:8080. При первом запуске мастер попросит создать учётную запись владельца.

Установка на свой сервер с HTTPS (Tailscale или Caddy), бэкапами и восстановлением — [docs/03-deploy.md](docs/03-deploy.md). Коротко: `cp .env.example .env`, заполнить, `docker compose --profile app up -d --build`.

Демо-данные в пустую базу: `SPENDINGS_DEMO_OPERATIONS=50000 ./gradlew bootRun`.

## Проверки

```bash
./gradlew check
```

Запускает форматирование (Spotless, `./gradlew spotlessApply` исправляет), тесты на PostgreSQL в Testcontainers и проверку границ модулей Spring Modulith. Скорость на 50 тысячах операций — `./gradlew perfTest`.
