# Refresh Flow (Access + Refresh + Fingerprint)

## Коротко
- Access JWT живет недолго и используется для авторизации запросов.
- Refresh JWT живет дольше и используется **только** для обновления access.
- Refresh‑сессия хранится в Redis и привязана к fingerprint‑метаданным.

## Claims
- **Access JWT**: `sub`, `jti`, `authorities`, `exp`, `iat`, `iss`, `token_use=access`.
- **Refresh JWT**: `sub`, `jti`, `sid`, `exp`, `iat`, `iss`, `token_use=refresh`.

## Redis
- `RefreshSession:<sid>` → JSON RefreshSession (sub, sid, refreshJti, accessJti, meta, timestamps, status).
- `AccessSession:<accessJti>` → `sid` (для проверки наличия refresh при истекшем access).
- `user:<sub>` → set ключей refresh‑сессий (для массового revoke).

## Поток обновления
1) Клиент получает 419 от фильтра → редирект на `/collect-fingerprint`.
2) `/exchangeTokens` получает refresh cookie + fingerprint мету.
3) Бек валидирует refresh JWT, сверяет `sid` и `refreshJti` с Redis‑сессией.
4) Сравнивает fingerprint метаданные.
5) Ротирует refresh (новый `refreshJti`) + выпускает новый access.

## Мини‑чеклист ручной проверки
- Логин/регистрация ставит cookie `access` и `refresh`.
- Истекший access → 419 → `collect-fingerprint` → `/exchangeTokens` → новые cookies.
- Refresh reuse (старый refresh после ротации) → 403 и удаление сессии.
