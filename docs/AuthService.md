# AuthService

Java/Spring Boot сервис (порт 8081 HTTP, 9092 gRPC). Входная дверь пользователя в систему:
логинит через Google, ведёт таблицу пользователей и на каждый запрос к защищённым путям
подтверждает ingress'у, что access-токен ещё валиден. **Сам итоговую пару access+refresh JWT
не выпускает** — это делает `HTTPService` (см. «Взаимодействие с другими сервисами» ниже).

## Зона ответственности

- **OAuth-логин через Google** (`spring-security-oauth2-client`, authorization code flow):
  принимает fingerprint браузера на `/startauth`, редиректит на Google, обрабатывает callback
  на `/login/oauth2/code/google`, при первом входе создаёт `User` в Postgres и асинхронно
  загружает аватар из Google в MinIO, публикуя ссылку на объект в Kafka.
- **Мост к HTTPService для завершения логина**: после успеха у Google не отдаёт JWT напрямую —
  кладёт `sub` пользователя в Redis под одноразовый код (TTL 5 минут) и редиректит браузер на
  `/authcallback` (эндпоинт уже в `HTTPService`). Тот забирает `sub`/`role` через gRPC
  (`checkOneTimeCodeAndGetSubRole`) и только там минтит настоящую пару access+refresh JWT.
- **`GET /jwtcheck`** — эндпоинт, который nginx ingress дёргает через `auth_request` на *каждый*
  запрос к защищённым путям (`/api/*`, `/AiAssist`, `/reactive/createchat`, см.
  `Helm/templates/http-ingress.yaml`). Проверяет access-JWT публичным ключом (RS512) и
  существование `RefreshSession:<sid>` в Redis; в ответ отдаёт заголовки `X-User-ID`,
  `X-Authorities`, `X-Jti`, `X-Sid`, которые ingress прокидывает дальше в бэкенды. Сам эндпоинт
  и `/startauth` — единственные пути AuthService, открытые публично (`permitAll` в
  `SecurityConfiguration`).
- **gRPC-сервис `AuthTransferService`** (порт 9092) — источник правды о пользователях для
  `HTTPService`/`MessegerParody`: поиск/чтение пользователей, обмен одноразового кода на
  `sub/role`, обмен Google refresh-токена на access (для запросов к Google API от имени
  пользователя), плюс legacy `login`/`register` по паре имя/bcrypt-пароль — этот путь по-прежнему
  вызывается из `HTTPService` (`WEBFLUX_Service`) в обход Google OAuth.
- **Kafka — только продюсер**: объявляет топики `Messages`/`Events`/`Images` (RF=1) через
  `KafkaAdmin.NewTopics`. В `Images` публикует не байты, а ссылку на объект: при первом логине
  `CustomOAuth2UserService.Upload_image` сперва кладёт аватар в MinIO
  (`ImageStorageService.putObject`, ключ `userimage/<userId>/<uuid>.jpg`) и только после успешной
  записи шлёт `{"targetType":"userimage","targetId":<userId>,"objectKey":<key>}` — общий
  lowercase-контракт пайплайна картинок, тот же, что публикует `HTTPService` (см.
  `docs/HTTPService.md`, раздел «MinIO-пайплайн картинок»). Этот ивент вычитывает
  `MessegerParody` (`ImageUrlPersistenceService.persistImageUrl`), которая и прописывает
  `objectKey` в поле `imageUrl` — в общей с AuthService Postgres (`hybrid_db`). Сам AuthService
  своё поле `User.imageUrl` после загрузки не трогает: оно остаётся `"pending"` до этой записи
  извне. В коде сервиса нет ни одного `@KafkaListener` — сам он ничего не консьюмит.

## Ключевые компоненты

| Класс / файл | Что делает |
|---|---|
| `Main.java` | Точка входа Spring Boot; сканирует `JPA_Entities`/`Repositories` по явным пакетам. |
| `SecurityConfiguration` | `SecurityFilterChain`: STATELESS-сессии, CSRF выключен, `permitAll` только на `/startauth` и `/jwtcheck`, остальное — `authenticated`; подключает `oauth2Login` с кастомными success-handler'ом, authorized-client-сервисом и resolver'ом стейта. |
| `HttpService` | `POST /startauth` — принимает fingerprint/meta клиента, хэширует «подозрительные» поля, сохраняет привязку к state в Redis, подменяет URI запроса под `/oauth2/authorization/google` и возвращает фронту ссылку для редиректа на Google. |
| `CustomOAuth2UserService` | Override `DefaultOAuth2UserService.loadUser`: находит/создаёт `User` по имени из Google-профиля; при первом логине (`imageUrl` ещё `null`/`"pending"`) асинхронно скачивает аватар с Google (`GetImageAndConvertToB64`, `@Async`, виртуальный поток), затем `Upload_image` кладёт байты в MinIO через `ImageStorageService.putObject` и публикует в Kafka (`Images`) `{targetType, targetId, objectKey}` — без Base64, только ссылка на объект. |
| `MinioConfig` | Бин `MinioClient`: эндпоинт и креды из `MINIO_ENDPOINT`/`MINIO_ACCESS_KEY`/`MINIO_SECRET_KEY` (дефолты в коде — `http://minio:9000`/`minioadmin`/`minioadmin`, в кластере переопределены Helm'ом). |
| `ImageStorageService` (`Services/`) | Тонкая обёртка над `MinioClient.putObject`: перед записью идемпотентно создаёт бакет (`ensureBucket`, имя из `MINIO_BUCKET`, дефолт `images`). Сервис синхронный (AuthService — блокирующий Spring MVC, не WebFlux) — в отличие от одноимённого класса в `HTTPService`, который уводит блокирующие вызовы MinIO SDK на `Schedulers.boundedElastic()`. Умеет только `putObject`; отдачи картинок (`getObject`) здесь нет — наружу их отдаёт `HTTPService` (`GET /api/images/{*key}`). |
| `AuthSuccessHandler` | Хендлер успешного `oauth2Login`: кладёт `sub` в Redis под одноразовый код (`UserOneTimeCodeFastCheck...`, TTL 300s) и редиректит браузер на `/authcallback?state=...&code=...` (сам этот путь уже обслуживает `HTTPService`). |
| `JwtCheckController` | `/jwtcheck` — читает токен из `Authorization: Bearer` либо cookie `access`, проверяет подпись публичным ключом и наличие `RefreshSession:<sid>` в Redis, отдаёт identity-заголовки или 401. |
| `JwtKeyProvider` | Парсит `JWT_PUBLIC_KEY_PEM` (env) в `PublicKey` при старте. Только верификация — приватного ключа в этом сервисе нет и быть не должно. |
| `Auth_impl` (`Services/`) | gRPC `AuthTransferServiceImplBase`: CRUD/lookup пользователей, `login`/`register` (bcrypt), `checkAuthorization` (обмен Google refresh-токена на access через Google API), `checkOneTimeCodeAndGetSubRole` (обмен одноразового кода на `sub/role` для `HTTPService`). |
| `Oauth2Utils` | HTTP-клиент к Google (`userinfo`, `token` эндпоинты) — обмен refresh-токена на access и получение профиля. |
| `RedisOauth2AuthorizedClientService`, `RedisAuthorizationRequestRepository` | Переопределяют штатные in-memory реализации Spring Security OAuth2 Client, чтобы хранить `OAuth2AuthorizedClient` (токены самого Google, не наши JWT) и `OAuth2AuthorizationRequest` в Redis — сервис STATELESS, HTTP-сессии нет. |
| `ResolverForRefreshTokens` | Кастомный `OAuth2AuthorizationRequestResolver` — подставляет state, полученный от `StateResolver`, в authorization request к Google. |
| `StateResolver` | Генерирует state-токен и привязывает к нему fingerprint/meta клиента в Redis (TTL 5 минут); стримингово хэширует чувствительные поля фингерпринта (`geometry`, `webGlExtensions`, `text`) перед хранением/логированием. |
| `Auth_rep` / `User`, `Chat` (`JPA_Entities`) | Postgres: пользователи (`google_sub`, роль, `imageUrl`, bcrypt-пароль для legacy-логина) и связь `User`↔`Chat` (ManyToMany). Сущность `Message` удалена, но `Chat` и поле `User.chats` сохранены намеренно: от них зависит JPQL `JOIN u.chats c` в `Auth_rep.findAllByChatId`, обслуживающий живой gRPC-метод `get_all_users_by_chatid` (`Services/Auth_impl`). |
| `KafkaConfig` / `KafkaProducer` | Топик-админ (создаёт `Messages`/`Events`/`Images`) + обёртка над `KafkaTemplate` для публикации. |
| `MyPasswordEncoder`, `ReverseDnsResolver`, `AsyncConfiguration`, `InstTypeAdapter`, `OAuth2*DTO` | Вспомогательные утилиты: bcrypt, reverse DNS для fingerprint-меты в `/startauth`, виртуальные потоки для `@Async`-загрузки аватара, Gson-сериализация `Instant`/`OAuth2AuthorizedClient` для хранения в Redis. |

## Взаимодействие с другими сервисами

- **Ingress → `/jwtcheck`**: nginx `auth_request` на каждый запрос к `/api`, `/AiAssist`,
  `/reactive/createchat` (`Helm/templates/http-ingress.yaml`); ответ прокидывается как заголовки
  `X-User-ID`, `X-Authorities`, `X-Jti`, `X-Sid`. Публичные `/startauth` и
  `/login/oauth2/code/google` маршрутизируются на authservice напрямую, без `auth_request`.
- **AuthService не минтит финальную пару JWT** — только валидирует их публичным ключом
  (`/jwtcheck`) и выдаёт одноразовый код, который обменивается по gRPC
  (`checkOneTimeCodeAndGetSubRole`). Реальную выдачу/ротацию access+refresh делает `HTTPService`
  (`TokensResolver`, приватный ключ там же) — подробности в `docs/security-flow.md`.
- **gRPC `AuthTransferService`** (порт 9092) потребляется `HTTPService` (логин/регистрация,
  lookup юзеров, обмен кода/токенов) и `MessegerParody` (lookup юзеров/чатов).
- **Kafka**: продюсер в топик `Images` — после успешной загрузки аватара в MinIO публикует
  `{targetType:"userimage", targetId, objectKey}`; вычитывает `MessegerParody`
  (`ImageUrlPersistenceService`), которая и прописывает `objectKey` обратно в `User.imageUrl` в
  общей Postgres. Плюс топик-админ для `Messages`/`Events`/`Images`.
- **MinIO**: `ImageStorageService.putObject` пишет байты аватара в бакет `images`
  (`MINIO_BUCKET`) по ключу `userimage/<userId>/<uuid>.jpg`. Тот же бакет и тот же
  контракт использует `HTTPService` для аватаров при регистрации и картинок чатов —
  общий S3-совместимый сторедж на весь стек (см. `docs/HTTPService.md`).

## Конфигурация и секреты

Основа — `AuthService/src/main/resources/application.yml`, поверх — env-переменные, которые
пробрасывает `Helm/charts/authservice/templates/deployment.yaml` (значения по умолчанию —
`Helm/values.yaml`, ключ `authservice`), секретные — из `Helm/templates/secrets.yaml`.

| Переменная / свойство | Источник значения | Назначение |
|---|---|---|
| `GOOGLE_CLIENT_ID` | Helm value `authservice.google.clientId` (не секрет) | id OAuth-клиента Google. |
| `GOOGLE_CLIENT_SECRET` | Helm Secret (`secrets.googleClientSecret`) | секрет OAuth-клиента Google. |
| `JWT_PUBLIC_KEY_PEM` | Helm value `authservice.env.jwtPublicKey` (PEM целиком, не секрет) | публичный ключ RS512 для проверки access-JWT в `/jwtcheck`; парсится `JwtKeyProvider`, падает при старте если пусто/плейсхолдер. В dev (`deploy-kind.sh`) генерируется одноразовая RSA-пара и тем же ключом наполняется `httpservice.env.jwtPublicKey`. |
| `DB_HOST` / `DB_PORT` / `DB_NAME` / `DB_USER` / `DB_PASSWORD` | Helm values + Secret (`DB_PASSWORD`) | Postgres-подключение; в `application.yml` заданы fallback-дефолты (`postgres:5432/hybrid_db`, `postgres`/`postgres123`) — они рассчитаны на запуск внутри кластера (хост `postgres` — k8s Service), не на localhost. |
| `spring.data.redis.url` | Захардкожено в `application.yml` (`redis://redis:6379/0`) | Redis для `RefreshSession:*`, одноразовых кодов, OAuth2 authorization requests/clients. Не параметризовано env, хотя Helm-деплоймент дополнительно прокидывает `SPRING_REDIS_HOST`/`SPRING_REDIS_PORT` — они не используются текущим биндингом. |
| `SPRING_KAFKA_BOOTSTRAP_SERVERS` | Helm value `authservice.env.kafkaBootstrap` | `spring.kafka.bootstrap-servers`. |
| `SPRING_KAFKA_CONSUMER_GROUP_ID` | Helm value `authservice.env.kafkaConsumerGroup` | пробрасывается деплойментом, но в коде сервиса нет `@KafkaListener` — сейчас не влияет на поведение (сервис только продюсер). |
| `MINIO_ENDPOINT` | Helm value `authservice.env.minioEndpoint` (не секрет) | S3-совместимый эндпоинт MinIO для `MinioConfig` (`http://minio:9000` в кластере; тот же дефолт зашит в коде). |
| `MINIO_BUCKET` | Helm value `authservice.env.minioBucket` (не секрет, дефолт `images`) | бакет для аватарок, читает `ImageStorageService`; создаётся идемпотентно при первой записи. |
| `MINIO_ACCESS_KEY` / `MINIO_SECRET_KEY` | Helm Secret (`secrets.minioAccessKey`/`secrets.minioSecretKey`) | креды приложения к MinIO (не root-креды сервера); тот же Secret использует `HTTPService`. |
| `grpc.server.port` | Захардкожено в `application.yml` (`9092`) | порт gRPC-сервера `AuthTransferService`. |

`redirect-uri` для Google OAuth параметризован через `INGRESS_HOST` (Helm value
`global.ingressHost`, прокидывается деплойментом `authservice`) и по умолчанию
собирается как `http://${INGRESS_HOST:myapp.localtest.me}/login/oauth2/code/google`.
`myapp.localtest.me` выбран вместо `myapp.local`, потому что Google Cloud Console
отклоняет `.local` как redirect URI ("must end with a public top-level domain") —
`localtest.me` резолвится на `127.0.0.1` через публичный DNS и не требует записи
в `/etc/hosts`.

## Как запускать и тестировать локально

Отдельного docker-compose-режима в проекте нет — полноценный прогон AuthService возможен только
как часть всего стека:

```bash
./deploy-kind.sh   # поднимает kind, собирает `docker build -t authservice:latest AuthService`,
                    # грузит образ в кластер и раскатывает весь Helm-релиз
```

Юнит-тесты не требуют инфраструктуры (Redis замокан, `Testcontainers` в зависимостях нет).
Единственный тестовый класс — `AuthService/src/test/java/.../JwtCheckControllerTest.java`
(6 тестов на `/jwtcheck`: валидный токен, отсутствующая refresh-сессия, истёкший токен, битый
токен, отсутствующий токен, токен без `sid`).

```bash
cd AuthService
JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn -q test
```

**Важно**: дефолтный JDK на машине может быть 25-й — он ломает Mockito, тесты нужно гонять
именно под JDK 21 (`maven.compiler.release=21` и в `pom.xml`). Тот же принцип — в команде
`/jtest`.

Полная сборка (тоже под JDK 21):

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn clean package
```
