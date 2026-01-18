# Руководство по репозиторию

## Структура проекта и модули
- Сервисы лежат в корне репозитория: `AuthService/`, `HTTPService/`, `JwtProxy/`, `MessegerParody/`.
- Исходники Java: `*/src/main/java/...`
- Ресурсы: `*/src/main/resources/` (см. `application.yml` в каждом сервисе).
- gRPC/proto:
  - `AuthService/src/main/proto/`
  - `HTTPService/src/Grpcs/proto/`
  - `MessegerParody/src/Grpcs/proto/`
- Helm (k8s) манифесты: `Helm/` с umbrella chart и подчартами сервисов.
- Тесты (когда будут): `*/src/test/java/...` (сейчас тестовых классов нет).

## Команды сборки, тестов и запуска
- Собрать JAR сервиса:
  - `mvn -f AuthService/pom.xml package -DskipTests`
- Запустить сервис локально:
  - `mvn -f HTTPService/pom.xml spring-boot:run`
- Запустить тесты (если есть):
  - `mvn -f MessegerParody/pom.xml test`
- Собрать Docker-образ (на сервис):
  - `docker build -t authservice:latest AuthService`
- Деплой в kind через Helm:
  - `helm upgrade --install hybrid ./Helm`

## Стиль кода и соглашения
- Java 21 (`maven.compiler.source/target` = 21).
- Соблюдать текущие Spring Boot соглашения и пакеты (`com.example.springexample`).
- Отступы: 4 пробела в Java, 2 пробела в YAML/Helm.
- Имена сервисов в Docker/Helm — в нижнем регистре (например, `authservice`, `messegerparody`).

## Тестирование
- Стек тестов: JUnit 5 через `spring-boot-starter-test`.
- Тесты добавлять в `*/src/test/java`, имена классов — `*Test`.
- Порог покрытия не задан; добавлять точечные тесты на новую логику.

## Конфигурация и секреты
- Конфиг для локали/k8s задается в `Helm/values.yaml`.
- Обязательные плейсхолдеры:
  - `authservice.google.clientId`
  - `authservice.google.clientSecret`
  - `httpservice.security.refreshSecret`
- Сейчас секреты хранятся в values (временно); не коммитить реальные креды.

## Коммиты и PR
- В истории — короткие прямые сообщения коммитов (например, `fixed helm build №3`).
- Коммиты держать сфокусированными; указывать сервис при необходимости (`authservice: fix oauth config`).
- В PR: краткое описание, какие тесты запускались (или “not run”), и изменения в конфиге/values.

## Разбиение больших задач
- Делить большие задачи на небольшие блоки через "beads" cli.
- Сохранять чанки в `./tasks`.

## Landing the Plane (Session Completion)

**When ending a work session**, you MUST complete ALL steps below. Work is NOT complete until `git push` succeeds.

**MANDATORY WORKFLOW:**

1. **Завести задачи на оставшуюся работу** — создать issue для всего, что нужно доделать
2. **Прогнать quality gates** (если менялся код) — тесты, линтеры, сборки
3. **Обновить статус задач** — закрыть завершенные, отметить в работе
4. **PUSH в удаленный репозиторий** — это обязательно:
   ```bash
   git pull --rebase
   bd sync
   git push
   git status  # ДОЛЖНО показать "up to date with origin"
   ```
5. **Уборка** — очистить stashes, подчистить remote ветки
6. **Проверка** — все изменения закоммичены И отправлены
7. **Передача контекста** — кратко зафиксировать, что сделано и что дальше

**КРИТИЧЕСКИЕ ПРАВИЛА:**
- Работа НЕ завершена, пока `git push` не прошел
- НЕЛЬЗЯ останавливаться до push — иначе изменения застрянут локально
- НЕ говорить “готово, пушни когда сможешь” — пуш должен сделать ты
- Если push падает, исправить и повторить

<!-- bv-agent-instructions-v1 -->

---

## Beads Workflow Integration

This project uses [beads_viewer](https://github.com/Dicklesworthstone/beads_viewer) for issue tracking. Issues are stored in `.beads/` and tracked in git.

### Essential Commands

```bash
# View issues (launches TUI - avoid in automated sessions)
bv

# CLI commands for agents (use these instead)
bd ready              # Show issues ready to work (no blockers)
bd list --status=open # All open issues
bd show <id>          # Full issue details with dependencies
bd create --title="..." --type=task --priority=2
bd update <id> --status=in_progress
bd close <id> --reason="Completed"
bd close <id1> <id2>  # Close multiple issues at once
bd sync               # Commit and push changes
```

### Workflow Pattern

1. **Start**: Run `bd ready` to find actionable work
2. **Claim**: Use `bd update <id> --status=in_progress`
3. **Work**: Implement the task
4. **Complete**: Use `bd close <id>`
5. **Sync**: Always run `bd sync` at session end

### Key Concepts

- **Dependencies**: Issues can block other issues. `bd ready` shows only unblocked work.
- **Priority**: P0=critical, P1=high, P2=medium, P3=low, P4=backlog (use numbers, not words)
- **Types**: task, bug, feature, epic, question, docs
- **Blocking**: `bd dep add <issue> <depends-on>` to add dependencies

### Session Protocol

**Before ending any session, run this checklist:**

```bash
git status              # Check what changed
git add <files>         # Stage code changes
bd sync                 # Commit beads changes
git commit -m "..."     # Commit code
bd sync                 # Commit any new beads changes
git push                # Push to remote
```

### Best Practices

- Check `bd ready` at session start to find available work
- Update status as you work (in_progress → closed)
- Create new issues with `bd create` when you discover tasks
- Use descriptive titles and set appropriate priority/type
- Always `bd sync` before ending session

<!-- end-bv-agent-instructions -->
