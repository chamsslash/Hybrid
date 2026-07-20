# Пайплайн картинок на MinIO — дизайн (beads n5y)

Дата: 2026-07-21
Рабочая ветка: `codex/spa-auth-test`
Эпик: `Hybrid-kubernetes-non-local-n5y`

## Проблема

Аватарки (регистрация) и картинки чатов/сообщений не сохраняются. Текущий поток сломан комбинацией причин:

- `WEBFLUX_Service.Upload_image` кодирует файл в **Base64** и хардкодит `TargetType="chatimage"` даже для аватарок регистрации.
- Публикует через `KafkaProducer.send(...)`, который шлёт в топик **`Messages`** (его листенер закомментирован) — события картинок не доходят до консюмеров `Images`.
- Сырой Base64 пишется в `user.image_url` / `chat.image_url` (`VARCHAR(255)`) — не влезает.
- Контракт сообщения `Images` рассогласован между продюсером (`Base64Image/Target/TargetType`), `ImageUploadDTO` (`TargetType/TargetId/Image_url`) и консюмером MessegerParody.
- `GoogleDriveProvider` / `GoogleDriveService` — мёртвый код (брошенная Drive-интеграция; SA-ключ был частью утечки, вычищенной в `rqe`/`8a3`).

Топик `Images` обслуживает **две** независимые цели, обе нужно сохранить:
1. **Персист** — консюмер MessegerParody пишет в БД.
2. **Live-обновление** — консюмер HTTPService форвардит в STOMP-контроллеры, чтобы подключённые клиенты обновляли картинку в реальном времени.

## Решения

- **Сторедж: MinIO** (в кластере, S3-совместимый). Обоснование из брейншторма: purpose-built объектное хранилище, остаётся внутренним, переиспользует Secret-паттерн из `rqe`, не возвращает Google SA, и позволяет байтам картинок минуть Kafka/БД. Drive отклонён (хрупкие хотлинки, security SA-ключа, внешняя зависимость); чистый БД/Base64 отклонён (не масштабируется, держит байты в Kafka).
- **Загрузка: server-side `putObject`.** Браузер шлёт multipart-файл в HTTPService (как сейчас); сервер грузит в MinIO. MinIO наружу не торчит; аутентификация/валидация — в приложении.
- **Отдача: прокси `GET /api/images/{key}`.** HTTPService стримит байты из MinIO под тем же auth-контуром `/api`, с кэш-заголовками. MinIO остаётся внутренним.
- **Топология Kafka сохранена**, но payload `Images` несёт **короткий object key**, а не Base64.

## Целевой поток

```
Загрузка (server-side):
  Браузер --multipart--> HTTPService.Upload_image(file, targetId, targetType)
      -> minio.putObject(bucket=images, key=<targetType>/<targetId>/<uuid>.<ext>)
      -> Kafka "Images": { targetType, targetId, objectKey }

Kafka "Images" (два независимых консюмера, оба сохраняем):
  |- MessegerParody -> персист objectKey в БД
  |     userimage -> user.image_url ; chatimage -> chat.image_url
  \- HTTPService    -> STOMP-пуш "картинка обновлена" (несёт objectKey)
                       -> клиенты дёргают /api/images/{objectKey}

Отдача (прокси):
  Браузер <img src="/api/images/{objectKey}">
      -> HTTPService.ApiController -> minio.getObject(objectKey)
      -> стрим байт + Content-Type + Cache-Control
```

## Контракт сообщения (ЯКОРЬ — зафиксирован, чтобы HTTPService и MessegerParody делались параллельно)

Топик: **`Images`**. JSON-тело:

```json
{ "targetType": "userimage" | "chatimage", "targetId": "<id>", "objectKey": "<ключ относительно бакета>" }
```

- Без Base64. Других полей консюмерам не требуется.
- `ImageUploadDTO` приводится ровно к этим трём полям.
- И продюсер/консюмер HTTPService, и консюмер MessegerParody реализуют этот контракт буквально.

## Изменения по компонентам

### Helm / инфра (`l4k`, ветка `feat/minio-helm`)
- MinIO `Deployment` + `PersistentVolumeClaim` + `Service`.
- Креды (`MINIO_ROOT_USER`, `MINIO_ROOT_PASSWORD`, app access-key/secret) добавить в существующий Secret-шаблон из `rqe` — без плейнтекста в `values.yaml`.
- `values.yaml`: блок `minio` (enabled, размер PVC, bucket=`images`, endpoint).
- `deploy-kind.sh`: поднять MinIO в kind-стенде.

### HTTPService (`6s0`, ветка `feat/minio-httpservice`)
- Бин `MinioClient`; endpoint/creds из env → Secret.
- `ImageStorageService`: `ensureBucket`, `putObject`, `getObject` (реактивный стрим).
- `Upload_image(FilePart, targetId, TargetType)` — `TargetType` становится параметром; call-site: регистрация → `userimage`, создание чата → `chatimage`.
- Продюсер: публиковать `{targetType, targetId, objectKey}` в `Images` (починить неверный топик `Messages`; убрать Base64).
- `ApiController`: `GET /api/images/{key}` стримит из MinIO с `Content-Type` + `Cache-Control`.
- `KafkaConsumer(Images)` + `ImageUploadDTO`: привести к контракту; STOMP-пуш несёт `objectKey`.

### MessegerParody (`8s0`, ветка `feat/minio-messegerparody`)
- `KafkaConsumer`: обрабатывать **оба** типа `userimage` и `chatimage` (убрать фильтр `if(!userimage) return`); персистить короткий `objectKey` через `ImageUrlPersistenceService`; привести к контракту.
- `ImageUrlPersistenceService` уже поддерживает обе ветки — выровнять только имя поля.

### Frontend (`3b8`, ветка `feat/minio-frontend`) — зависит от `6s0`
- `index.js` / `chatlist.js`: рендерить аватарки/картинки чатов через `<img src="/api/images/{key}">`.
- Обрабатывать live-STOMP-события обновления картинки, несущие `objectKey`.
- Убрать остатки `drive.google.com/thumbnail`.

### Миграция (`03u`, ветка `feat/image-url-migration`)
- Liquibase changeset: обнулить старые сырые Base64-значения в `user.image_url` / `chat.image_url` (где `length>255` или похоже на Base64). Колонка остаётся `VARCHAR`, хранит короткий key.

### Чистка (`9v0`, ветка `chore/remove-googledrive`)
- Удалить `GoogleDriveProvider`, `GoogleDriveService` и любые ссылки/креды.

## Стратегия тестирования

### Unit (`se2`, часть) — зависит от `6s0`, `8s0`
- `ImageStorageService` с замоканным `MinioClient`.
- Роутинг `TargetType` в `Upload_image` (регистрация → userimage, чат → chatimage).
- (Де)сериализация контракта `Images` (round-trip).
- Обе ветки `ImageUrlPersistenceService` (userimage → user, chatimage → chat).

### Integration — Testcontainers (`se2`, часть)
- Контейнер MinIO: `putObject` → `getObject` byte-for-byte round-trip.
- Контейнер Kafka: продюсер (сторона HTTPService) → консюмер (сторона MessegerParody) по контракту `Images` — key персистится.

### E2E на kind (`1e2`) — зависит от всех
- `deploy-kind`; регистрация с аватаром → `GET /api/images/{key}` отдаёт его.
- Создание чата с картинкой → то же.
- Проверить, что live-STOMP-обновление картинки доходит до подключённого клиента.
- Обновить docs с описанием MinIO-потока картинок.

## Граф задач (beads)

```
n5y (эпик)
├─ l4k  MinIO Helm/инфра                   [ready]  feat/minio-helm
├─ 6s0  HTTPService upload+proxy+контракт   [ready]  feat/minio-httpservice
├─ 8s0  MessegerParody consumer             [ready]  feat/minio-messegerparody
├─ 03u  Liquibase-миграция                  [ready]  feat/image-url-migration
├─ 9v0  Удалить мёртвый GoogleDrive         [ready]  chore/remove-googledrive
├─ 3b8  Frontend /api/images + live         [ждёт 6s0]        feat/minio-frontend
├─ se2  Unit + Testcontainers               [ждёт 6s0, 8s0]
└─ 1e2  E2E на kind + docs                  [ждёт всех]
```

- `l4k`, `6s0`, `8s0`, `03u`, `9v0` — file-disjoint (Helm / HTTPService / MessegerParody / Liquibase / мёртвый код), безопасно запускать параллельно — в т.ч. сабагентами, **при условии, что их worktree'ы branch'атся от текущего HEAD `codex/spa-auth-test`** (не от `origin/main`; это была причина проблем интеграции в прошлый раз).
- `6s0` и `8s0` связаны только зафиксированным контрактом выше, поэтому остаются параллелизуемыми.

## Не-цели / отложено

- Ресайз/генерация превью, модерация контента, антивирус.
- MinIO Operator / распределённый multi-node tenant (для стенда достаточно single-node Deployment).
- Presigned прямая загрузка/скачивание браузером (отклонено в пользу server-side + прокси).
- CDN / подписанные публичные URL.

## Дисциплина коммитов

Каждая самодостаточная задача реализуется в своей ветке (имена выше) и коммитится туда; интеграция в `codex/spa-auth-test` — по-задачно с проверкой сборки/helm, как в интеграции auth-spa.
