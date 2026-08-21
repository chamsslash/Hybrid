# Каталог тестов проекта

> **Назначение.** Единый живой реестр всех автотестов проекта: что тестируется, зачем и на каком уровне.
> Аудитория — сам автор через месяцы: быстро понять, что уже покрыто, чтобы не дублировать и не оставлять дыр.

## Правило поддержки (ОБЯЗАТЕЛЬНО)

**Этот файл обновляется в ТОМ ЖЕ коммите/ветке, что и изменение тестов.** Всегда, когда:

- добавлен новый тест-файл → добавить его секцию (модуль, класс, тип, что покрывает, список тестов);
- добавлен/удалён/переименован отдельный тест-метод → синхронизировать список методов и счётчик;
- изменилось поведение теста (что именно проверяет) → обновить описание;
- тест удалён вместе с фичей → удалить его секцию.

У КАЖДОГО теста должно быть расписано: **что делает** (входные данные/сценарий), **что проверяет** (ассерты), **зачем** (какой баг/контракт стережёт, ссылка на beads-тикет если есть). Не ограничиваться именем метода.

При сомнении «обновлять ли каталог» — обновлять. Рассинхрон каталога с кодом хуже его отсутствия.

## Условные обозначения

- **Тип `unit`** — быстрый, всё замокано (Mockito), запускается всегда в `mvn test`.
- **Тип `reactive-unit`** — unit на WebFlux/Reactor через `StepVerifier`/mock-серверные примитивы, тоже в обычном `mvn test`.
- **Тип `integration` (`*IT`, `@Tag("integration")`)** — Testcontainers, **требует Docker**. Исключён из обычного `mvn test` через surefire `excludedGroups=integration`; запуск явно: `mvn test -Dgroups=integration`.
- Сборка/прогон — **только JDK21** (на JDK25 Mockito ломается). `export JAVA_HOME=$(/usr/libexec/java_home -v 21)`.

## Сводка

| Модуль | Файлов | unit/reactive | integration | Тестов всего |
|---|---|---|---|---|
| AuthService | 5 | 17 | 2 | 19 |
| HTTPService | 15 | 89 | 2 | 91 |
| MessegerParody | 2 | 8 | 0 | 8 |
| **Итого** | **22** | **114** | **4** | **118** |

CI (`.github/workflows/build.yml`, job `unit-tests`) прогоняет unit-тесты всех трёх модулей (AuthService, HTTPService, MessegerParody). Integration (`*IT`) в CI по умолчанию не запускаются (нужен Docker-раннер + `-Dgroups=integration`).

---

## AuthService

### `AuthSuccessHandlerTest` — `unit` — OAuth success-flow (beads ok9)
Хендлер `AuthSuccessHandler.onAuthenticationSuccess`, вызываемый после успешного Google-логина. `RedisTemplate`/`ValueOperations` замоканы.

- **`successStoresOneTimeCodeAndRedirectsWithStateAndCode`** — вход: principal с `sub="42"`, запрос с `state="st-1"`. Проверяет: в Redis кладётся ключ `UserOneTimeCodeFastCheck<code>` со значением `sub=42` (DB user id), TTL `300 SECONDS`; редирект ведёт на `/authcallback`, несёт `state=st-1` и `code`, совпадающий с суффиксом Redis-ключа. Зачем: стережёт контракт одноразового кода между AuthService и callback-флоу.
- **`emptyStateSkipsRedirectAndDoesNotStoreCode`** — пустой `state` → ни записи в Redis, ни редиректа. Зачем: не выдавать одноразовый код без валидного OAuth-state (CSRF-защита state).
- **`missingSubThrows`** — principal без `sub` (null) → `IllegalArgumentException`. Зачем: не пропускать в систему пользователя без идентификатора.

### `CustomOAuth2UserServiceUploadImageTest` — `unit` — Upload_image аватарки (beads lyo)
`CustomOAuth2UserService.Upload_image` с замоканными `ImageStorageService` и `KafkaProducer`.

- **`uploadsDecodedBytesAndPublishesLowercaseContract`** — вход: Base64-аватарка + `userId="42"`. Проверяет: в MinIO (`putObject`) уходит ключ `userimage/42/*.jpg`, **декодированные** байты (не Base64) и content-type `image/jpeg`; в Kafka-топик `Images` уходит ровно 3 поля `{targetType:"userimage", targetId:"42", objectKey:<тот же ключ>}`, без поля `Base64Image`. Зачем: стережёт новый lowercase-контракт топика картинок и переход с Base64-в-Kafka на ключ-в-Kafka.

### `JwtCheckControllerTest` — `unit` — валидация JWT в gateway-проверке
`JwtCheckController.jwtCheckProcess` с реальной парой RSA-ключей (генерится в `@BeforeEach`) и замоканным `RedisTemplate`. Токены строятся `Jwts.builder()`.

- **`validTokenWithSessionReturns200AndHeaders`** — валидный токен + существующая refresh-сессия в Redis → `200` и заголовки `X-User-ID=42`, `X-Jti=access-jti`, `X-Sid=sid-1`, `X-Authorities=[{"authority":"USER"}]`. Зачем: контракт заголовков, которые gateway прокидывает вниз сервисам.
- **`missingRefreshSessionReturns401`** — валидный токен, но нет `RefreshSession:*` в Redis → `401`. Зачем: отозванная/истёкшая сессия не должна проходить даже с валидной подписью.
- **`expiredTokenReturns401WithJti`** — просроченный токен → `401` + заголовок `X-Jti` (для логирования/отзыва). Зачем: истечение access-токена.
- **`garbageTokenReturns401`** — строка не-JWT → `401`. Зачем: устойчивость к мусору.
- **`missingTokenReturns401`** — нет заголовка Authorization → `401`.
- **`tokenSignedWithWrongKeyReturns401`** (beads ok9) — токен, подписанный **чужим** RSA-ключом (attacker key), → `401` по несовпадению подписи. Зачем: главная защита — подделанная подпись отвергается.
- **`tokenWithoutSidReturns401`** — валидная подпись, но нет claim `sid` → `401`. Зачем: без session-id нельзя проверить отзыв сессии.

### `Services/AuthImplTest` — `unit` — gRPC-сервис Auth_impl (beads 820, x7m)
`Auth_impl` с замоканными `Auth_rep`, `RedisTemplate`, `MyPasswordEncoder`, `Oauth2Utils`. Ответы через `StreamObserver` (mock + `ArgumentCaptor`).

- **`loginSuccessReturnsSubAsUserId`** (A1/820) — верный логин/пароль → `AuthResponse` `status=200`, **`sub="42"` (DB user id)**, `role=USER`; `onError` не вызывается. Зачем: главный фикс 820 — обычный (не-Google) логин раньше не проставлял `sub`.
- **`loginBadPasswordReturns401NotOnError`** (A2/x7m) — неверный пароль → `AuthResponse status=401` через `onNext`+`onCompleted`, **НЕ** `onError`. Зачем: фикс x7m — клиент получал generic-ошибку вместо внятного 401.
- **`loginUnknownUserReturns401`** — несуществующий юзер → `AuthResponse status=401`, без `onError`. Зачем: регрессия — неизвестный юзер по-прежнему 401 (не утечка «юзер не найден» отдельным кодом).
- **`getUserBySubNumericResolvesById`** (A4) — `sub="42"` → `findById(42)`, возвращает User; `findByGoogleSub` НЕ дёргается. Зачем: после канонизации `sub`=DB id резолв идёт по id.
- **`getUserBySubNonNumericReturnsNotFound`** (A4) — нечисловой `sub` → `onError` (`StatusRuntimeException` NOT_FOUND), без краша `NumberFormatException`. Зачем: graceful-обработка старых/битых токенов после смены контракта.
- **`checkOneTimeCodeReturnsNumericUserIdAsSub`** (A3) — по коду в Redis лежит `google_sub`, юзер найден `findByGoogleSub` → в ответ идёт **`sub="99"` (numeric user id)**, а не google_sub. Зачем: Google-путь тоже отдаёт канонический numeric sub.

### `Services/ImageStorageServiceIT` — `integration` (Docker) — MinIO round-trip (beads ok9)
`ImageStorageService` против реального `MinIOContainer`, чтение обратно сырым `MinioClient`.

- **`putObjectCreatesBucketAndStoresBytesExactly`** — 64 KiB детерминированных байт по ключу `userimage/1/roundtrip.jpg` → бакет создан (`ensureBucket`), байты читаются точь-в-точь (сравнение + SHA-256), content-type `image/jpeg`. Зачем: реальное сохранение аватарки без моков SDK.
- **`putObjectIsIdempotentAcrossCallsOnSameBucket`** — повторный `putObject` на уже существующий бакет не падает, объект читается. Зачем: идемпотентность создания бакета.

---

## HTTPService

### `ImageUploadDTOTest` — `unit` — (де)сериализация контракта топика Images (beads se2)
Round-trip `ImageUploadDTO` через Gson.

- **`roundTripsUserImageContract`** — `userimage`-DTO → JSON → обратно, равенство + наличие полей `targetType/targetId/objectKey` в JSON.
- **`roundTripsChatImageContract`** — то же для `chatimage`.
- **`deserializesLiteralContractJson`** — парсинг литеральной JSON-строки контракта в DTO. Зачем: гарантия, что имена полей DTO совпадают с проводным контрактом Kafka-топика.

### `MvcJwtAuthFilterTest` — `unit` — фильтр аутентификации по X-заголовкам
`MvcJwtAuthFilter` с mock-сервлет-примитивами Spring; `SecurityContextHolder` чистится в `@AfterEach`.

- **`parsesAuthoritiesFromObjectFormat`** — `[{"authority":"USER"}]` → `[USER]`.
- **`parsesAuthoritiesFromPlainStringFormat`** — `["ADMIN","USER"]` → `[ADMIN, USER]`. Зачем: фильтр понимает оба формата authorities.
- **`returnsEmptyListOnGarbage`** — мусор/`null` → пустой список (без исключения).
- **`setsAuthenticationFromHeaders`** — запрос с `X-User-ID=42` и `X-Authorities` → в `SecurityContext` появляется Authentication с principal `42` и `[USER]`. Зачем: контракт gateway→MVC (заголовки, которые ставит `JwtCheckController`).
- **`skipsAuthenticationWithoutHeaders`** — без заголовков → Authentication не ставится (остаётся анонимом).

### `Services/AuthGrpcTest` — `unit` — gRPC-клиент AuthGrpc (beads 93e)
`AuthGrpc` с замоканным blocking-stub и метрикой.

- **`authRegisterThrowsAuthResponseExceptionOnDuplicateUser`** — register вернул `status=666` → бросается `AuthResponseException` с тем же статусом и сообщением «User with such name already exists». Зачем: не-200 не должен молча схлопываться в null.
- **`authRegisterReturnsResponseOnSuccess`** — `status=200` → возвращается сам ответ.
- **`authLoginThrowsAuthResponseExceptionWhenUserMissing`** — login вернул `status=404` → `AuthResponseException("404", ...)`.
- **`authLoginReturnsResponseOnSuccess`** — `status=200` → возвращается ответ. Зачем: типизированный проброс не-200 статусов на фронт (внятные сообщения).

### `Services/ImageStorageServiceIT` — `integration` (Docker) — MinIO round-trip реактивный (beads se2)
`ImageStorageService` (реактивный) против `MinIOContainer`, проверка через `StepVerifier`.

- **`putThenGetRoundTripsBytesExactly`** — 64 KiB → `putObject` → `getObject`, байты точь-в-точь + SHA-256. Зачем: реальный round-trip реактивного стораджа.
- **`putThenGetPreservesContentType`** — сохранение с `text/plain`, чтение сохраняет content-type. Зачем: content-type переживает round-trip.

### `Services/ImageStorageServiceTest` — `reactive-unit` — ImageStorageService с mock MinioClient (beads se2)
`ImageStorageService` с замоканным `MinioClient`, `StepVerifier` + `ArgumentCaptor` по `*Args`.

- **`ensureBucketCreatesBucketWhenMissing`** — бакета нет → `makeBucket` с правильным именем.
- **`ensureBucketSkipsCreationWhenBucketExists`** — бакет есть → `makeBucket` НЕ вызывается. Зачем: идемпотентность.
- **`putObjectSendsCorrectBucketKeyAndContentType`** — `putObject` формирует `PutObjectArgs` с верными bucket/object/contentType.
- **`putObjectDefaultsContentTypeWhenNull`** — content-type `null` → дефолт `application/octet-stream`.
- **`putObjectEnsuresBucketBeforeWriting`** — при отсутствующем бакете `putObject` сперва создаёт бакет, затем пишет.
- **`getObjectReturnsBytesAndContentType`** — `getObject` возвращает байты и content-type из ответа MinIO.
- **`getObjectPropagatesErrorAsMonoError`** — исключение SDK → `Mono.error` (а не выброс наружу). Зачем: ошибки не рвут реактивную цепочку.

### `Services/MVC_ServiceComputeLikelihoodTest` — `unit` — скоринг доверия (AI + эвристика)
`MVC_Service.computeLikelihood` с замоканными `GeminiService` и `FpSimilarityScore`.

- **`passesWhenAverageAboveThreshold`** — AI=90, эвристика=40 → среднее 65 ≥ 60 → `true`.
- **`failsWhenAverageBelowThreshold`** — AI=20, эвристика=40 → среднее 30 < 60 → `false`. Зачем: **регрессия бага** — раньше `"20"+40.0` конкатенировалось в `"2040.0"` и проверка всегда проходила.
- **`fallsBackToHeuristicWhenAiFails`** — AI-вызов падает (`Mono.error`), эвристика=75 → `true`. Зачем: деградация без AI (в логе при этом ожидаемый ERROR — это не падение теста).

### `Services/RegisterHandleErrorTest` — `reactive-unit` — ошибка регистрации на WebFlux (beads 93e)
`WEBFLUX_Service.registerHandle` c замоканными `ParsingDataService`/`AuthGrpc`, реальная запись `ServerResponse` в mock-exchange.

- **`duplicateUserReturns409WithRealMessageNotGenericFormError`** — `authRegister` бросает `AuthResponseException("666", "User with such name already exists")` → ответ `409 CONFLICT` с телом = реальным текстом ошибки, а НЕ «Отсутствуют данные формы.». Зачем: фикс 93e — юзер видел generic-ошибку вместо «имя занято».

### `Services/WEBFLUX_ServiceUploadImageTest` — `reactive-unit` — Upload_image роутинг (beads se2)
`WEBFLUX_Service.Upload_image` с замоканными `ImageStorageService`/`KafkaProducer`, mock `FilePart`.

- **`routesRegistrationAvatarToUserimageWithGeneratedKey`** — `targetType=userimage`, id=42, PNG → ключ `userimage/42/<uuid>.png` (UUID проверяется регэкспом), байты и content-type `image/png` верны, в Kafka событие с тем же `objectKey`.
- **`routesChatCreationImageToChatimage`** — `targetType=chatimage`, id=7, JPEG → ключ `chatimage/7/<uuid>.jpg`, Kafka-событие `chatimage`.
- **`defaultsExtensionToJpgWhenFilenameHasNoExtension`** — имя файла без расширения → ключ оканчивается `.jpg`.
- **`propagatesStorageFailureWithoutPublishingToKafka`** — `putObject` падает → ошибка пробрасывается, в Kafka **ничего не публикуется**. Зачем: не рассылать событие о картинке, которая не сохранилась.

### `Services/AppShellRenderTest` — `unit` — рендеринг SPA-шелла (Thymeleaf)
Рендерит шаблон `app.html` через `SpringWebFluxTemplateEngine` с classloader-резолвером, без поднятия контекста Spring.

- **`appShellRendersRootAndEntrypoint`** — в модель кладётся `nonce="testnonce"`, рендерится шаблон `app`. Проверяет: в HTML есть `id="app"` (корневой SPA-контейнер), `src="/app.js"` (точка входа фронтенда) и `nonce="testnonce"` (CSP nonce проброшен в `<script>`). Зачем: стережёт контракт SPA-шелла — если рендер сломается или nonce перестанет прокидываться, фронтенд не загрузится или упадёт по CSP.

### `Services/CreateChatJsonResponseTest` — `reactive-unit` — success-ответ создания чата (SPA-миграция)
`WEBFLUX_Service.createChatSuccess` — часть миграции с 303-редиректа на `/reactive/chatlist` на JSON-ответ, чтобы SPA навигировала без перезагрузки страницы. Проверяется через `ServerResponse.writeTo` в mock-exchange.

- **`successReturns200JsonWithChatIdAndTitle`** — успешный ответ бэкенда с `id=42`, `title="My Chat"` → `200 OK`, `Content-Type: application/json`, тело содержит `"chatId":42` и `"title":"My Chat"`. Зачем: контракт JSON-ответа, на который завязан клиентский роутинг SPA.
- **`successWithoutTitleReturnsOnlyChatId`** — ответ без `title` (`id=7`) → тело содержит `"chatId":7`, поле `title` отсутствует. Зачем: опциональность `title` в ответе не должна ломать сериализацию.

### `Services/ChatMembershipServiceTest` — `unit` — проверка членства в чате, fail-closed (beads g9x)
`ChatMembershipService` с замоканным `ReactorReactiveTransferServiceGrpc.ReactorReactiveTransferServiceStub`. Отвечает на вопрос «состоит ли пользователь в чате» — источник, от которого в следующих задачах зависит STOMP-интерцептор.

- **`membersReturnsUsersWithIdAndUsername`** — стаб возвращает `UserListResponse` с участниками `id=7,9`. Проверяет: `members(5L)` отдаёт список из 2 `UserDataRequest` с корректными `id`/`username` (`7`/`"user-7"`). Зачем: базовый контракт метода `members()`, на который опирается всё остальное.
- **`isMemberTrueWhenUserPresent`** — участники `7,9`, проверяем `userId="9"` → `true`. Зачем: happy path проверки членства.
- **`isMemberFalseWhenUserAbsent`** — участники `7,9`, проверяем `userId="42"` → `false`. Зачем: базовое отрицание для отсутствующего пользователя.
- **`isMemberFalseOnEmptyMemberList`** — gRPC вернул пустой список участников → `false`. Зачем: fail-closed — пустой список участников не должен трактоваться как «доступ всем».
- **`isMemberFalseWhenGrpcFails`** — стаб отдаёт `Mono.error` (MessegerParody недоступен) → `false`, без исключения наружу. Зачем: fail-closed при ошибке gRPC — недоступность бэкенда не должна открывать доступ.
- **`isMemberFalseOnTimeout`** — стаб зависает (`Mono.never()`), таймаут укорочен до 100мс через переопределение package-private `membershipTimeout()` → `false`. Зачем: fail-closed по таймауту (в проде — ровно `Duration.ofSeconds(5)`); тест не ждёт реальные 5 секунд.

### `Services/AiAssistMembershipTest` — `reactive-unit` — авторизация `/AiAssist` по членству в чате (beads dz5)
`WEBFLUX_Service.aiAssistHandler` с замоканными `ChatMembershipService`, `GeminiService` и реактивным Redis-стеком (`ReactiveRedisTemplate`/`ReactiveListOperations`/`ReactiveSetOperations`), поля контроллера подставлены `ReflectionTestUtils`. До фикса dz5 у хендлера **вообще не было `Principal` в сигнатуре**: `chat_id` брался из тела запроса, и любой залогиненный пользователь получал Redis-контекст чужого чата, пересказанный Gemini (подтверждено живьём на стенде kind: аккаунт `sunny` id=4, состоящий только в чате 3, получил `200` на `chat_id=2`). Тесты стерегут два контракта сразу: членство проверяется **до** чтения Redis, и отказ — настоящий HTTP-403, а не прежние `200` + текст в теле.

- **`memberReachesChatContext`** — `members(3L)` содержит принципала `id=4`, `chat_id="3"`. Проверяет: статус `200` и что Redis реально прочитан — `list.range("newmessages-3", 0, -1)`. Зачем: happy path, фикс не должен закрыть доступ участнику чата (acceptance 2 тикета dz5).
- **`nonMemberGetsForbiddenAndRedisIsNotRead`** — воспроизводит живой сценарий из dz5: принципал `id=4`, `members(2L)` = `{7,9}` без него. Проверяет: статус `403` (а не `200` с текстом) и что `redis.opsForList()`/`opsForSet()` не вызывались вообще. Зачем: **центральный сторож тикета dz5** — посторонний не должен доходить до чтения контекста чужого чата; утечки в проде не случилось только потому, что у чата 2 контекст был пуст.
- **`malformedChatIdIsRefusedBeforeAnySideEffect`** (`@ParameterizedTest`, 9 значений: `"+3"`, `"-3"`, `"abc"`, `""`, `" 3"`, `"3 "`, `"3.0"`, `"0x3"`, `"99999999999999999999"`) — Проверяет: `403`, `members(...)` не вызван ни разу (валидация формата — до gRPC), Redis не тронут. Зачем: та же строгая валидация `\d+`, что и в `ChatBoxStompController` (beads g9x) — голый `Long.parseLong` пропускает `"+3"`/`"-3"`; отдельно закрыт случай `"99999999999999999999"`, который проходит `\d+`, но не помещается в `long` — без `try/catch` он превратился бы в `500` вместо отказа.
- **`leadingZeroChatIdIsCanonicalized`** — `chat_id="003"`, `members(3L)` содержит принципала. Проверяет: членство спрошено именно про `3L`, а Redis-ключ — `newmessages-3`, не `newmessages-003`. Зачем: **сценарий 007 из g9x, но по HTTP** — `"003"` проходит `\d+`, и если бы побочные эффекты использовали сырую строку, контекст одного чата расщепился бы на два Redis-ключа (acceptance 4 тикета dz5).
- **`grpcFailureIsRefusedFailClosed`** — `members(3L)` отдаёт `Mono.error` (MessegerParody недоступен). Проверяет: `403`, Redis не тронут. Зачем: fail-closed — недоступность бэкенда не должна открывать доступ к контексту.
- **`membershipTimeoutIsRefusedFailClosed`** — `members(3L)` зависает (`Mono.never()`), таймаут укорочен до 200мс стабом package-private `membershipTimeout()`. Проверяет: `403`, Redis не тронут. Зачем: fail-closed по таймауту; заодно фиксирует, что проверка встроена в реактивную цепочку через `.timeout(...)`, а не через блокирующий `.block()` в общем пуле (та ошибка уже стоила P1 в beads 8wh).
- **`emptyMemberListIsRefusedFailClosed`** — `members(3L)` возвращает пустой список. Проверяет: `403`, Redis не тронут. Зачем: fail-closed — пустой список участников нельзя трактовать как «доступ всем».
- **`missingPrincipalIsRefused`** — `principal == null`. Проверяет: `403`, `members(...)` не вызван, Redis не тронут. Зачем: страховка на случай, если запрос дойдёт до хендлера мимо `MvcJwtAuthFilter` — хендлер обязан отказать сам, а не упасть с NPE.
- **`forbiddenReachesTheWireAsHttp403`** — сквозная проверка через `MockMvc.standaloneSetup`: multipart-запрос `/AiAssist` от постороннего, ответ забирается вторым, async-диспатчем (`asyncDispatch`). Проверяет: `request().asyncStarted()` и итоговый статус `403`. Зачем: смена типа возврата на `Mono<ResponseEntity<String>>` бессмысленна, если Spring MVC не донесёт статус до провода — остальные тесты этого класса смотрят на `ResponseEntity` до сериализации и такую поломку бы не увидели. Заодно фиксирует контракт для фронта: `chat.view.js#requestAIResponse` ходит через axios-интерцептор, который перехватывает только `401`, поэтому `403` штатно уходит в `.catch`.

### `StompHandlers/ChatBoxStompControllerTest` — `unit` — авторство сообщения и статуса набора текста из принципала и адреса, проверка членства (beads g9x), порядок времени сообщений (beads 525)
`ChatBoxStompController.HandleChatMessage` и `HandleChangeOfUserStatus` с замоканными `KafkaProducer`, `SimpMessagingTemplate`, `ChatMembershipService`, `ChatListStompController` и реактивным Redis-стеком (`ReactiveRedisTemplate`/`ReactiveListOperations`/`ReactiveSetOperations`), поля контроллера подставлены `ReflectionTestUtils`. Кроме авторства, контроллер с этим рефакторингом (g9x) стал единственным местом, где на SEND проверяется членство в чате: отправитель отсутствует в списке участников из `members(chatId)` → разбор фрейма прерывается без единого побочного эффекта. Оба метода также стали единственным местом строгой валидации формата `chatId` (`\d+`) на SEND-пути и канонизации его в побочных эффектах (адреса рассылки, Redis-ключ, `chat_id` в DTO) — обе обязанности раньше частично покрывал `parseChatIdOrDeny` в интерцепторе, но перенос проверки членства унёс их без замены (регрессия, закрытая тем же тикетом g9x). Отдельная группа тестов (beads 525) стережёт **момент присвоения времени сообщения**: оно фиксируется на входе в `HandleChatMessage`, а не внутри колбэка `members(chatId)`, поэтому порядок `timestamp` совпадает с порядком прихода фреймов, а не с порядком возврата gRPC-вызовов.

- **`serverOverwritesForgedIdentityFromFrameBody`** — клиент шлёт фрейм на `/app/chat/send/5` от принципала `userId="9"`, но в теле подделывает `chat_id="77"`, `user_id="7"`, `username="Оля"`; `membership.members(5L)` возвращает участников `{9:"Дима", 7:"Оля"}`. Проверяет: в `template.convertAndSend` уходит `/mutual/chat/5` (адрес, не тело) с DTO, где `chat_id="5"`, `user_id="9"`, `username="Дима"` (все три — из принципала/адреса/списка участников, не из тела), `text="привет"` (единственное поле из тела) и непустой `timestamp`. Зачем: **центральный сторож тикета g9x** — раньше сервер верил телу фрейма, и подделанное авторство оседало в БД навсегда.
- **`previewFansOutToAllChatMembers`** — `membership.members(5L)` возвращает участников `9,7`. Проверяет: `chatListController.ChangeChatPreview` получает список id `["9","7"]`, собранный из ответа gRPC-членства, а не из старого fire-and-forget `reactiveGetAllIdsByChatId`. Зачем: превью чат-листа должно рассылаться всем реальным участникам чата.
- **`nothingIsBroadcastWhenMembershipLookupFails`** — `membership.members(5L)` возвращает `Mono.error`. Проверяет: ни один из четырёх побочных эффектов success-лямбды не вызывается — `template.convertAndSend`, `kafkaProducer.send`, `chatListController.ChangeChatPreview` и запись Redis-контекста (`list.leftPush`). Зачем: fail-closed — раньше рассылка шла ПЕРВОЙ, а gRPC-запрос участников был fire-and-forget следом; теперь участники запрашиваются первыми, и вся рассылка живёт внутри `subscribe(...)`, поэтому недоступность MessegerParody не даёт наружу уйти ни одному сообщению с неподтверждённым авторством — по всем каналам утечки, не только по двум основным.
- **`messageIsNotBroadcastWhenSenderIsNotAChatMember`** (beads g9x) — `membership.members(5L)` возвращает участников `{7:"Оля"}` без отправителя `userId="9"`. Проверяет: ни `template.convertAndSend`, ни `kafkaProducer.send`, ни `chatListController.ChangeChatPreview`, ни запись Redis-контекста (`list.leftPush`) не вызваны. Зачем: **новый центральный сторож g9x** — проверка членства перенесена сюда из `StompAuthChannelInterceptor` (он на SEND её больше не делает, чтобы не дублировать этот же gRPC-вызов и не блокировать пул `clientInboundChannel`); раньше отсутствие отправителя в списке участников тихо подставляло сырой `senderId` вместо username и рассылка всё равно происходила — теперь она прерывается без единого побочного эффекта.
- **`typingStatusIdentityComesFromPrincipalNotBody`** — клиент шлёт фрейм на `/app/chat/user_statuses/5` от принципала `userId="9"`, но в теле DTO подделывает `user_id="7"`, `user_name="Оля"`, `chat_id="77"`; `membership.members(5L)` возвращает участников `{9:"Дима", 7:"Оля"}`. Проверяет: в `template.convertAndSend` уходит `/mutual/typing/5` с DTO, где `user_id="9"`, `user_name="Дима"`, `chat_id="5"` — все три взяты из принципала/адреса/списка участников, тело проигнорировано. Зачем: тот же контракт авторства, что и для сообщений чата — статус набора текста нельзя было подделать раньше, теперь нельзя и здесь.
- **`typingStatusFansOutPerMemberAndNotGlobally`** — `membership.members(5L)` возвращает участников `9,7`. Проверяет: `template.convertAndSend` вызывается на `/mutual/chatlist/typing/9` и `/mutual/chatlist/typing/7` (веерная рассылка по участникам), но ни разу — на `/mutual/typing_statuses_channel`. Зачем: **закрывает утечку графа общения** — раньше статус набора текста дублировался на глобальный канал, на который подписан список чатов (`chatlist.view.js`), из-за чего любой пользователь с открытым списком чатов видел, кто и в каком чате печатает, во всей системе; теперь адресация строго по участникам конкретного чата.
- **`typingStatusIsNotBroadcastWhenSenderIsNotAChatMember`** (beads g9x) — `membership.members(5L)` возвращает участников `{7:"Оля"}` без отправителя `userId="9"`. Проверяет: ни `template.convertAndSend`, ни `kafkaProducer.send`, ни `chatListController.ChangeChatPreview`, ни запись Redis-контекста (`list.leftPush`) не вызваны. Зачем: тот же контракт членства, что и `messageIsNotBroadcastWhenSenderIsNotAChatMember`, но для ветки типинг-статусов — проверка членства перенесена из интерцептора и сюда тоже, симметрично `HandleChatMessage`.
- **`nonNumericChatIdCausesNoSideEffects`** (beads g9x) — `HandleChatMessage("+7", ...)`. Проверяет: `membership.members(...)` не вызван вообще (валидация формата — до gRPC), как и `template.convertAndSend`, `kafkaProducer.send`, `chatListController.ChangeChatPreview`, `list.leftPush`. Зачем: **регрессия предыдущей правки g9x** — перенос проверки членства из интерцептора в контроллер унёс с собой и строгую валидацию формата `\d+`, которую раньше на SEND делал `parseChatIdOrDeny`; голый `Long.parseLong` пропускает `"+7"`, `"-5"` и другой мусор — контроллер обязан отказать сам, до любого побочного эффекта.
- **`leadingZeroesChatIdBroadcastsToCanonicalAddress`** (beads g9x) — `membership.members(7L)` возвращает участника `9:"Дима"`, `HandleChatMessage("007", ...)`. Проверяет: рассылка уходит на `/mutual/chat/7` (канонический адрес), а не на буквальный `/mutual/chat/007`; DTO несёт `chat_id="7"`. Зачем: **сценарий 007** — `"007"` проходит `\d+`, но `Long.parseLong("007") == 7`; если бы побочные эффекты использовали сырую строку `chatId`, рассылка ушла бы на адрес без живых подписчиков (участник чата 7 подписан на `/mutual/chat/7`), а Redis-контекст AI-assist расщепился бы на два ключа.
- **`leadingZeroesChatIdCanonicalizesTypingAddress`** (beads g9x) — тот же сценарий `007`, но для `HandleChangeOfUserStatus`. Проверяет: рассылка уходит на `/mutual/typing/7`, DTO несёт `chat_id="7"`. Зачем: та же канонизация адреса, что и для сообщений чата, симметрично `leadingZeroesChatIdBroadcastsToCanonicalAddress`.
- **`timestampFollowsHandlerCallOrderNotMembershipCompletionOrder`** (beads 525) — хендлер вызывается 10 раз подряд от одного принципала (`ord-01`…`ord-10`) с паузой 2 мс, но `membership.members(5L)` отдаёт по `Sinks.One` на вызов, и ответы эмитятся в **обратном** порядке (`ord-10` → `ord-01`), тоже с паузами. Проверяет: разослано ровно 10 сообщений, и `Instant.parse(timestamp)` строго возрастает в порядке отправки `ord-01 < ord-02 < … < ord-10`, а не в порядке ответов gRPC. Зачем: **центральный сторож тикета 525** — присвоение `Instant.now()` стояло внутри колбэка `members(chat)`, то есть фиксировало момент возврата round-trip, чей порядок ничем не гарантирован; на живом стенде 10 сообщений подряд разъехались на 90 мс и осели в БД в порядке `03,01,06,05,08,10,07,09,04,02`, а так как история сортируется по `time_stamp` (`MessegerParody/.../ReactiveRepository.java`), каша переживала перезагрузку страницы и рестарт пода. Инвертированные ответы в тесте — это ровно та ситуация: со старым кодом `timestamp` получались строго убывающими. Паузы нужны, чтобы соседние `Instant.now()` гарантированно различались, а не схлопывались в одно значение.
- **`clientSuppliedTimestampFromBodyIsIgnored`** (beads 525, g9x) — в теле фрейма приходит `timestamp="1999-01-01T00:00:00Z"`. Проверяет: разосланный DTO несёт **не** это значение, а серверное время не раньше момента вызова хендлера. Зачем: перенос присвоения времени в начало метода (525) не должен ослабить контракт g9x «тело фрейма не источник истины» — иначе клиент выбирал бы своему сообщению любое место в истории, которая сортируется по `time_stamp`.

### `StompAuthChannelInterceptorTest` — `unit` — аутентификация, аллоулист SEND и проверка членства на SUBSCRIBE (beads g9x)
`StompAuthChannelInterceptor` с замоканными `AccessTokenVerifier` и `ChatMembershipService`. Фреймы собираются хелпером `frame()` от имени аутентифицированного пользователя `userId="9"`. Изначально закрывала дыру: раньше интерцептор проверял только факт логина, а не то, что пользователь состоит в чате, куда пишет/на который подписывается. Финальное ревью (security-тикет g9x) добавило два Critical-дефекта: C1 — подписка Ant-шаблоном (`/mutual/**`) обходила все префиксные проверки, потому что `DefaultSubscriptionRegistry` матчит шаблон против каждого исходящего адреса брокера; C2 — `SEND` напрямую на брокерный адрес (`/mutual/...`, `/private/...`) вообще не попадал в `@MessageMapping` и проверку членства, `SimpleBrokerMessageHandler` рассылал его подписчикам как есть, позволяя подделать авторство сообщения в чужом чате.
Позже (рефакторинг g9x, тот же security-тикет после финального ревью) проверка членства на `SEND` **убрана** из интерцептора и перенесена в `ChatBoxStompController`: контроллер и так вызывает `ChatMembershipService.members(chatId)` за списком получателей веерной рассылки, поэтому интерцептор дублировал тот же gRPC-вызов вторым разом на каждый фрейм (особенно чувствительно на typing-статусах — они летят вдвое чаще), а `isMember` внутри интерцептора блокировал (`.block()`) общий пул `clientInboundChannel`, которым обслуживаются вообще все STOMP-команды всех сессий, включая CONNECT. На `SEND` в интерцепторе остались только аутентификация и аллоулист `/app/`. `SUBSCRIBE` не тронута — там контроллера нет, переносить проверку некуда.

- **`sendToOwnChatPassesWithoutMembershipCheck`** — `SEND /app/chat/send/5` от аутентифицированного пользователя → фрейм проходит, `membership.isMember(...)` не вызван (`Mockito.verify(..., never())`). Зачем: happy path прохождения аллоулиста; явно фиксирует, что membership больше не опрашивается на SEND.
- **`sendToForeignChatPassesInterceptorWithoutMembershipCheck`** — `SEND /app/chat/send/77` (тот же чужой чат, что раньше отклонялся) → теперь проходит именно интерцептор (без падения на этом слое), `membership.isMember(...)` не вызван. Зачем: фиксирует новое поведение после переноса — отказ по членству, если он случится, теперь должен прийти из `ChatBoxStompController`, а не отсюда; тест намеренно НЕ проверяет итоговый допуск/отказ рассылки, это зона ответственности `ChatBoxStompControllerTest`.
- **`sendTypingStatusPassesInterceptorWithoutMembershipCheck`** — `SEND /app/chat/user_statuses/77` → проходит интерцептор, `membership.isMember(...)` не вызван. Зачем: то же самое для второго SEND-адреса (статусы набора текста).
- **`subscribeToOwnChatPasses`** — `SUBSCRIBE /mutual/chat/5`, участник → проходит. Зачем: happy path подписки на свой чат.
- **`subscribeToForeignChatIsDenied`** — `SUBSCRIBE /mutual/chat/77`, не участник → `AccessDeniedException`. Зачем: раньше любой пользователь мог подписаться и читать чужую переписку; на SUBSCRIBE проверка членства сохранена.
- **`subscribeWithOverflowingChatIdIsDenied`** — `SUBSCRIBE /mutual/chat/99999999999999999999` (двадцать девяток, не влезает в `long`) → `AccessDeniedException`, `membership.isMember(...)` не вызван. Зачем: ветка `catch (NumberFormatException)` в `parseChatIdOrDeny` жива и достижима через SUBSCRIBE (на SEND `chatId` больше не парсится интерцептором, но SUBSCRIBE парсит его по-прежнему); прошлый рефакторинг (g9x) удалил одноимённый тест для SEND (`sendWithOverflowingChatIdIsDenied`), из-за чего эта ветка осталась без покрытия — тест возвращён под именем, отражающим актуальный путь.
- **`subscribeToForeignTypingChannelIsDenied`** — `SUBSCRIBE /mutual/typing/77`, не участник → `AccessDeniedException`. Зачем: индикатор набора текста в чужом чате — та же дыра, отдельный адрес.
- **`subscribeToGlobalImageChannelsPassesWithoutMembershipCheck`** — `SUBSCRIBE` на `/mutual/chat/image_chat_channel` и `/mutual/chat/image_message_channel` без настройки мока → оба проходят, `membership.isMember(...)` не вызван ни разу (`Mockito.verify(..., never())`). Зачем: **сторож ловушки таска** — под `/mutual/chat/` живут не только чаты, но и два глобальных картиночных канала (`chat.view.js:271-272`); наивная проверка членства сломала бы аватарки после деплоя.
- **`subscribeToUnknownNonNumericChatDestinationIsDenied`** — `SUBSCRIBE /mutual/chat/new_global_channel` (нечисловой хвост не из именного списка исключений) → `AccessDeniedException`. Зачем: **сторож от молчаливой дыры** — список глобальных каналов поимённый, а не «пропускать всё нечисловое»; иначе следующий добавленный в будущем канал автоматически оказался бы без проверки членства.
- **`perUserTypingDestinationOfAnotherUserIsDenied`** — `SUBSCRIBE /mutual/chatlist/typing/42` от лица `userId="9"` → `AccessDeniedException`. Зачем: `/mutual/chatlist/typing/` добавлен в `PER_USER_PREFIXES` этим таском — проверяет, что подписка на чужой per-user typing-канал отклоняется существующей `isPerUserDestination`-веткой.
- **`ownPerUserTypingDestinationPasses`** — `SUBSCRIBE /mutual/chatlist/typing/9` от лица того же `userId="9"` → проходит. Зачем: happy path для нового префикса, парный к предыдущему тесту.
- **`subscribeToMutualWildcardIsDenied`** — `SUBSCRIBE /mutual/**` → `AccessDeniedException`, `membership.isMember(...)` не вызван. Зачем: **C1** — воспроизводит атаку из финального ревью: `DefaultSubscriptionRegistry` хранит адрес подписки как Ant-шаблон и матчит его против каждого исходящего сообщения брокера, поэтому `/mutual/**` собрал бы себе все чужие чаты, typing-канал и chatlist-превью в обход префиксных проверок ниже по коду.
- **`subscribeToGlobalWildcardIsDenied`** — `SUBSCRIBE /**` → `AccessDeniedException`. Зачем: **C1**, второй вариант атаки — предельно широкий шаблон добирает ещё и `/private/**`, проверяет, что проверка шаблона не завязана на конкретный префикс `/mutual`.
- **`sendDirectlyToMutualChatBrokerAddressIsDenied`** — `SEND /mutual/chat/77` (не `/app/...`) → `AccessDeniedException`, `membership.isMember(...)` не вызван. Зачем: **C2** — центральный сценарий подделки авторства: такой фрейм не долетает ни до одного `@MessageMapping`, `SimpleBrokerMessageHandler` разослал бы его подписчикам чата 77 напрямую, как будто это легитимное сообщение.
- **`sendDirectlyToPrivateBrokerAddressIsDenied`** — `SEND /private/42` → `AccessDeniedException`. Зачем: **C2**, тот же обход бьёт и по личным уведомлениям чужого пользователя, не только по групповым чатам.
- **`sendToUnlistedAppDestinationPasses`** — `SEND /app/some/other/handler` (легитимный `/app/`-адрес вне двух проверяемых префиксов `send`/`user_statuses`) → проходит, `membership.isMember(...)` не вызван. Зачем: **регрессия аллоулиста** — во всём репозитории ровно два `@MessageMapping` (оба в `ChatBoxStompController`), поэтому тест не о существовании других обработчиков, а о том, что переход с чёрного списка на аллоулист `/app/*` не сужает пространство легитимных `/app/`-маршрутов до этих двух: любой будущий `/app/`-адрес, не входящий в проверяемые префиксы, обязан проходить, а не отклоняться.

### `GeminiServiceTest` — `unit` — GeminiService (миграция с Yandex GPT, beads 5fc)
Чистые методы `GeminiService` (замена `YandexGptService` после миграции AI-assist на Google Gemini API); `model`/`apiKey` подставляются через `ReflectionTestUtils`, сетевые вызовы не выполняются.

- **`buildCompletionRequestBody_usesModelFromConfig_andWiresSystemInstructionSeparately`** — вход: prompt от `BuildJsonPrompt`, схема `null`. Проверяет: `system_instruction.parts[0].text` = системная инструкция (уходит отдельным top-level полем, не элементом `contents`), `contents` = prompt.contents(), temperature 0.5, при `null`-схеме `responseMimeType` НЕ добавляется. Зачем: контракт тела запроса Gemini `generateContent` (system-инструкция отдельно от диалога — ключевое отличие от Yandex).
- **`buildCompletionRequestBody_withResponseSchema_addsStructuredOutputConfig`** — вход: непустая `responseSchema`. Проверяет: `generationConfig.responseMimeType = application/json` и `responseSchema` = переданная схема. Зачем: анти-фрод скоринг перешёл на structured output вместо regex-стрипа markdown.
- **`buildJsonPrompt_buildsOneContentTurnPerMessage_withUserRole`** — одно сообщение `alice: hi` → ровно 1 content-turn с `role: user` и текстом `alice: hi`. Зачем: контракт формата `contents` (каждое сообщение — свой turn).
- **`buildJsonPrompt_multipleMessagesFromSameUser_areNotCollapsedIntoLastOne`** — три сообщения одного юзера → три отдельных turn'а (`alice: first/second/third`). Зачем: **регрессия бага aliasing** — старый Yandex-код мутировал один и тот же `JsonObject` по ссылке, и все сообщения одного юзера схлопывались в последнее при сериализации.
- **`buildJsonPrompt_throwsOnEmptyInput`** — пустой ввод → `RuntimeException("Empty prompt")`.
- **`requireApiKey_blankKey_throwsClearErrorViaGetAssistantAnswer`** — blank `apiKey` → `IllegalStateException`, сообщение называет `GEMINI_API_KEY`. Зачем: явный фейл при отсутствии ключа (не тихий сбой), проверка идёт до `aiRequestMetric.increment()`.
- **`requireApiKey_nullKey_throwsClearErrorViaGetAssistantAnswer`** — `null` `apiKey` → `IllegalStateException`.
- **`buildSecurityCheckPrompt_wrapsBothFingerprintsInSingleUserTurn`** — два `ClientMeta` → ровно 1 content-turn с `role: user`, системная инструкция содержит методику (`secureUUID`). Зачем: контракт анти-фрод промпта (оба отпечатка в одном user-turn, методика — в system-инструкции).
- **`extractPlainText_pullsNestedTextOutOfFullGeminiEnvelope`** — вход: полный конверт ответа Gemini (`candidates[0].content.parts[0].text` = JSON-строка `{"reasoning":...,"probability":85}`). Проверяет: возвращает именно вложенную JSON-строку, не сам конверт. Зачем: **регрессия бага** — `aiSecurePredict()` раньше парсил `reasoning`/`probability` прямо из конверта (минуя `extractPlainText`), оба поля были `null`/кидали NPE при любом реальном (не замоканном) ответе Gemini — баг не проявлялся раньше только потому, что вызов падал ещё на этапе сериализации запроса (см. `fix/gemini-webclient-gson-jackson-mismatch`), обнаружен только после того фикса живым тестом 2026-08-01.
- **`extractPlainText_throwsOnEmptyBody`** — пустая строка → `RuntimeException` с "uncorrect struckture".

---

## MessegerParody

### `KafkaConsumerTest` — `unit` — парсинг топика Images (beads se2)
`KafkaConsumer.listenOauthImage` с замоканным `ImageUrlPersistenceService`.

- **`parsesUserimageContractAndDelegatesToPersistenceService`** — событие `userimage` → `persistImageUrl("userimage","42","userimage/42/uuid.png")`.
- **`parsesChatimageContractAndDelegatesToPersistenceService`** — событие `chatimage` → соответствующий вызов.
- **`ignoresExtraFieldsNotInContract`** — лишнее поле `extra` игнорируется, распарсенные три поля переданы. Зачем: устойчивость consumer к расширению контракта.

### `Services/ImageUrlPersistenceServiceTest` — `unit` — обе ветки персиста ключа (beads se2)
`ImageUrlPersistenceService` с замоканными `UserRepBase` (JPA) и `ReactiveChatRepository` (R2DBC).

- **`userimageBranchPersistsObjectKeyOnUser`** — `userimage` → у найденного User проставляется `imageUrl=objectKey`, `save`; чат-репозиторий не трогается.
- **`userimageBranchNoOpWhenUserMissing`** — юзера нет → `save` не вызывается (no-op, без краша).
- **`chatimageBranchPersistsObjectKeyOnChat`** — `chatimage` → у найденного чата проставляется `imageUrl`, `save`; user-репозиторий не трогается.
- **`chatimageBranchThrowsWhenChatMissing`** — чата нет → `RuntimeException`, `save` не вызывается. Зачем: не терять картинку молча для несуществующего чата.
- **`unknownTargetTypeThrows`** — неизвестный `targetType` → `RuntimeException`. Зачем: защита от неизвестных типов событий.
