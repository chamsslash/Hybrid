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
| HTTPService | 12 | 46 | 2 | 48 |
| MessegerParody | 2 | 8 | 0 | 8 |
| **Итого** | **19** | **71** | **4** | **75** |

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
