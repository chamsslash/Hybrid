# Hybrid Platform (k8s branch)

Kubernetes‑версия (ветка `main/dev`).

## Что внутри
- AuthService (OAuth + база пользователей)
- HTTPService (основной HTTP API + WebSocket)
- MessegerParody (DB + gRPC + обработка картинок)
- Helm (umbrella chart + подчарты сервисов)
- Kafka (KRaft), Postgres, Redis, Prometheus, Grafana

## Быстрый старт (k8s + Helm)

Локальный запуск в kind делает всё сам (собирает образы, генерирует одноразовые
ключи, поднимает chart):
```bash
./deploy-kind.sh
```

### Локальный запуск: свои креды вместо дефолтных

`deploy-kind.sh` source'ит gitignored `.env` (если он есть) и подставляет
значения из него в `helm --set`. Без `.env` скрипт всё равно работает — падает
на встроенные local-dev дефолты (`postgres123`, `admin123` и т.п., см. сам
скрипт) и печатает предупреждение.

```bash
cp .env.example .env
# заполни .env реальными (для тебя) значениями
./deploy-kind.sh
```

`.env` в `.gitignore` — не коммить его. `.env.example` — committed шаблон,
без реальных секретов.

Ручная установка — передай секреты в момент деплоя (в `values.yaml` их больше нет):
```bash
helm upgrade --install hybrid ./Helm \
  --set secrets.dbPassword=... \
  --set secrets.grafanaAdminPassword=... \
  --set secrets.googleClientSecret=... \
  --set secrets.refreshSecret=... \
  --set-file secrets.jwtPrivateKeyPem=./jwt-private.pem \
  --set-file secrets.yandexPrivateKeyPem=./yandex-sa.pem
```

## Providing secrets at deploy time

Секреты **не** хранятся в `Helm/values.yaml` (только пустые плейсхолдеры).
Chart `Helm/templates/secrets.yaml` собирает k8s Secret `<release>-app-secrets`
из значений `secrets.*`, а все Deployment'ы читают их через
`env.valueFrom.secretKeyRef`.

Значения секрета и куда они попадают:

| `secrets.*` value          | Secret key                | Env var(s)                                                              |
|----------------------------|---------------------------|------------------------------------------------------------------------|
| `dbPassword`               | `DB_PASSWORD`             | `SPRING_DATASOURCE_PASSWORD`, `DB_PASSWORD`, `PGPASSWORD`, `POSTGRES_PASSWORD` |
| `grafanaAdminPassword`     | `GRAFANA_ADMIN_PASSWORD`  | `GF_SECURITY_ADMIN_PASSWORD`                                            |
| `googleClientSecret`       | `GOOGLE_CLIENT_SECRET`    | `GOOGLE_CLIENT_SECRET`                                                  |
| `refreshSecret`            | `REFRESH_SECRET`          | `REFRESH_SECRET`                                                        |
| `jwtPrivateKeyPem`         | `JWT_PRIVATE_KEY_PEM`     | `JWT_PRIVATE_KEY_PEM`                                                   |
| `yandexPrivateKeyPem`      | `YANDEX_PRIVATE_KEY_PEM`  | `YANDEX_PRIVATE_KEY_PEM`                                                |

Публичные ключи (`*.env.jwtPublicKey`, `*.env.yandexPublicKey`) секретами не
являются и остаются в values как обычная конфигурация — но должны
соответствовать приватным ключам из Secret.

Способы передать значения:
- **Локально / CI:** `--set` (строки) и `--set-file` (PEM-файлы), см. выше.
- **Внешний менеджер секретов** (External Secrets Operator, Sealed Secrets и т.п.):
  создай Secret заранее и отключи встроенный:
  ```bash
  helm upgrade --install hybrid ./Helm \
    --set secrets.create=false \
    --set global.appSecretName=<имя-внешнего-secret>
  ```
  Внешний Secret должен содержать те же ключи, что в таблице выше.

Никогда не коммить реальные значения секретов в `values.yaml` или в скрипты.

## Security flow
Полный флоу access/refresh + fingerprint:
- `docs/security-flow.md`

Ключевая идея: ingress валидирует access через `/jwtcheck` и выставляет `X-User-ID / X-Authorities / X-Jti`,
backend доверяет только этим заголовкам.

## Ноты
- Secrets вынесены из `values.yaml` в k8s Secret (см. "Providing secrets at deploy time").
- gRPC доступен внутри кластера по сервисам.
