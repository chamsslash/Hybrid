# Дизайн: SPA-миграция authed-зоны (chatlist ↔ chat ↔ createchat)

Дата: 2026-07-26
Ветка-источник: `dev`
Beads: `j35` (SPIKE/ADR) — данная спека закрывает решение спайка в пользу Варианта 1;
попутно закрывает `q1b` (потеря сессии на переходах).

## 1. Контекст и объём

Проект — **частичный SPA**: `chats_list.html`+`chatlist.js` и `index.html`+`index.js` уже
работают как SPA-шеллы (данные через `/api/*`, рендер в JS, реалтайм STOMP/SockJS), но
переходы между экранами делают полную перезагрузку (`window.location.href`), из-за чего
теряется in-memory access-токен (баг `q1b`).

**Выбран Вариант 1 (решение пользователя):** довести до SPA **authed-зону** — экраны
`chatlist`, `chat`, `createchat` объединяются в один app-shell с клиентским роутером;
переходы между ними — без перезагрузки. **Auth-флоу** (`welcome`, `registerpage`,
`authcallback`, `collect-fingerprint`) остаётся серверным: Google-OAuth физически требует
full-page redirect на `accounts.google.com` и обратно, поэтому полный SPA там даёт мало
выгоды при высоком риске (CSRF/nonce/безопасность входа).

**Вне объёма (явно):** миграция форм входа/регистрации в клиентский рендер; Google-OAuth
redirect; ввод фреймворка (React/Vue) и бандлера. Это возможный отдельный второй этап.

## 2. Полная верифицированная инвентаризация (сервер + клиент)

### 2.1 Серверные эндпоинты, отдающие HTML

| Route | Хендлер (стек) | Шаблон | Скрипты шаблона | Класс | В объёме? |
|---|---|---|---|---|---|
| `GET /reactive/chatlist` | `WEBFLUX_Service.getChatList` (WebFlux) | `chats_list` | chatlist.js, trusted_policy.js, CDN | SPA-шелл | ✅ мигрируем в общий shell |
| `GET /reactive/chat` | `WEBFLUX_Service.renderChatPage` (WebFlux) | `index` | index.js, trusted_policy.js, CDN | SPA-шелл | ✅ мигрируем в общий shell |
| `GET /createchatpage` | `MVC_Service.GetCreateChat` (Servlet-MVC) | `chatcreatepage` | chatcreate.js, axios CDN | MPA-форма | ✅ переносим в `/reactive/createchat` |
| `GET /welcome` | `MVC_Service.GetWelcome` (MVC) | `welcome` | welcome.js, axios.js, CDN | MPA | ❌ вне объёма (auth-флоу) |
| `GET /registerpage` | `MVC_Service.GetRegisterPage` (MVC) | `register` | register.js, axios.js, CDN | MPA | ❌ вне объёма |
| `GET /authcallback` | `MVC_Service.authcallbackpage` (MVC) | `callback` | callback.js, axios.js, CDN | MPA (OAuth) | ❌ вне объёма |
| `GET /collect-fingerprint` | `MVC_Service.fpCollector` (MVC) | `fingerpring_collector` | meta_catcher.js, inmemory.js, axios.js, CDN | MPA | ❌ вне объёма |

7 шаблонов ↔ 7 вью-возвратов — сверено сплошным грепом (`return "<view>"`, `ParseWithThymeLeaf`,
`@GetMapping`, `route(GET…)`). Иных HTML-отдающих эндпоинтов нет.

### 2.2 JSON-эндпоинты (данные/действия — HTML не отдают, переиспользуются как есть)

| Route | Назначение | Примечание для SPA |
|---|---|---|
| `GET /api/me`, `/api/chatlist`, `/api/chat`, `/api/images/{*key}` | Данные SPA (ApiController) | без изменений |
| `POST /reactive/login`, `/reactive/register` | Логин/регистрация → JSON `{redirectUri, accessToken}` | без изменений (auth-флоу) |
| `POST /reactive/createchat` | Создание чата (multipart) | **меняем ответ на успех: 303→200 JSON** (см. §4) |
| `POST /reactive/AiAssist` | AI-подсказка в чате | без изменений (вызов внутри chat-вью) |
| `POST /exchangeTokens`, `/verifylogin` | Ротация токена / OAuth verify | без изменений (auth-флоу) |
| `POST /startauth` | Старт Google OAuth | вне HTTPService (ingress/AuthService) |

### 2.3 Клиентские full-reload переходы (`window.location.href`)

| Файл:строка | Куда | В объёме? | Действие |
|---|---|---|---|
| `chatlist.js:35` | `/reactive/chat?id=&title=` | ✅ authed | → `router.navigate(...)` |
| `index.js:254` | `/reactive/chatlist` | ✅ authed | → `router.navigate(...)` |
| `chatcreate.js:36` | `/reactive/chatlist` | ✅ authed | → `router.navigate(...)` |
| `chatcreate.js:33` | `response.request.responseURL` | ✅ authed | убрать (переход через navigate по JSON-ответу) |
| `welcome.js:19,46,85` | `/registerpage`, Google, `redirectUri` | ❌ auth-флоу | оставить как есть |
| `register.js:63` | `redirectUri` (`/reactive/chatlist`) | ❌ граница | оставить full-reload (вход в SPA из auth-флоу) |
| `callback.js:22,60` | `/welcome`, `redirectUri` | ❌ auth-флоу | оставить |
| `axios.js:61` | `/welcome` при провале refresh | ❌ граница | оставить (выход из SPA) |

Граница SPA: `register.js`/`callback.js` при успешном входе делают full-reload на
`/reactive/chatlist` — это **вход** в SPA-shell из серверного auth-флоу, перезагрузка здесь
корректна (грузим shell один раз). Дальше внутри shell — только `router.navigate`.

### 2.4 Security-границы (важно для серверной части)

- `ReactiveSecurityConfig` (WebFlux, обслуживает `/reactive/*`-сервлет):
  `pathMatchers(GET, "/chatlist", "/chat").permitAll()` (шеллы публичны), `/register`/`/login`
  permitAll, `anyExchange().authenticated()`.
- `MvcSecurityConfig` (Servlet): `/welcome`, `/reactive/**` permitAll; `/api/**` authenticated
  (данные требуют Bearer); CSRF игнорируется для `/api/**`.

## 3. Целевая архитектура (клиент)

- **Единый app-shell** `templates/app.html` — один документ: корень `<div id="app"></div>`,
  общие контейнеры (`#toastContainer`, `#global-spinner`), CDN-скрипты (axios/sockjs/stomp/
  dompurify), `trusted_policy.js`, nonce/CSP, точка входа `<script type="module" src="/app.js">`.
  Заменяет `chats_list.html` + `index.html` + `chatcreatepage.html` в authed-зоне.
- **Роутер** `static/router.js` (History API):
  - `navigate(path)` → `history.pushState` + `renderRoute(path)` (без перезагрузки);
  - `renderRoute(path)` → `currentView?.unmount()` → очистка `#app` → `view.mount(params)`;
  - слушатель `popstate` (назад/вперёд) → `renderRoute(location)`;
  - карта маршрутов: `/reactive/chatlist` → chatlist-view, `/reactive/chat` → chat-view
    (params `id`,`title` из query), `/reactive/createchat` → createchat-view;
  - неизвестный путь внутри shell → fallback на chatlist.
- **Вью-модули** (рефактор существующих; контракт `mount(params)` / `unmount()`):
  - `static/views/chatlist.view.js` ← из `chatlist.js`;
  - `static/views/chat.view.js` ← из `index.js` (params приходят **от роутера**, не из
    `window.location` на верхнем уровне модуля);
  - `static/views/createchat.view.js` ← из `chatcreate.js`.
- **`static/app.js`** — точка входа shell: единичная инициализация (Trusted Types policy,
  axios), регистрация роутера, первый `renderRoute(location)`.
- **Общий STOMP-lifecycle-хелпер** `static/stomp-lifecycle.js` — регистрирует созданные
  STOMP-клиенты вью и разом их дисконнектит в `unmount()`.

### 3.1 Контракт вью (обязателен)

Каждая вью экспортирует:
```js
export function mount(params) { /* fetch /api → render в #app → connect STOMP → wire listeners */ }
export function unmount()      { /* disconnect ВСЕХ STOMP-клиентов, clearTimeout, снять listeners, очистить #app */ }
```
`unmount()` **обязателен** — без него при переходах копятся STOMP-соединения и дублируются
подписки (сейчас teardown отсутствует нигде: `index.js`/`chatlist.js` открывают SockJS и не
закрывают — в MPA это маскировалось перезагрузкой).

## 4. Серверные изменения (детально, по файлам)

1. **`WEBFLUX_Service.getChatList` / `renderChatPage`** — рендерить общий shell:
   `ParseWithThymeLeaf(model, "app", templateEngine)` вместо `"chats_list"`/`"index"`.
   `ParseWithThymeLeaf` сам кладёт `nonce` в модель (это уже так). **Замечание по CSS-пути:**
   `chats_list.html` резолвит CSS через `${pathPrefix + '/css/style2.css'}`, а `index.html` —
   через `@{/css/style2.css}` (расходятся). В `app.html` выбрать **один** путь, который реально
   резолвится под `/reactive`-сервлетом (проверить при реализации), и не завязываться на
   `pathPrefix`. Данные не встраиваются — их тянет JS через `/api/*`.
2. **`WebFluxConfig`** — добавить бин-роут `GET /createchat` → рендер того же shell
   (метод `renderCreateChatPage`, аналог `renderChatPage`), и включить его в `combinedRoutes`.
   Итог: `/reactive/chatlist`, `/reactive/chat`, `/reactive/createchat` отдают один `app.html`.
3. **`ReactiveSecurityConfig:51`** — добавить `"/createchat"` в
   `pathMatchers(GET, "/chatlist", "/chat", "/createchat").permitAll()` (shell публичен).
4. **`WEBFLUX_Service.handleCreateChat`** — на успехе вернуть **`200` JSON `{chatId, title}`**
   вместо `303 → /reactive/chatlist`, чтобы клиент сделал `router.navigate('/reactive/chatlist')`
   без перезагрузки. Ветки ошибок (`666`/`500`/text-plain) не трогаем.
5. **`MVC_Service.GetCreateChat` (`/createchatpage`)** — задепрекейтить: заменить на
   серверный redirect `302 → /reactive/createchat` (обратная совместимость для старых ссылок),
   тело-хендлер и шаблон `chatcreatepage.html` удаляются после миграции.
6. **Шаблоны:** добавить `templates/app.html`; удалить `chats_list.html`, `index.html`,
   `chatcreatepage.html` после переноса их разметки в shell/вью (их `<div>`-контейнеры и
   стили переезжают в `app.html`, а `id`-контейнеры конкретных экранов вью создают в `#app`
   при `mount`).

## 5. CSP / nonce

Shell отдаётся с одним per-request `nonce`. **Точность механизма:** WebFlux-путь
(`ParseWithThymeLeaf`) кладёт `nonce` в модель, но, в отличие от MVC-`generateandputNonce`,
**не выставляет заголовок `Content-Security-Policy`**. Так работают уже текущие `chatlist`/`chat`
шеллы — сохраняем это поведение как есть (не добавляем и не убираем CSP-заголовок в WebFlux-рендере;
это вне объёма). Nonce на `<script>`-тегах shell продолжает проставляться через `${nonce}`.

`script-src 'nonce-…' 'strict-dynamic'` (там, где CSP применяется ingress/прокси) означает:
скрипт с валидным nonce может динамически подгружать другие скрипты — `app.js` (с nonce) вправе
делать `import()` вью-модулей без отдельного nonce на каждом. Роутер использует статические
`import` вверху `app.js` (проще и надёжнее под Trusted Types), ленивый `import()` — опционально.
Trusted Types policy (`trusted_policy.js`) переиспользуется как есть; весь `innerHTML` во вью
уже идёт через `policy.createHTML(...)`.

## 6. q1b — закрывается побочно

In-memory access-токен (`inmemory.js`) переживает `router.navigate` (нет перезагрузки),
поэтому после `chatlist → chat → createchat` повторный логин не требуется. Отдельного кода под
q1b не нужно; в acceptance добавляется его проверка.

## 7. Тестирование и верификация

- **Бэкенд:** юнит на `handleCreateChat` (успех → `200` c JSON `{chatId,title}`, а не `303`);
  проверка, что `/reactive/createchat` отдаёт shell и открыт `permitAll`. Сборка строго JDK21.
- **Фронтенд (live, Chrome MCP после `/kdeploy`):**
  1. Вход → попадаем в `/reactive/chatlist` (shell загружен один раз).
  2. Клик по чату → URL меняется на `/reactive/chat?id=…`, **документ не перезагружается**
     (в Network нет нового document-запроса), сообщения грузятся, STOMP подключён.
  3. Назад (кнопка браузера) → возвращаемся в chatlist без перезагрузки, **старый STOMP чата
     отключён** (нет дублей подписок — проверяем по числу SockJS-соединений).
  4. Создать чат → `/reactive/createchat` → сабмит → `router.navigate` в chatlist, новый чат
     виден, без перезагрузки.
  5. Deep-link/refresh (F5) на `/reactive/chat?id=…` → сервер отдаёт shell, JS рисует чат,
     токен восстановлен silent-refresh’ом (существующий `ensureAccessToken`).
  6. q1b: регистрация → chatlist без повторного логина.
- **GIF** прохода для ревью (по желанию).

## 8. Риски и меры

| Риск | Мера |
|---|---|
| Утечки/дубли STOMP при навигации | Обязательный `unmount()` с disconnect; тест п.7.3 на число соединений |
| Deep-link/refresh не работает | Сервер отдаёт shell на всех трёх authed-роутах; тест п.7.5 |
| Back/forward ломает состояние | Обработчик `popstate` + идемпотентный `renderRoute` |
| nonce/Trusted Types на модулях | Статические `import` в `app.js` (один nonce), `policy.createHTML` переиспользуется |
| Регрессия загрузки картинок/аватаров | Пути `/api/images/{key}` не меняются; smoke в chat/chatlist-вью |
| Смешение MVC/WebFlux security | `/createchat` GET добавлен в оба нужных места; проверка permitAll |

## 9. Предварительная декомпозиция (для writing-plans)

1. **Бэкенд-шелл + роуты:** `app.html`, рендер shell в `getChatList`/`renderChatPage`, новый
   `GET /reactive/createchat`, `ReactiveSecurityConfig` permitAll, `/createchatpage`→redirect.
   (изолированно, без клиента).
2. **`handleCreateChat` → JSON** + юнит-тест (изолированно, бэкенд).
3. **Каркас клиента:** `app.js` + `router.js` + `stomp-lifecycle.js` + контракт вью (заглушки
   вью, чтобы роутер зарабатывал).
4. **chatlist-вью:** рефактор `chatlist.js` → `views/chatlist.view.js` (mount/unmount, navigate).
5. **chat-вью:** рефактор `index.js` → `views/chat.view.js` (params от роутера, STOMP teardown).
6. **createchat-вью:** рефактор `chatcreate.js` → `views/createchat.view.js` (navigate по JSON).
7. **Чистка:** удалить старые шаблоны/скрипты, обновить ссылки, live-верификация + q1b.

Задачи 1–2 — бэкенд, параллелятся. 3 блокирует 4–6. 4–6 после каркаса, трогают разные файлы.
7 — финал. Точную последовательность и review-петли определит план (subagent-driven).
