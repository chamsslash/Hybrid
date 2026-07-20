# MinIO Image Pipeline — Design (beads n5y)

Date: 2026-07-21
Branch of record: `codex/spa-auth-test`
Epic: `Hybrid-kubernetes-non-local-n5y`

## Problem

Avatars (registration) and chat/message images are never persisted. Current flow is broken by a combination of:

- `WEBFLUX_Service.Upload_image` encodes the file to **Base64** and hardcodes `TargetType="chatimage"` even for registration avatars.
- It publishes via `KafkaProducer.send(...)` which targets the **`Messages`** topic (whose listener is commented out) — image events never reach the `Images` consumers.
- Raw Base64 is written into `user.image_url` / `chat.image_url` (`VARCHAR(255)`) — cannot fit.
- The `Images` message contract is inconsistent across producer (`Base64Image/Target/TargetType`), `ImageUploadDTO` (`TargetType/TargetId/Image_url`), and the MessegerParody consumer.
- `GoogleDriveProvider` / `GoogleDriveService` are dead (abandoned Drive integration; the SA key was part of the leak remediated in `rqe`/`8a3`).

The `Images` topic serves **two** independent purposes that must both be preserved:
1. **Persistence** — MessegerParody consumer writes to DB.
2. **Live update** — HTTPService consumer forwards to STOMP controllers so connected clients refresh the image in real time.

## Decisions

- **Storage backend: MinIO** (in-cluster, S3-compatible). Rationale in the brainstorm: purpose-built object storage, stays internal, reuses the `rqe` Secret pattern, no Google SA re-introduction, and lets image bytes bypass Kafka/DB. Drive rejected (fragile hotlinks, SA-key security, external dependency); pure-DB/Base64 rejected (does not scale, keeps bytes in Kafka).
- **Upload: server-side `putObject`.** Browser posts the multipart file to HTTPService (as today); the server uploads to MinIO. MinIO is never exposed to the browser; auth/validation stay in-app.
- **Serving: proxy `GET /api/images/{key}`.** HTTPService streams bytes from MinIO under the existing `/api` auth contour, with cache headers. MinIO stays internal.
- **Kafka topology preserved**, but the `Images` payload carries the **short object key**, not Base64.

## Target flow

```
Upload (server-side):
  Browser --multipart--> HTTPService.Upload_image(file, targetId, targetType)
      -> minio.putObject(bucket=images, key=<targetType>/<targetId>/<uuid>.<ext>)
      -> Kafka "Images": { targetType, targetId, objectKey }

Kafka "Images" (two independent consumers, both kept):
  |- MessegerParody -> persist objectKey to DB
  |     userimage -> user.image_url ; chatimage -> chat.image_url
  \- HTTPService    -> STOMP push "image updated" (carries objectKey)
                       -> clients fetch /api/images/{objectKey}

Serve (proxy):
  Browser <img src="/api/images/{objectKey}">
      -> HTTPService.ApiController -> minio.getObject(objectKey)
      -> stream bytes + Content-Type + Cache-Control
```

## Message contract (ANCHOR — fixed so HTTPService and MessegerParody can be built in parallel)

Topic: **`Images`**. JSON body:

```json
{ "targetType": "userimage" | "chatimage", "targetId": "<id>", "objectKey": "<bucket-relative key>" }
```

- No Base64. No other fields required by consumers.
- `ImageUploadDTO` is normalized to exactly these three fields.
- Both the HTTPService producer/consumer and the MessegerParody consumer implement against this contract verbatim.

## Component changes

### Helm / infra (`l4k`, branch `feat/minio-helm`)
- MinIO `Deployment` + `PersistentVolumeClaim` + `Service`.
- Credentials (`MINIO_ROOT_USER`, `MINIO_ROOT_PASSWORD`, app access-key/secret) added to the existing `rqe` Secret template — no plaintext in `values.yaml`.
- `values.yaml`: `minio` block (enabled, PVC size, bucket=`images`, endpoint).
- `deploy-kind.sh`: bring MinIO up in the kind stand.

### HTTPService (`6s0`, branch `feat/minio-httpservice`)
- `MinioClient` bean; endpoint/creds from env → Secret.
- `ImageStorageService`: `ensureBucket`, `putObject`, `getObject` (reactive stream).
- `Upload_image(FilePart, targetId, TargetType)` — `TargetType` becomes a parameter; call sites: registration → `userimage`, chat creation → `chatimage`.
- Producer: publish `{targetType, targetId, objectKey}` to `Images` (fix the wrong `Messages` topic; drop Base64).
- `ApiController`: `GET /api/images/{key}` streams from MinIO with `Content-Type` + `Cache-Control`.
- `KafkaConsumer(Images)` + `ImageUploadDTO`: normalize to the contract; STOMP push carries `objectKey`.

### MessegerParody (`8s0`, branch `feat/minio-messegerparody`)
- `KafkaConsumer`: handle **both** `userimage` and `chatimage` (remove the `if(!userimage) return` filter); persist the short `objectKey` via `ImageUrlPersistenceService`; align to the contract.
- `ImageUrlPersistenceService` already supports both branches — align the field name only.

### Frontend (`3b8`, branch `feat/minio-frontend`) — depends on `6s0`
- `index.js` / `chatlist.js`: render avatars/chat images via `<img src="/api/images/{key}">`.
- Handle live STOMP image-update events carrying `objectKey`.
- Remove any `drive.google.com/thumbnail` remnants.

### Migration (`03u`, branch `feat/image-url-migration`)
- Liquibase changeset: null-out legacy raw-Base64 values in `user.image_url` / `chat.image_url` (where `length>255` or looks like Base64). Column stays `VARCHAR` holding a short key.

### Cleanup (`9v0`, branch `chore/remove-googledrive`)
- Delete `GoogleDriveProvider`, `GoogleDriveService`, and any config/creds references.

## Testing strategy

### Unit (`se2`, part) — depends on `6s0`, `8s0`
- `ImageStorageService` with a mocked `MinioClient`.
- `Upload_image` `TargetType` routing (register → userimage, chat → chatimage).
- `Images` contract (de)serialization round-trip.
- `ImageUrlPersistenceService` both branches (userimage → user, chatimage → chat).

### Integration — Testcontainers (`se2`, part)
- MinIO container: `putObject` → `getObject` byte-for-byte round-trip.
- Kafka container: producer (HTTPService side) → consumer (MessegerParody side) over the `Images` contract persists the key.

### E2E on kind (`1e2`) — depends on all
- `deploy-kind`; register with an avatar → `GET /api/images/{key}` returns it.
- Create a chat with an image → same.
- Verify a live STOMP image update reaches a connected client.
- Update docs to describe the MinIO image flow.

## Task graph (beads)

```
n5y (epic)
├─ l4k  MinIO Helm/infra                 [ready]  feat/minio-helm
├─ 6s0  HTTPService upload+proxy+contract [ready]  feat/minio-httpservice
├─ 8s0  MessegerParody consumer           [ready]  feat/minio-messegerparody
├─ 03u  Liquibase migration               [ready]  feat/image-url-migration
├─ 9v0  Delete GoogleDrive dead code      [ready]  chore/remove-googledrive
├─ 3b8  Frontend /api/images + live       [blocked by 6s0]  feat/minio-frontend
├─ se2  Unit + Testcontainers tests       [blocked by 6s0, 8s0]
└─ 1e2  E2E on kind + docs                 [blocked by all]
```

- `l4k`, `6s0`, `8s0`, `03u`, `9v0` are file-disjoint (Helm / HTTPService / MessegerParody / Liquibase / dead-code) and safe to run in parallel — including as subagents, **provided their worktrees branch from the current `codex/spa-auth-test` HEAD** (not `origin/main`; this was the integration hazard in the prior round).
- `6s0` and `8s0` couple only through the fixed message contract above, so they remain parallelizable.

## Non-goals / deferred

- Image resize/thumbnail generation, content moderation, virus scanning.
- MinIO Operator / distributed multi-node tenant (single-node Deployment is enough for the stand).
- Presigned direct browser upload/download (rejected in favor of server-side + proxy).
- CDN / signed public URLs.

## Commit discipline

Each self-contained task is implemented on its own branch (names above) and committed there; integration onto `codex/spa-auth-test` happens per-task with a compile/helm check, mirroring the auth-spa integration.
