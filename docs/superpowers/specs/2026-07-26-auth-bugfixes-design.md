# Дизайн+план: фиксы auth-флоу (820, x7m, squ, iy0)

Дата: 2026-07-26
Ветка-источник: `dev`
Beads: `820` (P1, #1+#5), `x7m` (P2, #2), `squ` (P3, #3), `iy0` (P3, #4)

Найдено аудитом auth-флоу 2026-07-26. Решение по `sub`: **канонизируем `sub` = DB user id везде** (выбор пользователя).

## Группа A — контракт `sub` + login error (820 + x7m)

**Ветка:** `fix/auth-sub-contract`. Всё в `AuthService/.../Services/Auth_impl.java` (+ сверка HTTPService).

### A1. `login()` success (Auth_impl.java:76-80) — добавить sub
```java
DataTransferService.AuthResponse.newBuilder()
    .setStatus("200").setMessage("Successfully logged in")
    .setRole(user.getUser_role())
    .setSub(String.valueOf(user.getId()))   // ← ДОБАВИТЬ (как в register())
    .build();
```

### A2. `login()` bad-password (Auth_impl.java:75) — AuthResponse вместо onError (x7m)
Заменить `responseObserver.onError(new RuntimeException("PASSWORD MISSMATCH"))` на возврат
`AuthResponse` со `status "401"` + message (симметрично ветке «не найден», стр.88):
```java
if (!passwordEncoder.matches(request.getPassword(), user.getMyapppassword())) {
    responseObserver.onNext(DataTransferService.AuthResponse.newBuilder()
        .setStatus("401").setMessage("Invalid username or password").build());
    responseObserver.onCompleted();
    return;
}
```

### A3. `checkOneTimeCodeAndGetSubRole()` (Auth_impl.java:232-238) — Google sub → DB id
Метод уже делает `findByGoogleSub(sub)` и имеет `user`. Вернуть DB id, а не google_sub:
```java
auth_rep.findByGoogleSub(sub).ifPresentOrElse(
    user -> {
        responseObserver.onNext(DataTransferService.Sub_Role.newBuilder()
            .setSub(String.valueOf(user.getId()))   // ← было setSub(sub=google_sub)
            .setRole(user.getUser_role())
            .build());
        responseObserver.onCompleted();
    },
    () -> responseObserver.onError(Status.NOT_FOUND
        .withDescription("Auth Error: user with sub " + sub + " does not exist").asRuntimeException())
);
```

### A4. `getUserBySub()` (Auth_impl.java:200) — findById вместо findByGoogleSub
```java
public void getUserBySub(DataTransferService.Sub_Role request, StreamObserver<DataTransferService.User> responseObserver) {
    final Long id;
    try { id = Long.parseLong(request.getSub()); }
    catch (NumberFormatException e) {
        responseObserver.onError(Status.NOT_FOUND
            .withDescription("Auth Error: invalid sub " + request.getSub()).asRuntimeException());
        return;
    }
    auth_rep.findById(id).ifPresentOrElse(
        usr -> { /* тот же билд User, что сейчас (setId/setUsername/setRole) */ },
        () -> responseObserver.onError(Status.NOT_FOUND
            .withDescription("Auth Error: user with sub " + request.getSub() + " does not exist").asRuntimeException())
    );
}
```

### A5. Сверка HTTPService (не менять без нужды, только убедиться)
- `AuthGrpc.authlogin`: теперь bad-password приходит как `AuthResponse status "401"` (не onError) → он уже бросит `AuthResponseException("401", msg)` (после 93e). Убрать мёртвую проверку `"404"` (login шлёт `401`).
- `TokensResolver.CheckRefreshAndGetSub` / `WEBFLUX_Service.loginHandle` — sub теперь всегда numeric DB id для обоих типов логина; проверить, что нигде не парсится google_sub как строка иначе.

### Тесты (AuthService, JDK21)
- `login()`: success → AuthResponse содержит `sub == String.valueOf(user.getId())` и `role`.
- `login()`: bad-password → AuthResponse `status "401"` (НЕ onError/исключение).
- `getUserBySub()`: валидный numeric sub → User; нечисловой sub → NOT_FOUND (без NumberFormatException-краша).
- `checkOneTimeCodeAndGetSubRole()`: по google_sub из Redis → Sub_Role.sub == numeric user.getId().
Мокать `auth_rep`/`passwordEncoder`/`redisTemplate` (см. существующие тесты AuthService, если есть; иначе — минимальные Mockito-моки).

### Acceptance (820 + x7m)
1. Обычный логин → JWT sub = numeric DB id; silent-refresh (`/exchangeTokens`) продлевает сессию без разлогина.
2. Google-логин по-прежнему работает; его токен sub теперь тоже numeric DB id.
3. Неверный пароль → фронт показывает понятный текст (через AuthResponseException→401), не generic.
4. Live-regression в kind: не-Google юзер логинится → дёргает /exchangeTokens → 200, без редиректа на /welcome.

## Группа B — локальные фронт-фиксы (squ + iy0)

**Ветка:** `fix/auth-frontend-errors`. Файлы `HTTPService/src/main/resources/static/{callback.js, welcome.js}` (+ проверить `welcome.view.js`).

### B1. iy0 — `response.text()` на axios
`welcome.js:87` и `callback.js:63`: `await response.text()` → axios не имеет `.text()`.
Заменить на чтение `error.response?.data` / `response.data` (как в `chatcreate.js`/`register.js`).
Проверить, не скопирован ли этот баг в `welcome.view.js` (SPA-версия) — если да, поправить и там.

### B2. squ — callback.js error redirect
`callback.js:66,71`: раскомментить/дописать редирект на `/welcome` с сообщением при провале OAuth,
чтобы юзер не застревал на пустом `/authcallback`. Пример:
```js
window.location.href = "/welcome?error=" + encodeURIComponent("OAuth: " + (errorBody || error?.message || "ошибка входа"));
```

### Acceptance (squ + iy0)
- Провал OAuth → редирект на `/welcome` с понятным сообщением.
- Error-путь входа/callback показывает реальный текст, не падает с TypeError.

**Проверка группы B:** это чистый JS (нет JS-раннера) → `mvn -q compile` + визуальная проверка кода;
живьём — заодно в Task-8-подобной live-сессии (опц.).

## Диспатч
- **Agent 1** (Opus) → `fix/auth-sub-contract`: A1–A5 + тесты AuthService. Сборка обоих модулей JDK21.
- **Agent 2** (sonnet) → `fix/auth-frontend-errors`: B1–B2.
Параллельно: backend-java (AuthService) и frontend-js (HTTPService static) не пересекаются.
Мёрж в `dev` — отдельным шагом с проверкой сборки, после ревью (820 — P1, ревью обязателен + live-regression).
