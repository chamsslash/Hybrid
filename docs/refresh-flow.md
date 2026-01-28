# Security Flow (Access + Refresh + Fingerprint)

Этот документ описывает **единый** security‑флоу, который используется и в `main/dev` (k8s), и в `local` (docker‑compose).

## 1) Общая идея
- **Access JWT** короткий и используется для авторизации.
- **Refresh JWT** длинный и используется только для обновления access.
- **Refresh‑сессия** хранится в Redis и привязана к fingerprint‑метаданным.
- **Единственная точка доверия** — ingress/nginx: он валидирует access и выставляет заголовки.
- Backend **не валидирует JWT**, а доверяет `X-User-ID`, `X-Authorities`, `X-Jti`.

## 2) Где происходит проверка
### k8s
- Ingress делает `auth_request` в `AuthService /jwtcheck`.
- auth_request передает Authorization или cookie `access` в `jwtcheck`.
- Ingress:
  - **сбрасывает входящие** `X-User-ID`, `X-Authorities`, `X-Jti`;
  - **выставляет свои** после auth_request.

### local (docker-compose)
- То же самое делает `nginx/nginx.conf`.

## 3) Что кладем в токены
### Access JWT (короткий)
- `sub`, `jti`, `authorities`, `exp`, `iat`, `iss`, `token_use=access`.

### Refresh JWT (длинный)
- `sub`, `jti` (refresh), `sid` (session id), `exp`, `iat`, `iss`, `token_use=refresh`.

## 4) Redis-структуры
- `RefreshSession:<sid>` → JSON:
  - `sub`, `sid`, `refreshJti`, `accessJti`,
  - `meta` (fingerprint), `createdAt`, `lastSeenAt`, `rotatedAt`, `status`.
- `AccessSession:<accessJti>` → `sid` (быстрый путь от access к сессии).
- `user:<sub>` → set ключей refresh‑сессий (для массового revoke).

## 5) Логин / первичная выдача токенов
1) OAuth‑логин в `AuthService`.
2) `HTTPService` получает `sub/role`, создает:
   - `accessJti`, `refreshJti`, `sid`,
   - access‑JWT + refresh‑JWT.
3) Создается `RefreshSession:<sid>` и `AccessSession:<accessJti>`.
4) Клиент получает cookie `access` и `refresh`.

## 6) Проверка запросов
1) Клиент стучится в backend.
2) Ingress/nginx делает `auth_request` → `/jwtcheck`.
3) `jwtcheck` валидирует access:
   - если токен валиден → отдаёт `X-User-ID`, `X-Authorities`, `X-Jti`.
   - если токен истек → отдаёт `X-Jti`.
4) Backend‑фильтры:
   - если есть `X-User-ID`/`X-Authorities` → ставят `Authentication`.
   - если нет, но есть `X-Jti` и `AccessSession` существует → отдают **419**.

## 7) Refresh‑флоу
1) Клиент получает 419 → идет на `/collect-fingerprint`.
2) `/exchangeTokens` получает:
   - refresh cookie,
   - fingerprint мету.
3) Backend валидирует refresh JWT:
   - сверяет `sid` и `refreshJti` с Redis,
   - сравнивает fingerprint мету.
4) Ротация:
   - новый `refreshJti` + новый `accessJti`,
   - обновление `RefreshSession`,
   - новый mapping `AccessSession:<accessJti>`.
5) Клиент получает новые cookies `access` и `refresh`.

## 8) Ошибки и защита
- **Refresh reuse** (старый refresh после ротации) → 403 и удаление сессии.
- **Нет refresh‑сессии** → 403/401 и редирект на login.
- **FP mismatch** → 403, чистка сессии, re‑login.

## 9) Быстрый чеклист
- Логин ставит cookie `access` и `refresh`.
- Истекший access → 419 → `/collect-fingerprint` → `/exchangeTokens` → новые cookies.
- Нельзя подделать `X-User-ID`/`X-Authorities` (ингресс их сбрасывает).
