# SPA-миграция (Вариант 2) — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Объединить экраны authed-зоны (chatlist/chat/createchat) и формы входа (welcome/register) в один app-shell с клиентским роутером на History API, убрав полные перезагрузки между ними (и закрыв `q1b`).

**Architecture:** Один Thymeleaf-шаблон-шелл `app.html` с корнем `#app`, отдаётся сервером на всех shell-роутах. Клиентский роутер (`router.js`) на History API по пути монтирует нужную вью (`mount(params)`) и размонтирует предыдущую (`unmount()` с disconnect STOMP). Данные и действия — через существующие `/api/*` и POST-эндпоинты (часть меняет ответ на JSON). Auth-флоу Google-OAuth (`callback`, `startauth`, `collect-fingerprint`) остаётся серверным.

**Tech Stack:** Spring WebFlux + Servlet-MVC (гибрид), Thymeleaf, gRPC-клиенты, ванильные ES-модули (axios/SockJS/StompJS/DOMPurify как глобалы с CDN), Trusted Types + nonce CSP, JUnit5 (JDK21).

**Спека:** `docs/superpowers/specs/2026-07-26-spa-authed-zone-migration-design.md`

## Global Constraints

- **Сборка/тесты только на JDK21:** `export JAVA_HOME=/Users/gyattalert/Library/Java/JavaVirtualMachines/ms-21.0.9/Contents/Home` (дефолтный JDK ломает Mockito). Прогон: из `HTTPService/` → `./mvnw test` (или `mvn` если нет wrapper).
- **Нет JS-тест-раннера, вводить его вне объёма.** Test-cycle фронтенд-задач = `./mvnw -q compile` (ресурсы копируются) + **live-верификация через Chrome MCP** по чек-листу §7 спеки после `/kdeploy`. Классический «write failing JS test» не применяется — вместо него явные шаги live-проверки.
- **Токены не трогаем:** access — in-memory (`inmemory.js`), refresh — httpOnly cookie, silent-refresh (`auth.js`) — как есть. Меняем только навигацию, не модель хранения.
- **CSRF cookie-based** (`CookieCsrfTokenRepository`): формы читают cookie `XSRF-TOKEN` → заголовок `X-CSRF-TOKEN`. Серверный `<input name="_csrf">` в клиентских формах не нужен.
- **Trusted Types:** любой `innerHTML` — только через `policy.createHTML(...)` (глобальная политика из `trusted_policy.js`).
- **CDN-глобалы:** `axios`, `SockJS`, `Stomp`, `DOMPurify` приходят из `<script>`-тегов shell (не ES-import); shell грузит их ДО `app.js`.
- **Коммиты:** конвенциональные, БЕЗ `Co-Authored-By`. Частые, по шагам.
- **Ветка:** вся работа в ветке `feat/spa-migration-v2` от `dev`.
- **Не трогать вне объёма:** `callback.html`/`callback.js`, `fingerpring_collector.html`, `/startauth`, `/verifylogin`, `/exchangeTokens`, `/api/*`-контроллер.

## File Structure

**Новые файлы:**
- `HTTPService/src/main/resources/templates/app.html` — единый app-shell.
- `HTTPService/src/main/resources/static/app.js` — точка входа shell (регистрация роутов, старт).
- `HTTPService/src/main/resources/static/router.js` — роутер History API.
- `HTTPService/src/main/resources/static/stomp-lifecycle.js` — трекинг/teardown STOMP.
- `HTTPService/src/main/resources/static/views/welcome.view.js` ← из `welcome.js`.
- `HTTPService/src/main/resources/static/views/register.view.js` ← из `register.js`.
- `HTTPService/src/main/resources/static/views/chatlist.view.js` ← из `chatlist.js`.
- `HTTPService/src/main/resources/static/views/chat.view.js` ← из `index.js`.
- `HTTPService/src/main/resources/static/views/createchat.view.js` ← из `chatcreate.js`.

**Модифицируются:**
- `WEBFLUX_Service.java` — `getChatList`/`renderChatPage`→shell, новый `renderCreateChatPage`, `handleCreateChat`→JSON.
- `WebFluxConfig.java` — роут `GET /createchat`.
- `ReactiveSecurityConfig.java` — permitAll `/createchat`.
- `MVC_Service.java` — `GetWelcome`/`GetRegisterPage`→shell, `GetCreateChat`→redirect.

**Удаляются (в финальной задаче):** `templates/{chats_list,index,chatcreatepage,welcome,register}.html`, `static/{chatlist,index,chatcreate,welcome,register}.js`.

---

## Task 1: Бэкенд — app-shell и shell-роуты

**Files:**
- Create: `HTTPService/src/main/resources/templates/app.html`
- Modify: `HTTPService/src/main/java/com/example/springexample/Services/WEBFLUX_Service.java` (`getChatList`, `renderChatPage`, +`renderCreateChatPage`)
- Modify: `HTTPService/src/main/java/com/example/springexample/WebFluxConfig.java` (роут `/createchat`)
- Modify: `HTTPService/src/main/java/com/example/springexample/ReactiveSecurityConfig.java` (permitAll)
- Modify: `HTTPService/src/main/java/com/example/springexample/Services/MVC_Service.java` (`GetWelcome`, `GetRegisterPage`, `GetCreateChat`)
- Test: `HTTPService/src/test/java/com/example/springexample/Services/AppShellRenderTest.java`

**Interfaces:**
- Produces: shell-роуты `GET /welcome`, `/registerpage`, `/reactive/chatlist`, `/reactive/chat`, `/reactive/createchat` — все отдают `app.html` (HTML c `<div id="app">` и `<script type="module" src="/app.js">`).
- Consumes: существующий `ParseWithThymeLeaf(model, tmpl, engine)`; `generateandputNonce(model, response)` в MVC.

- [ ] **Step 1: Создать `templates/app.html`**

```html
<!DOCTYPE html>
<html xmlns:th="http://www.thymeleaf.org">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>TEBEGRAM</title>
    <link rel="stylesheet" th:href="@{/css/style2.css}">
</head>
<body>
<div id="app"></div>
<div id="toastContainer" class="toast-container"></div>
<div id="global-spinner" class="global-spinner-overlay" style="display: none;">
    <div class="big-spinner"></div>
</div>

<script src="https://cdn.jsdelivr.net/npm/axios@1/dist/axios.min.js" th:attr="nonce=${nonce}"></script>
<script src="https://cdn.jsdelivr.net/npm/sockjs-client@1/dist/sockjs.min.js" th:attr="nonce=${nonce}"></script>
<script src="https://cdn.jsdelivr.net/npm/stompjs@2.3.3/lib/stomp.min.js" th:attr="nonce=${nonce}"></script>
<script src="https://cdn.jsdelivr.net/npm/dompurify@3.0.9/dist/purify.min.js" th:attr="nonce=${nonce}"></script>
<script src="/trusted_policy.js" th:attr="nonce=${nonce}"></script>
<script type="module" src="/app.js" th:attr="nonce=${nonce}"></script>
</body>
</html>
```

- [ ] **Step 2: WEBFLUX_Service — рендерить shell + добавить `renderCreateChatPage`**

В `getChatList`: заменить `ParseWithThymeLeaf(model, "chats_list", templateEngine)` → `ParseWithThymeLeaf(model, "app", templateEngine)` (строку `model.put("pathPrefix", "/reactive")` можно оставить — не мешает).
В `renderChatPage`: заменить `ParseWithThymeLeaf(model, "index", templateEngine)` → `ParseWithThymeLeaf(model, "app", templateEngine)`.
Добавить метод рядом с `renderChatPage`:

```java
public Mono<ServerResponse> renderCreateChatPage(ServerRequest request, ISpringWebFluxTemplateEngine templateEngine) {
    Map<String, Object> model = new HashMap<>();
    return ParseWithThymeLeaf(model, "app", templateEngine)
            .flatMap(htmlContent -> ServerResponse.ok()
                    .contentType(MediaType.TEXT_HTML)
                    .bodyValue(htmlContent))
            .onErrorResume(e -> {
                log.error("createchat shell render failed", e);
                return ServerResponse.status(500).contentType(MediaType.TEXT_PLAIN)
                        .bodyValue("Internal Server Error: " + e.getMessage());
            });
}
```

- [ ] **Step 3: WebFluxConfig — роут `GET /createchat` + подключить в combinedRoutes**

Добавить бин рядом с `chatPageRouter`:

```java
@Bean
public RouterFunction<ServerResponse> createChatPageRouter(WEBFLUX_Service wf_handler, ISpringWebFluxTemplateEngine templateEngine){
    return route(GET("/createchat"), req -> wf_handler.renderCreateChatPage(req, templateEngine));
}
```

Добавить параметр `RouterFunction<ServerResponse> createChatPageRouter` в сигнатуру `webFluxHttpHandler(...)` и включить в цепочку: `.and(createChatPageRouter)` в `combinedRoutes`.

- [ ] **Step 4: ReactiveSecurityConfig — permitAll `/createchat`**

Найти `.pathMatchers(org.springframework.http.HttpMethod.GET, "/chatlist", "/chat").permitAll()` и заменить на:

```java
.pathMatchers(org.springframework.http.HttpMethod.GET, "/chatlist", "/chat", "/createchat").permitAll()
```

- [ ] **Step 5: MVC_Service — welcome/register → shell, createchatpage → redirect**

`GetWelcome`: оставить `generateandputNonce(model, response)` и чтение csrf-лога, но `return "welcome";` → `return "app";` (параметр `?error=` больше не кладём в модель — его читает клиентская вью из query; строки с `model.addAttribute("error", error)` удалить).
`GetRegisterPage`: `return "register";` → `return "app";`.
`GetCreateChat`: тело заменить на серверный редирект:

```java
@GetMapping(path = "/createchatpage")
public String GetCreateChat() {
    return "redirect:/reactive/createchat";
}
```

- [ ] **Step 6: Написать тест рендера shell**

`HTTPService/src/test/java/com/example/springexample/Services/AppShellRenderTest.java`:

```java
package com.example.springexample.Services;

import org.junit.jupiter.api.Test;
import org.thymeleaf.spring6.SpringWebFluxTemplateEngine;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;
import org.thymeleaf.context.Context;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AppShellRenderTest {

    private SpringWebFluxTemplateEngine engine() {
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");
        resolver.setTemplateMode(TemplateMode.HTML);
        resolver.setCharacterEncoding("UTF-8");
        SpringWebFluxTemplateEngine e = new SpringWebFluxTemplateEngine();
        e.setTemplateResolver(resolver);
        return e;
    }

    @Test
    void appShellRendersRootAndEntrypoint() {
        Context ctx = new Context();
        Map<String, Object> model = new HashMap<>();
        model.put("nonce", "testnonce");
        ctx.setVariables(model);

        String html = engine().process("app", ctx);

        assertThat(html).contains("id=\"app\"");
        assertThat(html).contains("src=\"/app.js\"");
        assertThat(html).contains("nonce=\"testnonce\"");
    }
}
```

- [ ] **Step 7: Прогнать тест на JDK21**

Run: `cd HTTPService && JAVA_HOME=/Users/gyattalert/Library/Java/JavaVirtualMachines/ms-21.0.9/Contents/Home ./mvnw test -Dtest=AppShellRenderTest`
Expected: PASS (шаблон `app` резолвится, содержит `#app`, `/app.js`, nonce).

- [ ] **Step 8: Полная сборка (не сломали компиляцию)**

Run: `cd HTTPService && JAVA_HOME=.../ms-21.0.9/Contents/Home ./mvnw -q test`
Expected: BUILD SUCCESS (существующие 27 тестов + новый зелёные).

- [ ] **Step 9: Commit**

```bash
git add HTTPService/src/main/resources/templates/app.html \
        HTTPService/src/main/java/com/example/springexample/Services/WEBFLUX_Service.java \
        HTTPService/src/main/java/com/example/springexample/WebFluxConfig.java \
        HTTPService/src/main/java/com/example/springexample/ReactiveSecurityConfig.java \
        HTTPService/src/main/java/com/example/springexample/Services/MVC_Service.java \
        HTTPService/src/test/java/com/example/springexample/Services/AppShellRenderTest.java
git commit -m "feat(spa): единый app-shell на всех shell-роутах (welcome/register/chatlist/chat/createchat)"
```

---

## Task 2: Бэкенд — `handleCreateChat` → 200 JSON

**Files:**
- Modify: `HTTPService/src/main/java/com/example/springexample/Services/WEBFLUX_Service.java` (`handleCreateChat`, success-ветка)
- Test: `HTTPService/src/test/java/com/example/springexample/Services/CreateChatJsonResponseTest.java`

**Interfaces:**
- Produces: `POST /reactive/createchat` на успехе → `200` JSON `{"chatId": <long>, "title": <string>}`. Ветки ошибок (`666` text-plain 200, `500` CONFLICT, IllegalArgument 400, прочее 500) — без изменений.

- [ ] **Step 1: Найти success-ветку и понять текущий контракт**

В `handleCreateChat`, во внешнем `.flatMap(jsonObj -> { String status = jsonObj.get("status").getAsString(); ... })` ветка `else` сейчас:

```java
} else {
    return ServerResponse.status(HttpStatus.SEE_OTHER)
            .location(URI.create("/reactive/chatlist"))
            .build();
}
```
`jsonObj` содержит поле `id` (chatId, используется в `Upload_image(file, jsonObj.get("id").getAsString(), "chatimage")`) и `title` доступен как `chatTitle` из внешнего scope — но в этой лямбде его нет; берём title из `jsonObj`, если есть, иначе из ответа. Проверить, есть ли в `jsonObj` поле `title`; если нет — вернуть только `chatId` (клиент всё равно навигирует в chatlist).

- [ ] **Step 2: Заменить success-ветку на JSON**

```java
} else {
    long chatId = jsonObj.get("id").getAsLong();
    Map<String, Object> body = new HashMap<>();
    body.put("chatId", chatId);
    if (jsonObj.has("title")) body.put("title", jsonObj.get("title").getAsString());
    return ServerResponse.ok()
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(body);
}
```
(Убедиться, что `java.util.HashMap`/`Map` импортированы — они уже используются в файле.)

- [ ] **Step 3: Написать падающий тест**

`CreateChatJsonResponseTest.java` — по образцу существующего `RegisterHandleErrorTest` (прогон через реальный хендлер + `MockServerWebExchange` + `writeTo`, проверка статуса и тела). Мокнуть зависимости `WEBFLUX_Service` так, чтобы `reactiveGrpcClient.reactiveChatServe(...)` вернул JSON с `status != 666/500` и `id`. Ассертить: статус `200`, `Content-Type` `application/json`, тело содержит `"chatId"`. Точную структуру моков взять из `RegisterHandleErrorTest` (тот же паттерн создания сервиса и подмены полей).

Примечание для реализатора: если изолировать `handleCreateChat` в тесте слишком дорого из-за multipart/`ReactiveSecurityContextHolder`, допустимо вынести формирование success-ответа в маленький package-private метод `Mono<ServerResponse> createChatSuccess(JsonObject jsonObj)` и юнит-тестировать его напрямую (вход — `JsonObject` с `id`/`title`, выход — 200 JSON). Это предпочтительный вариант — меньше моков, тест точечный.

- [ ] **Step 4: Запустить тест — убедиться, что падает (до реализации, если делаешь строго TDD; иначе — что проверяет новый контракт)**

Run: `cd HTTPService && JAVA_HOME=.../ms-21.0.9/Contents/Home ./mvnw test -Dtest=CreateChatJsonResponseTest`
Expected: если тест писался до Step 2 — FAIL (был 303); после Step 2 — PASS.

- [ ] **Step 5: Полная сборка**

Run: `cd HTTPService && JAVA_HOME=.../ms-21.0.9/Contents/Home ./mvnw -q test`
Expected: BUILD SUCCESS.

- [ ] **Step 6: Commit**

```bash
git add HTTPService/src/main/java/com/example/springexample/Services/WEBFLUX_Service.java \
        HTTPService/src/test/java/com/example/springexample/Services/CreateChatJsonResponseTest.java
git commit -m "feat(spa): handleCreateChat возвращает 200 JSON {chatId,title} вместо 303-redirect"
```

---

## Task 3: Клиентский каркас — роутер, STOMP-lifecycle, точка входа

**Files:**
- Create: `HTTPService/src/main/resources/static/stomp-lifecycle.js`
- Create: `HTTPService/src/main/resources/static/router.js`
- Create: `HTTPService/src/main/resources/static/app.js`

**Interfaces:**
- Produces:
  - `stomp-lifecycle.js`: `export function createStompRegistry()` → `{ add(client), disconnectAll() }`.
  - `router.js`: `export function registerRoute(pathname, loader)`, `export function navigate(path)`, `export function start()`. `loader` — `() => Promise<{mount(params), unmount()}>`.
  - `app.js` — регистрирует 5 роутов и вызывает `start()`.
- Consumes: `getAccessToken` из `/inmemory.js`.

- [ ] **Step 1: Создать `static/stomp-lifecycle.js`**

```js
// Трекинг STOMP-клиентов вью и их массовый disconnect при уходе с экрана.
// Без него SPA-навигация копит SockJS-соединения и дублирует подписки.
export function createStompRegistry() {
    const clients = [];
    return {
        add(client) { clients.push(client); return client; },
        disconnectAll() {
            for (const c of clients) {
                try {
                    if (c && typeof c.disconnect === "function") c.disconnect(() => {});
                    else if (c && c.ws && typeof c.ws.close === "function") c.ws.close();
                } catch (e) {
                    console.error("[stomp-lifecycle] disconnect failed", e);
                }
            }
            clients.length = 0;
        }
    };
}
```

- [ ] **Step 2: Создать `static/router.js`**

```js
// Клиентский роутер SPA на History API.
// mount(params) рисует экран в #app; unmount() гасит предыдущий (disconnect STOMP + очистка).
import { getAccessToken } from "/inmemory.js";

const routes = [];      // { pathname, loader }
let currentView = null;
let currentPath = null;

export function registerRoute(pathname, loader) {
    routes.push({ pathname, loader });
}

function findRoute(pathname) {
    return routes.find(r => r.pathname === pathname) || null;
}

async function renderRoute(path) {
    const url = new URL(path, location.origin);
    let route = findRoute(url.pathname);
    if (!route) {
        // неизвестный путь: если токена нет — на welcome, иначе на chatlist
        const fallback = getAccessToken() ? "/reactive/chatlist" : "/welcome";
        route = findRoute(fallback);
        history.replaceState({}, "", fallback);
    }
    // teardown предыдущей вью
    if (currentView && typeof currentView.unmount === "function") {
        try { currentView.unmount(); } catch (e) { console.error("[router] unmount failed", e); }
    }
    currentView = null;
    const app = document.getElementById("app");
    if (app) app.replaceChildren();

    const mod = await route.loader();
    currentView = mod;
    currentPath = location.pathname + location.search;
    const params = Object.fromEntries(new URL(location.href).searchParams.entries());
    await mod.mount(params);
}

export function navigate(path) {
    if (path === currentPath) return Promise.resolve();
    history.pushState({}, "", path);
    return renderRoute(path);
}

window.addEventListener("popstate", () => renderRoute(location.pathname + location.search));

export function start() {
    return renderRoute(location.pathname + location.search);
}
```

- [ ] **Step 3: Создать `static/app.js`**

```js
// Точка входа app-shell. Регистрирует роуты и стартует роутер.
import "/trusted_policy.js";          // побочный эффект: глобальная Trusted Types policy
import { registerRoute, start } from "/router.js";

registerRoute("/welcome",             () => import("/views/welcome.view.js"));
registerRoute("/registerpage",        () => import("/views/register.view.js"));
registerRoute("/reactive/chatlist",   () => import("/views/chatlist.view.js"));
registerRoute("/reactive/chat",       () => import("/views/chat.view.js"));
registerRoute("/reactive/createchat", () => import("/views/createchat.view.js"));

start();
```

Примечание CSP: динамический `import()` из nonce'd `app.js` разрешён `strict-dynamic` (скрипт, загруженный доверенным, доверен). `trusted_policy.js` грузится как модуль-side-effect; если он не ES-модуль — импортировать его не через `import`, а оставить `<script src="/trusted_policy.js">` в shell (он там уже есть) и убрать строку `import "/trusted_policy.js"` из `app.js`. Проверить содержимое `trusted_policy.js` при реализации и выбрать один способ (в shell он уже подключён тегом — вероятно строку импорта в app.js убрать).

- [ ] **Step 4: Компиляция (ресурсы копируются) + быстрый smoke загрузки shell**

Run: `cd HTTPService && JAVA_HOME=.../ms-21.0.9/Contents/Home ./mvnw -q compile`
Expected: BUILD SUCCESS. (Вью ещё нет — роутер зафейлит `import()` в рантайме на конкретном маршруте; это нормально, вью добавляются в задачах 4–7. На этом шаге проверяем только, что файлы на месте и проект собирается.)

- [ ] **Step 5: Commit**

```bash
git add HTTPService/src/main/resources/static/stomp-lifecycle.js \
        HTTPService/src/main/resources/static/router.js \
        HTTPService/src/main/resources/static/app.js
git commit -m "feat(spa): каркас клиента — router (History API), stomp-lifecycle, app entrypoint"
```

---

## Task 4: Вью welcome + register

**Files:**
- Create: `HTTPService/src/main/resources/static/views/welcome.view.js` (из `static/welcome.js`)
- Create: `HTTPService/src/main/resources/static/views/register.view.js` (из `static/register.js`)

**Interfaces:**
- Consumes: `navigate` из `/router.js`; `api` из `/axios.js`; `getFingerprintData` из `/meta_catcher.js`; `setAccessToken` из `/inmemory.js`.
- Produces: `export function mount(params)`, `export function unmount()` в каждом файле.

- [ ] **Step 1: `welcome.view.js` — обернуть логику welcome в mount/unmount**

Скопировать логику из `static/welcome.js`. Отличия:
1. Разметку формы welcome (сейчас в `welcome.html`) вью **рисует сама** в `#app` через `policy.createHTML(...)` в начале `mount()` (перенести HTML из `welcome.html`: контейнер, `#google-login-btn`, `#register-link`, `#regular-login-form`, `#response-div`). `params.error` (из query `?error=`) — показать в `#response-div`, если есть.
2. Вместо `document.addEventListener('DOMContentLoaded', ...)` — тело идёт в `mount(params)`.
3. Замены навигации:
   - ссылка регистрации: `window.location.href="/registerpage"` → `navigate("/registerpage")`;
   - успешный логин: `window.location.href = response.data.redirectUri` → `navigate(response.data.redirectUri)` (после `setAccessToken`);
   - **кнопка Google оставить как есть**: `window.location.href = data.redirectUrl` (уход на accounts.google.com — реальная навигация).
4. `unmount()`: снять обработчики (хранить ссылки на добавленные listeners или использовать `AbortController` — создать `const ac = new AbortController()` и передавать `{ signal: ac.signal }` во все `addEventListener`, а в `unmount()` вызвать `ac.abort()`), очистить `hideTimeout` (`clearTimeout`).

Скелет:

```js
import { navigate } from "/router.js";
import api from "/axios.js";
import { getFingerprintData } from "/meta_catcher.js";
import { setAccessToken } from "/inmemory.js";

const policy = window.__ttPolicy || window.trustedTypes?.defaultPolicy; // как в trusted_policy.js
let ac = null;
let hideTimeout = null;

export async function mount(params) {
    ac = new AbortController();
    const app = document.getElementById("app");
    app.innerHTML = policy ? policy.createHTML(WELCOME_HTML) : WELCOME_HTML;

    if (params.error) showError(decodeURIComponent(params.error));

    const meta = await getFingerprintData();
    const registerLink = document.getElementById("register-link");
    const googleLoginBtn = document.getElementById("google-login-btn");
    const regularLoginForm = document.getElementById("regular-login-form");

    registerLink?.addEventListener("click", (e) => { e.preventDefault(); navigate("/registerpage"); }, { signal: ac.signal });

    googleLoginBtn?.addEventListener("click", async (e) => {
        e.preventDefault();
        const response = await api.post("/startauth",
            new URLSearchParams({ FpComponents: JSON.stringify(meta.components) }),
            { headers: googleHeaders(meta) });
        if (response.data.redirectUrl) window.location.href = response.data.redirectUrl; // уход на Google — reload by design
    }, { signal: ac.signal });

    regularLoginForm?.addEventListener("submit", async (e) => {
        e.preventDefault();
        try {
            const p = new URLSearchParams(new FormData(regularLoginForm));
            p.set("FpComponents", JSON.stringify(meta.components));
            const response = await api.post("/reactive/login", p, { headers: loginHeaders(meta) });
            if (response.data.redirectUri) {
                if (response.data.accessToken) setAccessToken(response.data.accessToken);
                navigate(response.data.redirectUri);                 // ← было window.location.href
            }
        } catch (err) { showError(err?.response?.data || err.message); }
    }, { signal: ac.signal });
}

export function unmount() {
    if (ac) ac.abort();
    if (hideTimeout) clearTimeout(hideTimeout);
    ac = null;
}
```
(Точные `WELCOME_HTML`, `googleHeaders`, `loginHeaders`, `showError` перенести из `welcome.js`/`welcome.html`; CSRF-заголовок читается из cookie `XSRF-TOKEN` как в оригинале.)

- [ ] **Step 2: `register.view.js` — обернуть логику register в mount/unmount**

Из `static/register.js`. Разметку формы регистрации перенести из `register.html` и рисовать в `#app` в `mount()`. Убрать зависимость от серверного `<input name="_csrf">` (в клиентской форме его нет; CSRF-заголовок берём из cookie `XSRF-TOKEN`, как в оригинале строки 54). Замены:
- успешная регистрация: `window.location.href = response.data.redirectUri` → `navigate(response.data.redirectUri)` (после `setAccessToken`);
- ошибка: показать `error.response?.data` (это уже исправлено в текущем `register.js`).
`unmount()`: `AbortController.abort()` + очистка таймеров.

- [ ] **Step 3: Компиляция**

Run: `cd HTTPService && JAVA_HOME=.../ms-21.0.9/Contents/Home ./mvnw -q compile`
Expected: BUILD SUCCESS.

- [ ] **Step 4: Commit**

```bash
git add HTTPService/src/main/resources/static/views/welcome.view.js \
        HTTPService/src/main/resources/static/views/register.view.js
git commit -m "feat(spa): вью welcome и register в shell (навигация через router, без reload)"
```

---

## Task 5: Вью chatlist

**Files:**
- Create: `HTTPService/src/main/resources/static/views/chatlist.view.js` (из `static/chatlist.js`)

**Interfaces:**
- Consumes: `navigate` из `/router.js`; `createStompRegistry` из `/stomp-lifecycle.js`; `api` из `/axios.js`; `ensureAccessToken` из `/auth.js`.
- Produces: `export function mount(params)`, `export function unmount()`.

- [ ] **Step 1: Перенести `chatlist.js` в `chatlist.view.js` с контрактом mount/unmount**

1. Верхнеуровневый IIFE-бутстрап (`(async () => { ... })()`) обернуть в `export async function mount(params) { ... }`.
2. Модульные переменные (`user_id`, `originalPreviews`, `typingUsers` и др.) — оставить модульными, но **сбрасывать в начале `mount()`** (иначе при повторном входе останется старое состояние).
3. Создать реестр STOMP: `const stomp = createStompRegistry();` и каждый `Stomp.over(new SockJS(...))` регистрировать: `const generalStomp = stomp.add(Stomp.over(new SockJS('/GeneralChatDataUpdateConn')));` — и так все 4 (general/list/changes/images/status).
4. Навигация: клик по чату `chatPart.onclick = () => window.location.href = \`/reactive/chat?id=...\`` → `chatPart.onclick = () => navigate(\`/reactive/chat?id=${chat_id}&title=${encodeURIComponent(chat_title || '')}\`);`
5. `export function unmount() { stomp.disconnectAll(); /* clearTimeouts, снять DOM-listeners через AbortController если есть */ }`.
6. Добавить импорты: `import { navigate } from "/router.js"; import { createStompRegistry } from "/stomp-lifecycle.js";` (плюс существующие `api`, `ensureAccessToken`).

- [ ] **Step 2: Компиляция**

Run: `cd HTTPService && JAVA_HOME=.../ms-21.0.9/Contents/Home ./mvnw -q compile`
Expected: BUILD SUCCESS.

- [ ] **Step 3: Commit**

```bash
git add HTTPService/src/main/resources/static/views/chatlist.view.js
git commit -m "feat(spa): вью chatlist (mount/unmount, STOMP teardown, navigate в чат)"
```

---

## Task 6: Вью chat

**Files:**
- Create: `HTTPService/src/main/resources/static/views/chat.view.js` (из `static/index.js`)

**Interfaces:**
- Consumes: `navigate` из `/router.js`; `createStompRegistry` из `/stomp-lifecycle.js`; `api`, `ensureAccessToken`.
- Produces: `export function mount(params)` — `params.id`, `params.title` **приходят от роутера**; `export function unmount()`.

- [ ] **Step 1: Перенести `index.js` в `chat.view.js` с контрактом mount/unmount**

1. **Ключевое:** сейчас `index.js` читает `const params = new URLSearchParams(window.location.search); const chat_id = params.get('id')` на верхнем уровне модуля. В вью — `chat_id`/`chat_title` берутся из аргумента `mount(params)`: `export async function mount(params) { const chat_id = params.id; const chat_title = params.title || ''; ... }`. Все использования `chat_id`/`chat_title` — внутри `mount` (или через модульные переменные, выставляемые в начале `mount`).
2. Разметку чата (`chat-container`, `#chatMessages`, `#messageInput`, `#sendBtn`, `#aiBtn`, `#aiSuggestionPanel`, `#aiUserSelect` — из `index.html`) вью рисует в `#app` в начале `mount()` через `policy.createHTML(...)`.
3. STOMP: `const stomp = createStompRegistry();`, регистрировать все 3 клиента (`stompClient` для `/ChatMessagesConn`, statusStomp для `/StatusUserConn`, imagesStomp для `/MutualImagesConn`).
4. Навигация «назад к списку»: `window.location.href = '/reactive/chatlist'` → `navigate('/reactive/chatlist')`.
5. `unmount()`: `stomp.disconnectAll()`, `clearTimeout(typingTimeout)`, снять DOM-listeners (AbortController).
6. Импорты: `navigate`, `createStompRegistry` + существующие.

- [ ] **Step 2: Компиляция**

Run: `cd HTTPService && JAVA_HOME=.../ms-21.0.9/Contents/Home ./mvnw -q compile`
Expected: BUILD SUCCESS.

- [ ] **Step 3: Commit**

```bash
git add HTTPService/src/main/resources/static/views/chat.view.js
git commit -m "feat(spa): вью chat (params от роутера, STOMP teardown, navigate в chatlist)"
```

---

## Task 7: Вью createchat

**Files:**
- Create: `HTTPService/src/main/resources/static/views/createchat.view.js` (из `static/chatcreate.js`)

**Interfaces:**
- Consumes: `navigate` из `/router.js`; `api` из `/axios.js`.
- Produces: `export function mount(params)`, `export function unmount()`.

- [ ] **Step 1: Перенести `chatcreate.js` в `createchat.view.js`**

1. Разметку формы (из `chatcreatepage.html`: `#chatForm`, поля title/file/userFields, `#addUserBtn`, `#result`) вью рисует в `#app` в `mount()`.
2. Сабмит: сейчас после POST `/createchat` код смотрит `response.request.responseURL`/`window.location.href = "/reactive/chatlist"`. Новый бэкенд отдаёт `200` JSON `{chatId,title}`. Поэтому: на успехе (2xx) → `navigate('/reactive/chatlist')`. Убрать ветки с `responseURL`. Ошибки (text-plain) — показать в `#result` как сейчас (`error?.response?.data`).
3. `unmount()`: снять listeners (AbortController); STOMP тут нет.
4. Импорты: `navigate`, `api`.

- [ ] **Step 2: Компиляция**

Run: `cd HTTPService && JAVA_HOME=.../ms-21.0.9/Contents/Home ./mvnw -q compile`
Expected: BUILD SUCCESS.

- [ ] **Step 3: Commit**

```bash
git add HTTPService/src/main/resources/static/views/createchat.view.js
git commit -m "feat(spa): вью createchat (navigate по 200 JSON, без reload)"
```

---

## Task 8: Чистка старых файлов + live-верификация

**Files:**
- Delete: `templates/{chats_list,index,chatcreatepage,welcome,register}.html`
- Delete: `static/{chatlist,index,chatcreate,welcome,register}.js`

**Interfaces:** нет новых — финальная интеграция и проверка.

- [ ] **Step 1: Грепом убедиться, что на удаляемые файлы нет ссылок**

Run:
```bash
cd HTTPService/src/main/resources
grep -rn -E "chats_list|chatcreatepage|/chatlist\.js|/index\.js|/chatcreate\.js|/welcome\.js|/register\.js" templates static | grep -vE "views/|app\.html|app\.js"
```
Expected: пусто (все ссылки ведут на новые `views/*` и `app.js`; серверные `return "welcome"/"register"/...` уже заменены в Task 1). Если что-то осталось — поправить перед удалением.

- [ ] **Step 2: Удалить старые шаблоны и скрипты**

```bash
cd HTTPService/src/main/resources
git rm templates/chats_list.html templates/index.html templates/chatcreatepage.html templates/welcome.html templates/register.html
git rm static/chatlist.js static/index.js static/chatcreate.js static/welcome.js static/register.js
```

- [ ] **Step 3: Полная сборка**

Run: `cd HTTPService && JAVA_HOME=.../ms-21.0.9/Contents/Home ./mvnw -q test`
Expected: BUILD SUCCESS (все тесты зелёные).

- [ ] **Step 4: Деплой в kind**

Run: `/kdeploy` (прогоняет `deploy-kind.sh` + проверяет поды). Дождаться Ready.

- [ ] **Step 5: Live-верификация через Chrome MCP (чек-лист §7 спеки)**

Пройти в браузере и зафиксировать (Network — отсутствие document-перезагрузок там, где их не должно быть):
1. `/welcome` → shell загружен, форма логина видна.
2. Ссылка «регистрация» → URL `/registerpage`, **без reload**, форма регистрации.
3. Успешная регистрация → `/reactive/chatlist`, **без reload**, **нет запроса `/exchangeTokens`**, повторного логина не просят (регресс-тест `q1b`).
4. Клик по чату → `/reactive/chat?id=…`, без reload, сообщения грузятся, STOMP подключён.
5. Назад (браузер) → chatlist без reload; проверить, что число открытых SockJS-соединений не растёт (старый STOMP чата отключён).
6. Создать чат → `/reactive/createchat` → сабмит → chatlist без reload, чат появился.
7. F5 на `/reactive/chat?id=…` → shell + silent-refresh, чат отрисован, сессия жива.
8. Кнопка Google → уход на accounts.google.com (реальная навигация — ожидаемо).

- [ ] **Step 6: (опц.) GIF прохода для ревью** через `gif_creator`.

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "chore(spa): удалить старые MPA-шаблоны/скрипты после миграции в shell; live-verified"
```

---

## Self-Review (заполнить при написании — уже пройдено автором плана)

- **Покрытие спеки:** §3 архитектура → Tasks 1,3–7; §4 сервер → Tasks 1–2; §5 CSP/CSRF → Tasks 1,4; §6 токены → Tasks 4 (navigate вместо reload); §7 верификация → Task 8; §2 инвентарь (удаление старого) → Task 8. Все shell-роуты (welcome/register/chatlist/chat/createchat) покрыты. `callback`/`fingerprint`/Google — намеренно не тронуты (вне объёма).
- **Плейсхолдеры:** новые файлы (app.html, router.js, stomp-lifecycle.js, app.js, handleCreateChat) даны кодом целиком; вью — точные рецепты трансформации существующих файлов с конкретными заменами строк (файлы есть в репозитории, дублировать сотни строк рендер-логики нецелесообразно и ошибкоопасно).
- **Согласованность типов:** контракт вью `mount(params)/unmount()` единый во всех вью; `createStompRegistry()→{add,disconnectAll}` и `navigate/registerRoute/start` из router согласованы между Task 3 и потребителями 4–7.
