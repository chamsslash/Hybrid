# Hybrid Platform (local branch)

Локальная версия для docker‑compose. Ветка `local`.

## Что внутри
- AuthService (OAuth + база пользователей)
- HTTPService (основной HTTP API + WebSocket)
- MessegerParody (DB + gRPC + обработка картинок)
- nginx (локальная точка входа + auth_request)

## Как запускать локально
```bash
./scripts/local-run.sh
```

## Security flow
Полный флоу access/refresh + fingerprint описан здесь:
- `docs/refresh-flow.md`

Ключевая идея: nginx валидирует access через `/jwtcheck` и выставляет `X-User-ID / X-Authorities / X-Jti`,
backend доверяет только этим заголовкам.

## Ноты
- `access` и `refresh` живут в HttpOnly cookies.
- `refresh` используется только для ротации, хранится с `sid` в Redis.
