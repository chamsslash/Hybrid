# MessegerParody

Сервис-владелец базы данных мессенджера. Не имеет HTTP API для внешних клиентов
(HTTP-порт 8082 поднят Spring Boot'ом, но REST-контроллеров в сервисе нет) — с
ним общаются другие сервисы по gRPC, а также он сам слушает Kafka. Maven
artifactId — `DatabaseModule` (исторический, не переименовывался при
переименовании модуля в `MessegerParody`).

## Зона ответственности

- **Владелец схемы Postgres** (`users`, `chat`, `message`, `user_chat`) и её
  Liquibase-миграций.
- **gRPC-сервис `ReactiveTransferService`** — единственная точка чтения/записи
  чатов, сообщений и пользователей для других сервисов (в первую очередь
  HTTPService).
- **Kafka-консюмер топика `Images`** — применяет к БД события об успешно
  загруженных в MinIO картинках (пользовательских и чатовых аватарках).

У сервиса нет собственного gRPC-клиента к другим сервисам: `Main.java`
подключает `GrpcClientAutoConfiguration` и смежные auto-конфиги, но ни одного
`@GrpcClient`-стаба в коде нет — это только серверная сторона gRPC.

## Доступ к данным: JPA vs R2DBC

В сервисе одновременно живут два стека доступа к Postgres — это не случайность,
а разделение по путям использования:

- **JPA (блокирующий).** Ровно одна JPA-сущность — `JPA_Entities/User.java` —
  и ровно один JPA-репозиторий — `JPA_Repositories/UserRepBase.java`.
  Используется в `ImageUrlPersistenceService`, для записи `image_url`
  пользователя, когда Kafka-событие приходит с `targetType=userimage`.
  `ChatRepBase`, `MessageRepBase` и вспомогательный класс `R2DBC_to_JDBC`
  (пакет `JPA_Entities/RowsMappers`) были мёртвым кодом (объявлены, но нигде
  не инжектились) и удалены в ветке `fix/schema-source-of-truth`.
- **R2DBC (реактивный), пакеты `JPA_Entities` (`r2dbc_*`) /
  `R2DBC_Repositories`.** Основной путь для gRPC (`ReactiveImpl` целиком
  работает через `ReactiveRepository`, `ReactiveUserRepository`,
  `ReactiveChatRepository`, `ReactiveUserChatRepository`) и для записи
  `image_url` чата в Kafka-консюмере (`targetType=chatimage` →
  `ReactiveChatRepository`, с явным `.block()`, так как консюмер синхронный).

`ReactiveRepository` — кастомный DAO поверх `DatabaseClient` с ручными SQL-
запросами (нет Spring Data derived queries для сложных выборок вроде «чаты
пользователя, отсортированные по последней активности»).

## gRPC: `ReactiveTransferService`

Определён в `MessegerParody/src/Grpcs/proto/Reactor/ReactiveTransferService.proto`,
реализация — `Services/ReactiveImpl.java` (`@GrpcService`, порт 9091, задаётся
`grpc.server.port` в `application.yml`). Методы:

- `GetAllUsersByChatId`, `getallchatsbyid`, `transferAllMessages`, `getnewest` —
  чтение чатов/сообщений/пользователей.
- `transferchat` — создание чата или возврат существующего (поиск по
  названию + точному набору участников через `findChatByTitleAndExactUsers`).
- `GetUsernameById`, `getimageurl`, `getUserImageurl` — точечные lookup'ы.

Общие типы сообщений (`ChatData`, `Message`, `User`, `DriveUrl` и т. д.)
описаны в `Grpcs/proto/Common/DataTransferService.proto`. Тот же файл содержит
`service AuthTransferService` — это контракт AuthService, MessegerParody его не
реализует, только переиспользует общие message-типы.

Имя сообщения `DriveUrl` (и его неиспользуемое поле `type`) — тоже наследие
эпохи Google Drive, как и артефакт `DatabaseModule`: `getimageurl` и
`getUserImageurl` кладут в него `url` (короткий MinIO object key, не сам URL)
и `chat_id`, поле `type` в `ReactiveImpl` нигде не выставляется.

## Kafka

- **Consumer**, `KafkaConsumer.java`: слушает два топика — `Images` и
  `Messages` (consumer group `messegerparody-consumer` на оба). Топик `Events`
  в сервисе не используется — его читает HTTPService (см.
  `docs/HTTPService.md`), в MessegerParody для него нет ни листенера, ни
  упоминаний.
- **Топики сервис не создаёт**: `spring.kafka.admin.auto-create: false`
  (beads lo2). `@RetryableTopic` по умолчанию объявляет `NewTopic` не только на
  retry/DLT, но и на главные топики листенеров — с ОДНОЙ партицией; выигрывая
  гонку у джобы `kafka-topics`, такой `KafkaAdmin` создавал однопартиционный
  `Messages`, и консьюмер держал только партицию 0 до истечения
  `metadata.max.age.ms` (~5 минут), теряя на это время сообщения с партиций
  1..4. Геометрию задаёт чарт (`kafka.createTopics`/`kafka.partitionsPerTopic`),
  а retry/DLT-топики по-прежнему появляются на лету — через auto-create на
  брокере, у которого `num.partitions` приравнен к тому же значению. Отсюда
  жёсткая связка: выключить auto-create на брокере, не перечислив retry/DLT
  явно, значит молча сломать DLT-путь.
- Контракт события `Images` (см. комментарий в `KafkaConsumer.java`):
  ```json
  { "targetType": "userimage"|"chatimage", "targetId": "<id>", "objectKey": "<key>" }
  ```
  Обработка делегирована в `Services/ImageUrlPersistenceService.persistImageUrl`,
  которая по `targetType` пишет `objectKey` (короткий MinIO object key, не URL)
  либо в `User.imageUrl` (через JPA `UserRepBase`), либо в `r2dbc_chat.imageUrl`
  (через R2DBC `ReactiveChatRepository`).
- Контракт события `Messages` (`listenChatMessages`, зеркало
  `HTTPService/StompHandlers/ChatMessageDTO` — сервисы не шарят Java-классы,
  только имена JSON-полей):
  ```json
  { "type": "message", "message_id": "<uuid>", "chat_id": "<id>",
    "user_id": "<id>", "username": "<имя>", "timestamp": "<ISO-8601>",
    "text": "<текст>", "imageurl": "<key|null>" }
  ```
  Всё, что влияет на запись в БД, проставляет сервер в
  `ChatBoxStompController.HandleChatMessage`, а не клиент (инвариант beads
  g9x). Обработка — `ReactiveRepository.insertMessage`, синхронно
  (`.block(Duration.ofSeconds(15))`, таймаут обязателен: без него исчерпанный
  r2dbc-пул навсегда остановил бы `poll()` единственного потока контейнера и
  выбил бы консьюмер из группы). `@RetryableTopic(attempts = "7")` с
  экспоненциальным backoff, после исчерпания — топик `Messages-dlt`;
  детерминированные ошибки разбора (`NumberFormatException`,
  `DateTimeParseException`, `NullPointerException`, `JsonSyntaxException`)
  через `exclude` уходят в DLT сразу, без шести бессмысленных ретраев.
- **Идемпотентность вставки** (beads myl). Гарантия Kafka здесь at-least-once:
  под, убитый после коммита в Postgres, но до коммита офсета; ребаланс группы
  по таймауту `poll`; обрыв на ответе драйвера с последующим ретраем — каждый
  сценарий переигрывает уже записанное сообщение. Отличить повтор по
  содержимому нельзя (два одинаковых сообщения подряд от одного человека —
  нормальный сценарий), поэтому естественный ключ приходит снаружи:
  `message_id` генерирует HTTPService один раз, до рассылки, и одно и то же
  значение уходит и в realtime-эхо, и в Kafka. `insertMessage` вставляет с
  `ON CONFLICT (message_id) DO NOTHING` — переигровка становится no-op.
  Записи **без** `message_id` (бэклог топика, сделанный до этой ветки)
  вставляются по-старому, с `log.warn`: защиты от дублей для них нет и быть не
  может — сгенерированный на консьюмере id был бы на каждой переигровке новым.
- **Producer**, `KafkaProducer.java`: тонкая обёртка `send(String event)` над
  `KafkaTemplate`, публикует в топик `Images`. Судя по коду сервиса, реально не
  вызывается ни из одного места в MessegerParody — producer существует как
  API, но активного вызывающего кода в этом сервисе нет (событие в `Images`
  публикует HTTPService после загрузки в MinIO).

## Liquibase-миграции

Master-changelog: `src/main/resources/db/changelog/db.changelog-master.yaml`,
подключает baseline `changes/v1/` и релиз `changes/v2/` — организация по
релизам: `v1/` заморожен, его файлы не правятся (changeSet'ы уже применены на
существующих БД, правка сломала бы их checksum), новое едет отдельным файлом в
следующем каталоге. Пять файлов, 11 changeSet'ов суммарно:

1. `changes/v1/001-create-users.yaml` (1 changeSet) — создаёт `users`. Раньше
   эту таблицу заводил Hibernate через `spring.jpa.hibernate.ddl-auto: update`
   в AuthService; теперь Liquibase — единственный владелец, а Hibernate в
   обоих JPA-сервисах (AuthService, MessegerParody) переведён в `validate` и
   прав на DDL не имеет.
2. `changes/v1/002-create-chat.yaml` (1 changeSet) — создаёт `chat`.
3. `changes/v1/003-create-message.yaml` (3 changeSet'а) — создаёт `message`
   (колонка `user_id`, не `user_id_id` — старое имя было артефактом
   Hibernate-нейминга для `@ManyToOne User user_id` в удалённой сущности
   `Message.java`; `time_stamp` сразу `TIMESTAMP WITH TIME ZONE`, так как
   откатывать её в `VARCHAR` больше некому — ни на `message`, ни где-либо ещё
   в дереве нет `@Entity`, которая бы на неё отображалась) и FK на
   `users`/`chat`.
4. `changes/v1/004-create-user-chat.yaml` (4 changeSet'а) — создаёт
   `user_chat`, составной PK и FK на `users`/`chat`.
5. `changes/v2/001-message-idempotency.yaml` (2 changeSet'а, beads myl) —
   добавляет `message.message_id VARCHAR(64)` и уникальный индекс
   `ux_message_message_id`. Колонка **nullable** намеренно: у строк, записанных
   до миграции, и у записей из бэклога топика идентификатора нет и взяться ему
   неоткуда, а в Postgres уникальный индекс допускает сколько угодно NULL — то
   есть легаси-строки не конфликтуют друг с другом, просто не защищены от
   дублей. `VARCHAR`, а не `UUID`: в БД это непрозрачный идентификатор,
   происхождение которого знает только HTTPService.

Ни один из changeSet'ов **не** имеет `preConditions` — это осознанное решение,
не упущение: `preConditions` дали бы `MARK_RAN` на уже существующей легаси-
схеме (с `user_id_id`, `VARCHAR` вместо `TIMESTAMPTZ`) и воспроизвели бы
исходный баг вместо его устранения. Из этого есть прямое эксплуатационное
следствие — см. «Переход на baseline v1: обязательный сброс БД» ниже.

**Как реально накатываются миграции:** основной под сервиса стартует с
`spring.liquibase.enabled: false` (`application.yml`) — Liquibase в нём
выключен. Миграции применяет **отдельный Helm-job**
(`Helm/charts/messegerparody/templates/liquibase-job.yaml`, хуки
`post-install,pre-upgrade`), который поднимает тот же образ с
`--spring.profiles.active=liquibase`. Профиль `liquibase` подключает
`application-liquibase.yml` (`spring.liquibase.enabled: true`) и активирует
бин `config/LiquibaseExitOnReady`, который по `ApplicationReadyEvent`
завершает процесс (`SpringApplication.exit` + `System.exit(0)`) — то есть джоба
именно "применить миграции и выйти", а не постоянно живущий под. Джоба
предваряется init-контейнером, ждущим `pg_isready`.

### Переход на baseline v1: обязательный сброс БД

Если под postgres в кластере уже жив со старыми данными (легаси-схема до
этой ветки), `pre-upgrade`-джоба Liquibase упадёт: `changes/v1/001-create-users.yaml`
выполнит `CREATE TABLE users`, получит `relation "users" already exists"` (это
ожидаемо — changeSet'ы намеренно без `preConditions`, см. выше),
`backoffLimit: 1` джобы исчерпается, и `helm upgrade` завершится с ошибкой.
`deploy-kind.sh` при этом ретраит `helm upgrade` трижды с сообщением "likely
ingress-webhook race" — это маскирует настоящую причину, если она в этом.

Поэтому при первом переходе существующего кластера на baseline v1 нужно
**один раз вручную сбросить БД**. `postgres` в `Helm/templates/postgres.yaml`
монтирует `emptyDir: {}` (строка 53), а не PVC — данные не переживают
пересоздание пода, так что сброс сводится к:

```bash
kubectl delete pod -n hybrid-platform -l app=postgres
```

После этого под пересоздаётся с пустым `emptyDir`, и следующий прогон
Liquibase-джобы создаёт схему с нуля по `changes/v1/`.

### Первый старт на окружении с непустым топиком `Messages`: сброс офсетов

Отдельная от предыдущей и **разовая** операционная процедура (beads myl).
Consumer group `messegerparody-consumer` стартует с
`auto-offset-reset: earliest`, а Kafka в кластере переживает пересоздание пода
postgres. Значит на окружении, где топик `Messages` уже непустой, а БД только
что сброшена, первый старт консьюмера переигрывает **весь** накопленный
бэклог: старые сообщения приезжают в свежую историю чатов.

`message_id` от этого не спасает — записи в бэклоге сделаны до его появления,
у них его нет, и дедуплицировать их нечем (см. «Идемпотентность вставки»
выше). Штормa ретраев при этом не будет: сообщения старого формата с мусорными
или несовместимыми полями отсекает `exclude` в `@RetryableTopic`
(`DateTimeParseException` и остальные детерминированные ошибки разбора уходят
в `Messages-dlt` сразу, а не после шести попыток), — но сами по себе валидные
старые записи вставятся.

Кодом это не решается и решаться не должно: консьюмер не может отличить
«старое сообщение, которое уже видели» от «старого сообщения, которое ещё не
записали». Перед первым стартом сервиса на таком окружении офсеты группы
переводятся на конец топика вручную:

```bash
kubectl exec -n hybrid-platform deploy/kafka -- \
  kafka-consumer-groups.sh --bootstrap-server kafka:9092 \
  --group messegerparody-consumer --topic Messages \
  --reset-offsets --to-latest --execute
```

Команда требует, чтобы группа была неактивна, — то есть выполнять её нужно
до старта пода MessegerParody (`--dry-run` вместо `--execute` показывает, куда
именно сдвинутся офсеты, ничего не меняя). На чистом окружении, где топик
создаётся с нуля, процедура не нужна.

Важно с beads y1v: «чистое окружение» перестало получаться само собой. Пока
брокер стоял на `emptyDir`, любое пересоздание пода kafka обнуляло топик, и
процедура сброса офсетов почти никогда не требовалась. Теперь у брокера PVC
(`kafka.persistence.*`), бэклог `Messages` переживает и рестарт пода kafka, и
`helm upgrade` — так что при каждом сбросе БД проверяй офсеты явно, а не
рассчитывай, что топик уже пуст.

## Конфигурация и деплой

- HTTP-порт 8082 (нет REST API, но actuator/health и служебные эндпоинты Spring
  Boot на нём доступны), gRPC-порт 9091 — оба проброшены в
  `Helm/charts/messegerparody/templates/service.yaml`.
- `spring.datasource` (JDBC, для JPA) и `spring.r2dbc` (для R2DBC) настроены
  параллельно на одну и ту же БД (`DB_HOST`/`DB_PORT`/`DB_NAME`/`DB_USER`/
  `DB_PASSWORD`, дефолты — `postgres:5432/hybrid_db`).
- Kafka: `spring.kafka.bootstrap-servers=kafka:9092`,
  `consumer.group-id=messegerparody-consumer`,
  `producer.properties.max.request.size=10485760` (10 МиБ — с запасом под
  крупные события, хотя сам сервис ничего не продюсит на практике).
- Деплой — Helm-чарт `Helm/charts/messegerparody` (Deployment + Service +
  liquibase Job), включается флагом `messegerparody.enabled` /
  `messegerparody.liquibaseJob.enabled` в `Helm/values.yaml`.

## Удалённый мёртвый код

Ниже — классы, которые упоминались в старой документации/комментариях, но
реально отсутствуют в дереве репозитория (проверено `grep -rl` по всему
репо + `find` по имени файла на момент написания этого документа):

- **`MTS_impl`** — старая gRPC-реализация, из которой была выделена логика
  записи image url в БД. Класса нет; единственный след — упоминание в
  Javadoc-комментарии `Services/ImageUrlPersistenceService.java` («Extracted
  from the former gRPC MTS_impl…») как историческая справка, не как код.
- **`gRPC_Client`** — не найден нигде в репозитории.
- **`GoogleDriveProvider`** — не найден нигде в репозитории (хранение картинок
  на Google Drive заменено на MinIO).
- **`GoogleDriveService`** — не найден нигде в репозитории.
- **`JWT_Service`** (legacy) — не найден нигде в репозитории; в исходниках
  MessegerParody вообще нет JWT-логики (ни импортов, ни упоминаний `jwt` в
  Java-коде) — валидация токенов теперь целиком в AuthService/ingress
  (`/jwtcheck`, см. `docs/AuthService.md`).

Если в будущем один из этих классов снова понадобится искать — его в дереве
нет, это не пропущенный грep, а фактическое состояние на момент написания.

## Тестирование

Юнит-тесты (JUnit5 + Mockito, `spring-boot-starter-test`):

- `KafkaConsumerTest` — парсинг контракта топика `Images` (`userimage` /
  `chatimage`) и делегирование в `ImageUrlPersistenceService` с замоканным
  сервисом.
- `Services/ImageUrlPersistenceServiceTest` — обе ветки
  `persistImageUrl` (JPA-запись через `UserRepBase`, R2DBC-запись через
  `ReactiveChatRepository`) с замоканными репозиториями.

Integration (Testcontainers, требует Docker, в обычный `mvn test` не входит):

- `R2DBC_Repositories/MessageIdempotencyIT` — `insertMessage` против реального
  Postgres со схемой, накатанной тем же `db.changelog-master.yaml`, что и в
  проде: повтор с тем же `message_id` даёт одну строку, одинаковый текст с
  разными id — две, вставка без id (легаси-формат) по-прежнему проходит.
  Мока репозитория тут недостаточно: уникальность обеспечивает индекс
  Postgres, а не Java-код.

Полный реестр с описанием каждого теста — `docs/TESTS.md`.

Как и у остальных Java-модулей проекта, `mvn test` требует JDK21 (на JDK25
ломается Mockito) — используй `/jtest` или выставленный `JAVA_HOME` на JDK21.
