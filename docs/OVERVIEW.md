# Overview

Мессенджер (чаты, real-time сообщения, картинки, AI-ассист), развёрнутый как три Spring Boot
сервиса поверх Kubernetes/Helm. Это точка входа в архитектуру для возвращающегося автора —
без построчного разбора кода, только сервисы, потоки данных и деплой в текущем виде.

## Сервисы

Три top-level Java-модуля в корне репозитория:

- **`AuthService/`** — Google OAuth-логин (Spring Security OAuth2 Client), выдача
  одноразового кода авторизации (Redis `UserOneTimeCodeFastCheck*`), эндпоинт `/jwtcheck`,
  который ingress вызывает через `auth_request` для проверки access-JWT на каждый защищённый
  запрос. При первом логине Google-юзера сам грузит его аватарку в MinIO и публикует событие
  в Kafka-топик `Images` (см. Image-flow ниже). Подробности:
  [`docs/AuthService.md`](AuthService.md).
- **`HTTPService/`** — основной пользовательский сервис: HTTP API (`/api/*`), SPA-шеллы,
  STOMP/WebSocket, обмен одноразового кода на access/refresh-JWT (`/authcallback`,
  `/exchangeTokens`), загрузка картинок в MinIO, AI-ассист (Gemini). Подробности:
  [`docs/HTTPService.md`](HTTPService.md).
- **`MessegerParody/`** — владелец БД (Postgres, Liquibase-миграции), gRPC-сервер
  `ReactiveTransferService` (чаты/сообщения/пользователи), Kafka-консюмер топика `Images`
  (персистит `objectKey` картинок в БД). Подробности:
  [`docs/MessegerParody.md`](MessegerParody.md).

Инфраструктура объявлена в `Helm/` (umbrella chart + подчарты `authservice`/`httpservice`/
`messegerparody` в `Helm/charts/`) и поднимается как отдельные Deployment/Service:

- **Kafka** (`Helm/templates/kafka.yaml`) — single-node KRaft (`apache/kafka:3.9.0`),
  используемые топики — `Messages` и `Images`. Геометрию топиков (список и число
  партиций) владеет чарт: `Helm/values.yaml: kafka.createTopics` /
  `kafka.partitionsPerTopic` читают джоба `Helm/templates/kafka-topics-job.yaml`
  (создаёт топики явно на хуке post-install/post-upgrade) и `KAFKA_NUM_PARTITIONS`
  брокера. Auto-create топиков оставлен включённым намеренно: Spring Kafka заводит на
  лету retry/DLT-топики MessegerParody, чьи имена выводятся из `@RetryableTopic`
  (beads lo2). Данные брокера лежат на PVC `kafka-data` (`kafka.persistence.*` в
  `Helm/values.yaml`, дефолтный StorageClass кластера), поэтому пересоздание пода
  брокера сохраняет топики, офсеты консьюмер-групп и неразобранные сообщения
  (beads y1v). Каталог логов задан явно через `KAFKA_LOG_DIRS`: entrypoint
  `apache/kafka` собирает `server.properties` только из переменных `KAFKA_*`, и без
  неё брокер писал бы в свой встроенный дефолт `/tmp/kafka-logs` мимо тома.
  Deployment стоит со `strategy: Recreate` — том `ReadWriteOnce` не переживёт двух
  подов сразу. `kafka.persistence.enabled: false` возвращает старое поведение
  (`emptyDir`, всё теряется при рестарте) для эфемерных стендов.
- **Postgres** (`Helm/templates/postgres.yaml`) — `postgres:15`, база `hybrid_db`, схема
  накатывается Liquibase-джобом из `messegerparody`.
- **Redis** (`Helm/templates/redis.yaml`) — `redis:7`, хранит refresh-сессии/fingerprint
  (AuthService, HTTPService) и OAuth one-time-code (AuthService).
- **MinIO** (`Helm/templates/minio.yaml`) — S3-совместимое хранилище картинок, бакет
  `images`; отдельная post-install/upgrade Job (`minio/mc`) создаёт бакет и выделенного
  app-пользователя (не root-креды). Потребители — HTTPService (весь пользовательский
  image-flow) и AuthService (аватарки Google-юзеров при логине), оба получают
  `MINIO_ENDPOINT`/`MINIO_BUCKET`/`MINIO_ACCESS_KEY`/`MINIO_SECRET_KEY` из одного и того же
  Secret.
- **Prometheus / Grafana** (`Helm/templates/prometheus.yaml`, `grafana.yaml`) — метрики и
  дашборды, оба переключаемы через `infra.prometheus`/`infra.grafana` в `values.yaml`.

Все инфра-компоненты и все три сервиса включаются/выключаются через флаги в
`Helm/values.yaml: infra.*` и `<service>.enabled`.

## Топология деплоя

Единственный режим — **Kubernetes через Helm** (локально — `kind`, через `deploy-kind.sh`).
Docker-compose и nginx как отдельно поднимаемый reverse-proxy в репозитории не существуют —
входная точка HTTP-трафика это k8s `Ingress` (`nginx-ingress` controller,
`Helm/templates/http-ingress.yaml`):

### Граф сервисов и инфраструктуры

```mermaid
graph LR
    Client["Браузер<br/>(SPA)"]
    Ingress["nginx-ingress<br/>контроллер"]
    AuthSvc["AuthService<br/>:8081"]
    HTTPSvc["HTTPService<br/>:8080"]
    MsgSvc["MessegerParody<br/>:5000"]
    
    Kafka["Kafka<br/>(KRaft)"]
    Redis["Redis"]
    Postgres["Postgres<br/>hybrid_db"]
    MinIO["MinIO<br/>bucket: images"]
    
    subgraph "k8s Ingress"
        Ingress
    end
    
    subgraph "Сервисы"
        AuthSvc
        HTTPSvc
        MsgSvc
    end
    
    subgraph "Инфраструктура"
        Kafka
        Redis
        Postgres
        MinIO
    end
    
    Client -->|"HTTP/HTTPS"| Ingress
    Ingress -->|"/startauth,<br/>/login/oauth2"| AuthSvc
    Ingress -->|"/api, /<br/>/AiAssist"| HTTPSvc
    
    AuthSvc -->|"auth_request<br/>/jwtcheck"| AuthSvc
    AuthSvc -->|"gRPC<br/>AuthTransferService"| HTTPSvc
    AuthSvc -->|"Publish Images<br/>контракт"| Kafka
    AuthSvc -->|"Redis<br/>one-time-code"| Redis
    AuthSvc -->|"PUT object<br/>userimage"| MinIO
    
    HTTPSvc -->|"gRPC<br/>ReactiveTransferService"| MsgSvc
    HTTPSvc -->|"Publish Images,<br/>Messages"| Kafka
    HTTPSvc -->|"Subscribe Events,<br/>Images"| Kafka
    HTTPSvc -->|"Session,<br/>Chat context"| Redis
    HTTPSvc -->|"PUT/GET object"| MinIO
    
    MsgSvc -->|"Schema,<br/>Queries"| Postgres
    MsgSvc -->|"Subscribe Images<br/>контракт"| Kafka
    
    style Ingress fill:#f9f,stroke:#333
    style AuthSvc fill:#bbf,stroke:#333
    style HTTPSvc fill:#bbf,stroke:#333
    style MsgSvc fill:#bbf,stroke:#333
    style Kafka fill:#fbb,stroke:#333
    style Redis fill:#bfb,stroke:#333
    style Postgres fill:#bfb,stroke:#333
    style MinIO fill:#bfb,stroke:#333
```

- `http-public` — публичные пути без `auth_request`: `/startauth`, `/login/oauth2/code/google`
  (оба на `authservice:8081`) и `/` (SPA-шеллы, статика, SockJS-хендшейки — на
  `httpservice:8080`).
- `http-protected` — `/api`, `/AiAssist`, `/reactive/createchat` (на `httpservice:8080`),
  защищены аннотацией `nginx.ingress.kubernetes.io/auth-url` → `authservice:8081/jwtcheck`.
  `auth_request` отвечает за **ревокацию** (жива ли refresh-сессия по `sid` в Redis);
  **личность** backend берёт сам из подписи access-JWT, а не из прокинутых
  `X-User-ID`/`X-Authorities` (beads 1fs, см. `docs/security-flow.md`).

`deploy-kind.sh` собирает образы сервисов, генерирует одноразовые dev-ключи, ставит chart в
kind-кластер; секреты — либо из gitignored `.env` (см. `README.md`), либо через
`helm --set`/`--set-file` (см. `README.md: Providing secrets at deploy time`).

## Потоки данных

### Auth-flow (кратко)

1. `AuthService` проводит Google OAuth-хендшейк и по успеху кладёт в Redis одноразовый код
   (`AuthSuccessHandler`), редиректя на `HTTPService: /authcallback`.
2. `HTTPService` (`MVC_Service`) меняет код на `sub`/`role` через gRPC
   (`AuthTransferService.CheckOneTimeCodeAndGetSub_Role`) и сам выпускает пару JWT:
   короткий access (в памяти SPA) + длинный refresh (HttpOnly cookie), заводит
   `RefreshSession:<sid>` в Redis.
3. Каждый запрос к `/api/*` несёт `Authorization: Bearer <access>`; ingress проверяет его
   через `auth_request` → `AuthService /jwtcheck`, backend доверяет проставленным заголовкам
   и сам JWT не валидирует (кроме STOMP CONNECT — там HTTPService валидирует access-JWT сам,
   т.к. SockJS-хендшейк не несёт заголовков).

Полная схема (refresh-ротация, fingerprint, Redis-структуры, STOMP-аутентификация):
[`docs/security-flow.md`](security-flow.md).

### Message-flow

- `HTTPService` — единственный producer чат-сообщений в Kafka: `KafkaProducer.send()`
  публикует в топик `Messages` при каждом STOMP `/chat/send/{chatId}`
  (`ChatBoxStompController.HandleChatMessage`). У топика **нет активного консюмера** —
  единственный `@KafkaListener(topics = "Messages")` закомментирован
  (`HTTPService/.../KafkaConsumer.java`). Реальная доставка сообщения подписчикам чата идёт
  напрямую через STOMP `@SendTo("/mutual/chat/{chatId}")`, а не через Kafka.
- Топик `Events` — HTTPService слушает (`KafkaConsumer.listenNotifications`) и форвардит
  уведомления (`MessageCreated`, `ChatCreated`) в STOMP-каналы чата/списка чатов. Продюсер
  топика `Events` в текущем коде трёх сервисов не найден — топик объявлен
  (`AuthService/KafkaConfig`), но producer не идентифицирован по коду; при работе с этим
  потоком проверять заново.
- Краткий контекст последних сообщений чата кешируется в Redis (`ChatContextService`) — это
  источник контекста для AI-ассиста, а не механизм персистентности.

### Image-flow

Единый контракт события в топике `Images` — `{targetType, targetId, objectKey}` (без
Base64: тело картинки всегда лежит в MinIO, в Kafka идёт только ссылка на объект). У этого
контракта два независимых продюсера:

#### Диаграмма потока картинок

```mermaid
sequenceDiagram
    participant User as Пользователь
    participant HTTPSvc as HTTPService
    participant MinIO
    participant Kafka as Kafka: Images
    participant MsgSvc as MessegerParody
    participant STOMP as STOMP (live)
    
    rect rgb(200, 220, 255)
        Note over HTTPSvc,STOMP: Пользовательская загрузка (регистрация, создание чата)
        User->>HTTPSvc: POST /reactive/upload<br/>(targetType, file)
        HTTPSvc->>MinIO: putObject(key, bytes)<br/>key=targetType/targetId/uuid.ext
        MinIO-->>HTTPSvc: success
        HTTPSvc->>Kafka: publish({targetType,<br/>targetId, objectKey})
    end
    
    rect rgb(220, 200, 255)
        Note over HTTPSvc,MsgSvc: Google OAuth: первый логин новоего юзера
        HTTPSvc->>HTTPSvc: CustomOAuth2UserService
        HTTPSvc->>MinIO: putObject(key, jpeg)<br/>userimage/userId/uuid.jpg
        MinIO-->>HTTPSvc: success
        HTTPSvc->>Kafka: publish({targetType=userimage,<br/>targetId=userId, objectKey})
    end
    
    rect rgb(200, 255, 200)
        Note over MsgSvc,STOMP: Два независимых консюмера
        Kafka->>MsgSvc: listenOauthImage()
        MsgSvc->>MsgSvc: ImageUrlPersistenceService<br/>(персист objectKey в БД)
        
        Kafka->>HTTPSvc: listenImagesEvents()
        HTTPSvc->>STOMP: форвард в каналы<br/>(/mutual/chat/image_*)
        STOMP->>User: live-update UI
    end
    
    rect rgb(255, 240, 200)
        Note over User,MinIO: Отдача картинки клиенту
        User->>HTTPSvc: GET /api/images/{key}
        HTTPSvc->>MinIO: getObject(key)
        MinIO-->>HTTPSvc: byte[]
        HTTPSvc-->>User: byte[] + Cache-Control
    end
```

1. `HTTPService` — пользовательская загрузка картинки (регистрация профиля →
   `targetType=userimage`, создание чата → `targetType=chatimage`): кладёт файл в MinIO через
   `ImageStorageService.putObject` по ключу `<targetType>/<targetId>/<uuid>.<ext>` в бакете
   `images` (`WEBFLUX_Service.Upload_image`), затем публикует событие.
2. `AuthService` — аватарка нового Google-юзера при первом OAuth-логине: скачивает картинку по
   `picture`-URL из Google-профиля, конвертирует в JPEG, кладёт в MinIO по ключу
   `userimage/<userId>/<uuid>.jpg` через свой (собственный, минимальный) `ImageStorageService`
   и публикует событие с `targetType=userimage`
   (`CustomOAuth2UserService.Upload_image`). Использует тот же `MinioConfig`/бакет `images`, что
   и HTTPService — оба сервиса читают одни и те же `MINIO_*` переменные из общего Secret.

Оба продюсера пишут в один и тот же топик один и тот же формат события, поэтому у него два
независимых консюмера:

- `MessegerParody` (`KafkaConsumer.listenOauthImage`) — персистит `objectKey` в БД через
  `ImageUrlPersistenceService`.
- `HTTPService` (`KafkaConsumer.listenImagesEvents`) — форвардит событие в STOMP
  (`ChatBoxStompController`/`ChatListStompController`) для live-обновления UI без reload.

Отдача картинки клиенту — `GET /api/images/{key}` (`ApiController.image`, ключ с слэшами через
`{*key}`), стримит байты из MinIO с кэш-заголовком `max-age=30d`. Этот путь есть только в
HTTPService — AuthService картинки клиентам не отдаёт, только загружает при логине.

## Стек

- **HTTPService** — смешанный WebFlux + Spring MVC (`spring-boot-starter-web` и
  `spring-boot-starter-webflux` оба в `pom.xml`): `WEBFLUX_Service`/`WebFluxConfig` для
  реактивных путей (upload картинок, `/api/*`, AI-ассист), `MVC_Service`/`MvcConfig`/
  `MvcSecurityConfig` для блокирующих (auth-callback, exchangeTokens, SPA-шеллы).
- **gRPC между сервисами**: `AuthTransferService` (сервер — `AuthService`, реализация
  `Auth_impl`; клиенты — `HTTPService`: `AuthGrpc.java`) и `ReactiveTransferService` (сервер
  — `MessegerParody`, реализация `ReactiveImpl`; клиент — `HTTPService`:
  `ReactiveGrpcClient`/`ReactiveStubGen`). Proto лежат в `AuthService/src/main/proto/` и
  `*/src/Grpcs/proto/` (`Common/DataTransferService.proto`,
  `Reactor/ReactiveTransferService.proto`) — у каждого сервиса своя копия контракта, не общий
  модуль.
- **Postgres + Liquibase** — единственный владелец схемы БД — `MessegerParody`
  (`src/main/resources/db/changelog/db.changelog-master.yaml`), миграции применяются отдельным
  k8s Job (`Helm/charts/messegerparody/templates/liquibase-job.yaml`) перед стартом сервиса.
- **Redis** — refresh-сессии и fingerprint-метаданные (`RefreshSession:<sid>`, `user:<sub>`),
  OAuth one-time-code, короткий контекст чата для AI-ассиста.
- **MinIO** — S3-совместимое хранилище картинок. Доступ из двух сервисов, каждый со своим
  бином `MinioClient` и своей копией `ImageStorageService` (общего модуля нет): `HTTPService`
  — полный пользовательский image-flow, `AuthService` — только загрузка Google-аватарки при
  логине. Endpoint, бакет и креды приходят из одного и того же k8s Secret через `MINIO_*`
  переменные окружения в обоих Deployment.

## Дальше

- [`docs/AuthService.md`](AuthService.md) — зона ответственности, конфиг, запуск/тесты
  AuthService.
- [`docs/HTTPService.md`](HTTPService.md) — SPA-auth, MinIO-пайплайн, AI-ассист, Kafka/STOMP
  роли HTTPService.
- [`docs/MessegerParody.md`](MessegerParody.md) — БД/gRPC/Kafka-консюмер, Liquibase, удалённый
  мёртвый код.
- [`docs/security-flow.md`](security-flow.md) — полная схема access/refresh/fingerprint и
  STOMP-аутентификации.
- [`docs/kind-stand-clock.md`](kind-stand-clock.md) — часы VM и задержка сообщений: почему
  разъехавшееся время в VM выглядит как потеря сообщений чата, как это отличить и вылечить.
- [`docs/tls-cert-manager.md`](tls-cert-manager.md) — включение TLS на стенде и цена отката.
