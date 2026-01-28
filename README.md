# Hybrid Platform

Минималистичная микросервисная платформа.

## Что внутри
- AuthService (OAuth + база пользователей)
- HTTPService (основной HTTP API + WebSocket)
- JwtProxy (JWT прокси)
- MessegerParody (DB + gRPC + обработка картинок)
- Kafka (KRaft), Postgres, Redis, Prometheus, Grafana

## Быстрый старт (k8s + Helm)

1) В `Helm/values.yaml` замени на свои значения:
- `authservice.google.clientId`
- `authservice.google.clientSecret`
- `httpservice.security.refreshSecret`
- `image.repository`/`image.tag` для сервисов (если не local)

2) для локального docker‑compose используется `http://localhost` (доп. hosts не нужен).

## Ноты
- Secrets пока храним в открытую (values).
- Ingress только для HTTP.
- gRPC доступен внутри кластера по сервисам.
