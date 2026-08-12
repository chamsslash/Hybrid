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

- **Consumer**, `KafkaConsumer.java`: слушает **только** топик `Images`
  (`@KafkaListener(topics = "Images")`, `@RetryableTopic(attempts = "3")`,
  consumer group `messegerparody-consumer`). Топик `Events` в сервисе не
  используется — его читает HTTPService (см. `docs/HTTPService.md`), в
  MessegerParody для него нет ни листенера, ни упоминаний.
- Контракт события `Images` (см. комментарий в `KafkaConsumer.java`):
  ```json
  { "targetType": "userimage"|"chatimage", "targetId": "<id>", "objectKey": "<key>" }
  ```
  Обработка делегирована в `Services/ImageUrlPersistenceService.persistImageUrl`,
  которая по `targetType` пишет `objectKey` (короткий MinIO object key, не URL)
  либо в `User.imageUrl` (через JPA `UserRepBase`), либо в `r2dbc_chat.imageUrl`
  (через R2DBC `ReactiveChatRepository`).
- **Producer**, `KafkaProducer.java`: тонкая обёртка `send(String event)` над
  `KafkaTemplate`, публикует в топик `Images`. Судя по коду сервиса, реально не
  вызывается ни из одного места в MessegerParody — producer существует как
  API, но активного вызывающего кода в этом сервисе нет (событие в `Images`
  публикует HTTPService после загрузки в MinIO).

## Liquibase-миграции

Master-changelog: `src/main/resources/db/changelog/db.changelog-master.yaml`,
подключает baseline `changes/v1/` — организация по релизам (следующий релиз
добавляет `changes/v2/`, не трогая `v1/`), четыре файла, 9 changeSet'ов
суммарно:

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

Как и у остальных Java-модулей проекта, `mvn test` требует JDK21 (на JDK25
ломается Mockito) — используй `/jtest` или выставленный `JAVA_HOME` на JDK21.
