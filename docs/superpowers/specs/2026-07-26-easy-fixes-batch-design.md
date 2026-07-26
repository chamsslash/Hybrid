# Дизайн: пачка лёгких фиксов (iqf + 93e + csc)

Дата: 2026-07-26
Ветка-источник: `dev`
Beads: `iqf` (P1), `93e` (P2), `csc` (P4)

## Контекст и отбор

Из 10 ready-задач отобраны три, которые действительно локальны, не меняют
контрактов между сервисами и чинятся без участия пользователя. Явно исключены:

- `820` (пустой `sub` в JWT) — глубокий/системный контракт AuthService↔HTTPService,
  чинится через отдельный brainstorming, не «сразу».
- `q1b` (потеря сессии после регистрации) — при анализе выяснилось, что механизм
  silent-refresh (`auth.js: ensureAccessToken` + axios 401-интерцептор) уже реализован
  в SPA-шелле `chatlist.js`. Тикет либо устарел, либо баг тонкий → требует live-воспроизведения,
  вынесен из этой пачки.
- `gq9`, `c2k`, `2zw`, `ok9`, `26d` — требуют решений пользователя или объёмны.

Каждый фикс идёт в **отдельную ветку от `dev`** (по правилу проекта), мёрж в `dev` —
отдельным шагом после проверки сборки.

---

## Фикс 1 — iqf (P1 security): убрать утечку секретов в логи

**Проблема.** При default-профиле verbose-логирование льёт JWT/заголовки/тела запросов в stdout.

**Затронутые файлы (main-дерево, не worktrees):**
- `HTTPService/src/main/resources/application.yml`
- `MessegerParody/src/main/resources/application.yml`

(`AuthService/src/main/resources/application.yml` — чист, грепом утечек не найдено.)

**Решение (самый лёгкий вариант — просто чтобы не текло, без профилей).**
Правки в `HTTPService/application.yml`:
- строка 1: `debug: true` → убрать (или `false`);
- строка 17: `spring.webflux.log-request-details: true` → убрать (главный источник — печатает заголовки/тела);
- строка 83: `org.springframework.security: TRACE` → `INFO`;
- строки 86-91 (`web.reactive`, `web.servlet`, `web.method`, `server.adapter`,
  `ServletHttpHandlerAdapter`, `reactor.netty` на уровне TRACE/DEBUG) → `INFO`
  (они тоже логируют детали запросов);
- `com.example.springexample: DEBUG` → `INFO` (строка 90).

Правки в `MessegerParody/application.yml`:
- строка 1: `debug: true` → убрать (или `false`).

**НЕ трогаем:** `management.endpoints` (prometheus/health/info), kafka/grpc-настройки,
`log-bean-definition-overriding` (не секрет).

**Acceptance.** При запуске с default-профилем (как в `deploy-kind.sh`, который профиль не ставит)
в stdout нет JWT/Authorization/тел запросов.

**Проверка.** `/kdeploy` → выполнить register/login → `kubectl logs` HTTPService/MessegerParody →
grep по `Authorization`, `Bearer`, `refresh`, `password` — не должно быть значений секретов.

**Риск.** Минимальный: только уровни логирования, поведение сервисов не меняется.

---

## Фикс 2 — 93e (P2): нормальные сообщения об ошибках регистрации/логина

**Root cause.** `AuthGrpc.authRegister`/`authlogin` возвращают `null` на любой не-200 статус.
Внутри `Mono.fromCallable(...)` `null`-результат схлопывает Mono в **пустой** (не error),
поэтому дальнейшая цепочка `flatMap` молча пропускается и финальный
`switchIfEmpty` в `registerHandle` (WEBFLUX_Service.java:134) отдаёт универсальное
«Отсутствуют данные формы.» вместо реальной причины (напр. статус `666`
«User with such name already exists» из AuthService).

Дополнительно: в `registerHandle` (WEBFLUX_Service.java:110-115) ветка обработки `666`
**недостижима** — guard `if (!"200".equals(...)) return error` срабатывает раньше.

**Затронутые файлы:**
- `HTTPService/src/main/java/com/example/springexample/Services/AuthGrpc.java`
- `HTTPService/src/main/java/com/example/springexample/Services/WEBFLUX_Service.java`
- `HTTPService/src/main/resources/static/register.js`

**Решение (типизированное исключение — выбор пользователя).**

1. Новый класс исключения (напр. `HTTPService/.../Services/AuthResponseException.java` или
   в `Utils`), несущий `status` и `message` из `AuthResponse`:
   ```java
   public class AuthResponseException extends RuntimeException {
       private final String status;
       public AuthResponseException(String status, String message) {
           super(message);
           this.status = status;
       }
       public String getStatus() { return status; }
   }
   ```

2. `AuthGrpc.authRegister` / `authlogin`: вместо `return null` на не-200 —
   `throw new AuthResponseException(status, message)` (сохранив happy-path 200 без изменений
   и текущее логирование). Для `login` статус `404` → сообщение «пользователь не найден»,
   для `register` статус `666`/иное → реальный `message` из AuthResponse.

3. `WEBFLUX_Service.registerHandle` / `loginHandle`: убрать теперь-мёртвую ветку `666`,
   добавить `.onErrorResume(AuthResponseException.class, e -> ServerResponse.status(...).bodyValue(e.getMessage()))`.
   Маппинг статуса на HTTP-код (зафиксировано): дубликат имени при регистрации → `409 CONFLICT`;
   неверные креды на login → `401 UNAUTHORIZED`; прочие не-200 → `400 BAD_REQUEST`.
   Тело ответа во всех случаях — реальный `message` (строка). Acceptance завязан на текст
   сообщения, а не на конкретный код; фронт (`register.js`) читает `error.response?.data`
   независимо от кода, так что смена кода его не ломает.

4. `register.js:70`: `error.response?.data?.message` → `error.response?.data`
   (сервер отдаёт **строку** `text/plain`, как уже правильно сделано в `chatcreate.js:40`).

**Acceptance.**
1. `AuthGrpc.authRegister/authlogin` пробрасывают реальный статус/сообщение, а не `null`→пустой Mono.
2. `register.js` показывает текст ошибки от сервера, а не generic HTTP-статус.
3. Ручной тест: повторная регистрация с существующим логином показывает понятное
   «пользователь уже существует», а не «нет данных формы».

**Тесты (TDD).** Юнит на `AuthGrpc` (мок blocking-stub возвращает статус 666/404 → ожидаем
`AuthResponseException` с сообщением, а не `null`). Реактивный тест на `registerHandle`
(WebTestClient/StepVerifier): не-200 от authGrpc → ответ с реальным телом ошибки, а не
«Отсутствуют данные формы.».

**Риск.** Локальный для HTTPService; контракт HTTPService↔AuthService (proto/статусы) не меняется —
меняется только внутренняя обработка ответа и маппинг на HTTP. Проверить, что фронт
(`register.js`, `login`-страница) корректно показывает новый код/тело.

---

## Фикс 3 — csc (P4): косметика лога в KafkaConsumer

**Файл:** `HTTPService/src/main/java/com/example/springexample/KafkaConsumer.java:35`

**Решение.** В default-ветке `switch (imageUploadDTO.getTargetType())`:
```java
default:
    log.warn("Unknown targetType in imageUploadDTO: {}", imageUploadDTO.getTargetType());
```
(было `getTargetId()` + текст «Unknown targetId»). Только текст лога, поведение не меняется.

**Acceptance.** Лог default-ветки печатает `targetType`, а не `targetId`.

**Риск.** Нулевой (косметика).

---

## План диспатча (subagent-driven-development)

Текущая ветка — `dev`. ⚠️ Хазард: worktree-сабагенты ветвятся от `origin/main`, не от рабочей
ветки. Поэтому фиче-ветки создаются **от `dev`** явно, агентам даются точные имена веток;
worktree-изоляцию не используем (или используем с явной базой `dev`).

| Агент | Ветка | Задача | Параллельность |
|---|---|---|---|
| A (`developer`) | `fix/log-secret-leak` | iqf — конфиг | параллельно (изолирован) |
| B (`developer`) | `fix/kafka-log-targettype` | csc — однострочник | параллельно (изолирован) |
| C (`developer`) | `fix/auth-error-messages` | 93e — backend+frontend+тесты | параллельно (свои файлы) |

Все три ветки трогают непересекающиеся файлы, поэтому все три агента могут идти параллельно.
Каждый агент: реализует по TDD, собирает через JDK21 (`/jtest` / `JAVA_HOME=JDK21`), коммитит
в свою ветку. Мёрж в `dev` и деплой-проверка (`/kdeploy`, live-grep логов для iqf) —
отдельным финальным шагом с пользователем.

## Вне пачки (заведено в beads)
- `q1b` → open, комментарий с анализом; будет live-верифицирован отдельно.
- `j35` (новый) → SPIKE/ADR «доводить ли фронт до полного SPA».
