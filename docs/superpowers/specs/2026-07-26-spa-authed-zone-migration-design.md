# Дизайн: SPA-миграция (Вариант 2 — authed-зона + формы входа/регистрации)

Дата: 2026-07-26
Ветка-источник: `dev`
Beads: `j35` (SPIKE/ADR) — данная спека закрывает решение спайка в пользу **Варианта 2**;
попутно **полностью** закрывает `q1b` (потеря сессии на переходах, включая границу входа).

## 1. Контекст и объём

Проект — **частичный SPA**: `chats_list.html`+`chatlist.js` и `index.html`+`index.js` уже
работают как SPA-шеллы (данные через `/api/*`, рендер в JS, реалтайм STOMP/SockJS), но
переходы между экранами делают полную перезагрузку (`window.location.href`), из-за чего
теряется in-memory access-токен (баг `q1b`).

**Выбран Вариант 2 (решение пользователя):** в один app-shell с клиентским роутером входят:
- **authed-зона:** `chatlist`, `chat`, `createchat`;
- **формы входа:** `welcome` (логин + старт Google + ссылка на регистрацию), `registerpage`.

Тогда переход «успешный логин/регистрация → chatlist» становится бесшовным `router.navigate`
**без перезагрузки** — только что выданный access-токен не стирается, и последняя точка потери
сессии (граница входа) устраняется.

**Что удешевляет Вариант 2:** CSRF здесь **cookie-based** (`CookieCsrfTokenRepository`: формы
читают cookie `XSRF-TOKEN` и эхом шлют её в заголовке `X-CSRF-TOKEN`), а не серверно-встроенный
токен. Значит клиентски-отрисованные формы логина/регистрации остаются совместимы — переносить
серверную генерацию CSRF в клиент не требуется (снимается только зависимость от скрытого
`<input name="_csrf">`, который был серверным guard'ом).

**Вне объёма (явно) и почему:**
- `authcallback` — возврат от Google: браузер приходит сюда **полной перезагрузкой с чужого
  домена** (`accounts.google.com`), это неизбежный fresh-load, SPA его не отменяет. Остаётся
  серверной страницей; её `window.location.href` в chatlist — один reload на OAuth-пути (ок).
- `collect-fingerprint` — security-конвейер выдачи токенов (анти-фрод), самая чувствительная
  часть; не трогаем.
- Старт Google (`/startauth`) — намеренный уход браузера на `accounts.google.com` (реальная
  навигация, не in-SPA).
- Ввод фреймворка (React/Vue) и бандлера — отдельный крупный проект, не эта миграция.

## 2. Полная верифицированная инвентаризация (сервер + клиент)

### 2.1 Серверные эндпоинты, отдающие HTML

| Route | Хендлер (стек) | Шаблон | Скрипты шаблона | Класс | В объёме? |
|---|---|---|---|---|---|
| `GET /reactive/chatlist` | `WEBFLUX_Service.getChatList` (WebFlux) | `chats_list` | chatlist.js, trusted_policy.js, CDN | SPA-шелл | ✅ → общий shell |
| `GET /reactive/chat` | `WEBFLUX_Service.renderChatPage` (WebFlux) | `index` | index.js, trusted_policy.js, CDN | SPA-шелл | ✅ → общий shell |
| `GET /createchatpage` | `MVC_Service.GetCreateChat` (Servlet-MVC) | `chatcreatepage` | chatcreate.js, axios CDN | MPA-форма | ✅ → `/reactive/createchat` |
| `GET /welcome` | `MVC_Service.GetWelcome` (MVC) | `welcome` | welcome.js, axios.js, CDN | MPA-форма | ✅ → вью shell (Вариант 2) |
| `GET /registerpage` | `MVC_Service.GetRegisterPage` (MVC) | `register` | register.js, axios.js, CDN | MPA-форма | ✅ → вью shell (Вариант 2) |
| `GET /authcallback` | `MVC_Service.authcallbackpage` (MVC) | `callback` | callback.js, axios.js, CDN | MPA (OAuth) | ❌ вне объёма (OAuth fresh-load) |
| `GET /collect-fingerprint` | `MVC_Service.fpCollector` (MVC) | `fingerpring_collector` | meta_catcher.js, inmemory.js, axios.js, CDN | MPA | ❌ вне объёма (security) |

7 шаблонов ↔ 7 вью-возвратов — сверено сплошным грепом (`return "<view>"`, `ParseWithThymeLeaf`,
`@GetMapping`, `route(GET…)`). Иных HTML-отдающих эндпоинтов нет.

### 2.2 JSON-эндпоинты (данные/действия — HTML не отдают, переиспользуются как есть)

| Route | Назначение | Примечание для SPA |
|---|---|---|
| `GET /api/me`, `/api/chatlist`, `/api/chat`, `/api/images/{*key}` | Данные SPA (ApiController) | без изменений |
| `POST /reactive/login`, `/reactive/register` | Логин/регистрация → JSON `{redirectUri, accessToken}` + refresh-cookie | **успех: клиент делает `router.navigate` вместо reload** |
| `POST /reactive/createchat` | Создание чата (multipart) | **успех: 303→200 JSON** (см. §4) |
| `POST /reactive/AiAssist` | AI-подсказка в чате | без изменений (вызов внутри chat-вью) |
| `POST /exchangeTokens` | Ротация токена (silent-refresh) | без изменений (страховка сессии) |
| `POST /verifylogin` | OAuth verify (на callback-странице) | без изменений (вне объёма) |
| `POST /startauth` | Старт Google OAuth | вне HTTPService (ingress/AuthService) |

### 2.3 Клиентские full-reload переходы (`window.location.href`)

| Файл:строка | Куда | В объёме? | Действие |
|---|---|---|---|
| `chatlist.js:35` | `/reactive/chat?id=&title=` | ✅ authed | → `router.navigate(...)` |
| `index.js:254` | `/reactive/chatlist` | ✅ authed | → `router.navigate(...)` |
| `chatcreate.js:36` | `/reactive/chatlist` | ✅ authed | → `router.navigate(...)` |
| `chatcreate.js:33` | `response.request.responseURL` | ✅ authed | убрать (navigate по JSON-ответу) |
| `welcome.js:19` | `/registerpage` | ✅ Вариант 2 | → `router.navigate('/registerpage')` |
| `welcome.js:85` | `redirectUri` (login success) | ✅ Вариант 2 | → `router.navigate('/reactive/chatlist')` |
| `register.js:63` | `redirectUri` (register success) | ✅ Вариант 2 | → `router.navigate('/reactive/chatlist')` |
| `welcome.js:46` | `data.redirectUrl` (старт Google) | ❌ граница | оставить (уход на accounts.google.com) |
| `callback.js:22,60` | `/welcome`, `redirectUri` | ❌ OAuth | оставить (fresh-load после Google) |
| `axios.js:61` | `/welcome` при провале refresh | ❌ граница | оставить (выход из SPA при мёртвой сессии) |

Границы SPA (остаются реальными переходами): **уход на Google** (`welcome.js:46`), **возврат от
Google** (`callback.js` — fresh-load), **аварийный выход при мёртвой сессии** (`axios.js:61`).
Всё остальное внутри shell — `router.navigate`.

### 2.4 Security-границы (важно для серверной части)

- `ReactiveSecurityConfig` (WebFlux, обслуживает `/reactive/*`-сервлет):
  `pathMatchers(GET, "/chatlist", "/chat").permitAll()` (шеллы публичны), `/register`/`/login`
  POST permitAll, `anyExchange().authenticated()`.
- `MvcSecurityConfig` (Servlet): `/welcome`, `/reactive/**` permitAll; `/api/**` authenticated
  (данные требуют Bearer); CSRF игнорируется для `/api/**`, для остального — `CookieCsrfTokenRepository`
  (cookie `XSRF-TOKEN`, эхо в `X-CSRF-TOKEN`).
- Для Варианта 2 shell должен быть доступен **до аутентификации** — `/welcome` и `/registerpage`
  уже `permitAll` в MVC-цепочке, так что shell-точки входа публичны без новых послаблений.

## 3. Целевая архитектура (клиент)

- **Единый app-shell** `templates/app.html` — один документ: корень `<div id="app"></div>`,
  общие контейнеры (`#toastContainer`, `#global-spinner`, `#register-response`/`#response-div`
  переезжают внутрь вью), CDN-скрипты (axios/sockjs/stomp/dompurify), `trusted_policy.js`,
  nonce, точка входа `<script type="module" src="/app.js">`. Заменяет `chats_list.html`,
  `index.html`, `chatcreatepage.html`, `welcome.html`, `register.html`.
- **Роутер** `static/router.js` (History API):
  - `navigate(path)` → `history.pushState` + `renderRoute(path)` (без перезагрузки);
  - `renderRoute(path)` → `currentView?.unmount()` → очистка `#app` → `view.mount(params)`;
  - слушатель `popstate` (назад/вперёд) → `renderRoute(location)`;
  - карта маршрутов:
    - `/welcome` → welcome-view (публичный, дефолт для неаутентифицированного);
    - `/registerpage` → register-view (публичный);
    - `/reactive/chatlist` → chatlist-view;
    - `/reactive/chat` → chat-view (params `id`,`title` из query);
    - `/reactive/createchat` → createchat-view;
  - неизвестный путь → welcome (если нет сессии) / chatlist (если есть).
- **Вью-модули** (рефактор существующих; контракт `mount(params)` / `unmount()`):
  - `static/views/welcome.view.js` ← из `welcome.js` (логин-форма + кнопка Google + ссылка на регистрацию);
  - `static/views/register.view.js` ← из `register.js` (форма регистрации);
  - `static/views/chatlist.view.js` ← из `chatlist.js`;
  - `static/views/chat.view.js` ← из `index.js` (params от роутера, не из `window.location` на верхнем уровне);
  - `static/views/createchat.view.js` ← из `chatcreate.js`.
- **`static/app.js`** — точка входа shell: единичная инициализация (Trusted Types policy, axios),
  регистрация роутера, первый `renderRoute(location)`.
- **Общий STOMP-lifecycle-хелпер** `static/stomp-lifecycle.js` — регистрирует STOMP-клиенты вью
  и разом их дисконнектит в `unmount()`.

### 3.1 Контракт вью (обязателен)

```js
export function mount(params) { /* fetch /api → render в #app → (для чата) connect STOMP → wire listeners */ }
export function unmount()      { /* disconnect ВСЕХ STOMP-клиентов, clearTimeout, снять listeners, очистить #app */ }
```
`unmount()` **обязателен** — без него при переходах копятся STOMP-соединения и дублируются
подписки (сейчас teardown отсутствует нигде: `index.js`/`chatlist.js` открывают SockJS и не
закрывают — в MPA это маскировалось перезагрузкой). Welcome/register-вью STOMP не открывают —
их `unmount()` только снимает listeners форм и таймеры ошибок.

## 4. Серверные изменения (детально, по файлам)

1. **`WEBFLUX_Service.getChatList` / `renderChatPage`** — рендерить общий shell:
   `ParseWithThymeLeaf(model, "app", templateEngine)` вместо `"chats_list"`/`"index"`.
   `ParseWithThymeLeaf` сам кладёт `nonce`. **CSS-путь:** `chats_list.html` использует
   `${pathPrefix + '/css/style2.css'}`, `index.html` — `@{/css/style2.css}` (расходятся); в
   `app.html` выбрать один путь, реально резолвящийся под `/reactive`-сервлетом (проверить при
   реализации), не завязываться на `pathPrefix`.
2. **`WebFluxConfig`** — добавить бин-роут `GET /createchat` → рендер того же shell
   (метод `renderCreateChatPage`), включить в `combinedRoutes`.
3. **`ReactiveSecurityConfig:51`** — добавить `"/createchat"` в
   `pathMatchers(GET, "/chatlist", "/chat", "/createchat").permitAll()` (shell публичен).
4. **`WEBFLUX_Service.handleCreateChat`** — на успехе вернуть **`200` JSON `{chatId, title}`**
   вместо `303 → /reactive/chatlist`. Ветки ошибок (`666`/`500`/text-plain) не трогаем.
5. **`MVC_Service.GetWelcome` (`/welcome`) и `GetRegisterPage` (`/registerpage`)** — вместо
   `return "welcome"`/`"register"` рендерить общий shell (`return "app"`; `generateandputNonce`
   сохраняется — он ставит nonce **и** CSP-заголовок). Роутер по пути покажет нужную вью.
   Параметр `?error=` (welcome) читает клиентская welcome-вью из query, а не из модели.
   `app.html` должен резолвиться и MVC-, и WebFlux-резолвером (оба смотрят в `templates/` classpath).
6. **`MVC_Service.GetCreateChat` (`/createchatpage`)** — задепрекейтить: `302 → /reactive/createchat`.
7. **Шаблоны:** добавить `templates/app.html`; удалить `chats_list.html`, `index.html`,
   `chatcreatepage.html`, `welcome.html`, `register.html` после переноса разметки в shell/вью.
   `callback.html` и `fingerpring_collector.html` **остаются**.

## 5. CSP / nonce / CSRF

- **nonce:** shell отдаётся с per-request `nonce`. MVC-путь (`generateandputNonce`) ставит nonce
  **и** заголовок `Content-Security-Policy`; WebFlux-путь (`ParseWithThymeLeaf`) ставит только
  nonce в модель (CSP-заголовок не эмитит — так и сейчас на `chatlist`/`chat`). Оба сохраняем
  как есть; nonce на `<script>`-тегах shell проставляется через `${nonce}`.
- **strict-dynamic:** скрипт с валидным nonce может динамически подгружать другие → `app.js`
  (с nonce) вправе `import()` вью. Роутер использует статические `import` вверху `app.js`
  (проще под Trusted Types), ленивый `import()` — опционально. `trusted_policy.js` и
  `policy.createHTML(...)` переиспользуются во всех вью.
- **CSRF:** cookie-based (`CookieCsrfTokenRepository`). Клиентские формы welcome/register читают
  cookie `XSRF-TOKEN` и шлют `X-CSRF-TOKEN` — как уже делают `welcome.js`/`register.js`. Серверный
  скрытый `<input name="_csrf">` больше не нужен (формы рисует JS); зависимость от него убрать.

## 6. Модель токенов и её инвариант при миграции

Не меняем модель хранения — только убираем перезагрузки, которые её обнуляют:
- **access** — in-memory (`inmemory.js`, переменная замыкания), стирается при любом full-load.
  Это осознанный анти-XSS выбор.
- **refresh** — httpOnly cookie, переживает перезагрузку, недоступен из JS.
- **silent-refresh** (`auth.js: ensureAccessToken` + axios 401-интерцептор → `/exchangeTokens`)
  восстанавливает access по refresh-cookie на fresh-load.

Эффект Варианта 2 на инвариант:
- Переходы внутри shell (включая **login/register success → chatlist**) — `router.navigate`, без
  reload → **access не стирается**, silent-refresh не нужен, `q1b` устранён на границе входа.
- Оставшиеся fresh-load'ы (первый заход на `/welcome`, возврат от Google на `/authcallback`,
  аварийный `→/welcome`) по-прежнему покрыты silent-refresh'ом — сессия не теряется.

## 7. Тестирование и верификация

- **Бэкенд:** юнит на `handleCreateChat` (успех → `200` JSON `{chatId,title}`, не `303`);
  проверка, что `/welcome`, `/registerpage`, `/reactive/createchat` отдают shell и `permitAll`.
  Сборка строго JDK21.
- **Фронтенд (live, Chrome MCP после `/kdeploy`):**
  1. `/welcome` → shell загружен один раз; видна форма логина.
  2. Ссылка «регистрация» → `router.navigate('/registerpage')`, **без перезагрузки**; форма регистрации.
  3. Успешная регистрация → `router.navigate` в chatlist, **без reload, токен жив** (нет повторного
     логина, нет запроса `/exchangeTokens` — проверяем в Network). Это прямой регресс-тест `q1b`.
  4. Клик по чату → `/reactive/chat?id=…`, документ не перезагружается, сообщения грузятся, STOMP подключён.
  5. Назад (браузер) → chatlist без reload, **старый STOMP чата отключён** (нет дублей — по числу SockJS).
  6. Создать чат → `/reactive/createchat` → сабмит → navigate в chatlist без reload, чат виден.
  7. Deep-link/refresh (F5) на `/reactive/chat?id=…` → сервер отдаёт shell, JS рисует чат, токен
     восстановлен silent-refresh'ом.
  8. Google-кнопка → уход на accounts.google.com (реальная навигация — так и должно).
- **GIF** прохода для ревью (по желанию).

## 8. Риски и меры

| Риск | Мера |
|---|---|
| Утечки/дубли STOMP при навигации | Обязательный `unmount()` с disconnect; тест §7.5 на число соединений |
| Deep-link/refresh не работает | Сервер отдаёт shell на всех shell-роутах (welcome/register/chatlist/chat/createchat); тест §7.7 |
| Back/forward ломает состояние | Обработчик `popstate` + идемпотентный `renderRoute` |
| Форма логина/регистрации ломается на клиентском рендере | CSRF cookie-based (совместимо); юнит/live на вход и регистрацию (§7.1–7.3) |
| Неаутентифицированный доступ к shell раскрывает данные | Данные только через `/api/**` (authenticated); shell пуст, без серверных данных |
| nonce/Trusted Types на модулях | Статические `import` в `app.js`; `policy.createHTML` переиспользуется |
| Смешение MVC/WebFlux (shell из двух резолверов) | `app.html` в `templates/`, доступен обоим; `/createchat` GET в permitAll; проверка §7 |

## 9. Предварительная декомпозиция (для writing-plans)

1. **Бэкенд-шелл + роуты:** `app.html`; рендер shell в `getChatList`/`renderChatPage`;
   `GetWelcome`/`GetRegisterPage` → shell; новый `GET /reactive/createchat`;
   `ReactiveSecurityConfig` permitAll; `/createchatpage` → redirect. (изолированно, без клиента).
2. **`handleCreateChat` → JSON** + юнит-тест (изолированно, бэкенд).
3. **Каркас клиента:** `app.js` + `router.js` (маршруты welcome/register/chatlist/chat/createchat) +
   `stomp-lifecycle.js` + контракт вью (заглушки вью).
4. **welcome-вью + register-вью:** рефактор `welcome.js`/`register.js` → вью shell; успех входа/
   регистрации → `router.navigate`; ссылка welcome↔register → navigate; Google-кнопка остаётся reload.
5. **chatlist-вью:** рефактор `chatlist.js` → `views/chatlist.view.js` (mount/unmount, navigate).
6. **chat-вью:** рефактор `index.js` → `views/chat.view.js` (params от роутера, STOMP teardown).
7. **createchat-вью:** рефактор `chatcreate.js` → `views/createchat.view.js` (navigate по JSON).
8. **Чистка:** удалить старые шаблоны/скрипты, обновить ссылки, live-верификация (вкл. `q1b` на границе входа).

Задачи 1–2 — бэкенд, параллелятся. 3 блокирует 4–7. 4–7 после каркаса, трогают разные файлы.
8 — финал. Точную последовательность и review-петли определит план (subagent-driven).
