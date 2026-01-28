# Hybrid Platform (k8s branch)

Kubernetes‑версия (ветка `main/dev`).

## Что внутри
- AuthService (OAuth + база пользователей)
- HTTPService (основной HTTP API + WebSocket)
- MessegerParody (DB + gRPC + обработка картинок)
- Helm (umbrella chart + подчарты сервисов)
- Kafka (KRaft), Postgres, Redis, Prometheus, Grafana

## Быстрый старт (k8s + Helm)
1) В `Helm/values.yaml` укажи секреты:
- `authservice.google.clientId`
- `authservice.google.clientSecret`
- `httpservice.security.refreshSecret`

2) Установить:
```bash
helm upgrade --install hybrid ./Helm
```

## Security flow
Полный флоу access/refresh + fingerprint:
- `docs/refresh-flow.md`

Ключевая идея: ingress валидирует access через `/jwtcheck` и выставляет `X-User-ID / X-Authorities / X-Jti`,
backend доверяет только этим заголовкам.

## Ноты
- Secrets сейчас хранятся в values (временно).
- gRPC доступен внутри кластера по сервисам.
