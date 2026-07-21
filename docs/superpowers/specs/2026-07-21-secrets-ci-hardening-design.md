# Секреты, CI и харденинг MinIO-чарта — дизайн

Дата: 2026-07-21
Рабочая ветка: `feat/minio-image-pipeline`
Эпик: `Hybrid-kubernetes-non-local-csd` (RESCUE)

## Контекст

После интеграции MinIO (`n5y`) остались follow-up'ы по секретам/CI (из разбора `57i`). Уточнены пожелания и факты.

### Факты (проверено в коде)
- **Yandex-ключ НЕ мёртвый.** `YANDEX_PRIVATE_KEY_PEM` нужен для Yandex Cloud IAM → **Yandex GPT** (фича AI-ассиста), а НЕ для выпиленного Yandex Drive. Живой код: `WEBFLUX_Service.java` `@PostMapping("/AiAssist")` вызывает `yandexGptService.BuildJsonPrompt/GetAssistantAnswer`; фронт — `requestAIResponse()`; ingress роутит `/AiAssist`. Удалять ключ нельзя. В CI-смоуке пустой ключ безвреден (смоук не дёргает `/AiAssist`).
- **Локальный запуск = `deploy-kind.sh`** с захардкоженными `--set` кредами. `docker-compose`/`.env`/`scripts`/`nginx` в дереве нет.

### Решения
- **CI**: креды подаются из **GitHub Actions Secrets** (`${{ secrets.* }}`), не хардкодятся в `build.yml`.
- **Локально**: креды из **gitignored `.env`**, который `deploy-kind.sh` source'ит и прокидывает в `helm --set/--set-file`. Сервисы получают их как раньше — через k8s Secret → env (вариант «deploy-kind читает .env», не spring-dotenv и не docker-compose).
- **Чарт**: fail-fast на пустых MinIO-кредах вместо тихого отката сервера на `minioadmin` → `Access Denied` в provision-Job.
- **Версии**: запинить образы MinIO на конкретный стабильный RELEASE.
- **CI-тесты**: гонять `mvn test` (наши свежие юниты) на **JDK 21** отдельным job'ом, не только kind-смоук.

## Декомпозиция (file-disjoint → параллелится)

| ID | Задача | Скоуп (владелец файлов) |
|----|--------|--------------------------|
| `zuj` (T1) | CI: креды из GitHub Secrets + job `mvn test` (JDK21) + `kubectl wait job/minio-provision` | `.github/workflows/build.yml` |
| `sdj` (T2) | Chart: `{{ required }}` fail-fast + единый источник creds + пин стабильной версии MinIO (поглощает ar8) | `Helm/templates/minio.yaml`, `secrets.yaml`, `values.yaml` |
| `b4z` (T3) | Local: `deploy-kind.sh` берёт креды из gitignored `.env` + `.env.example` | `deploy-kind.sh`, `.env.example`, `.gitignore`, `README` |
| `tg8` (T4) | AI-assist: проверить, что `/AiAssist`→Yandex GPT работает; решить — оставить фичу+ключ или выпилить | investigation (чтение) |

Все четыре — на непересекающихся файлах, безопасно параллелить сабагентами (worktree от текущего HEAD, `baseRef: head` + guard). `T4` — investigation, код обычно не меняет.

### Детали
- **T1 (JDK 21 в CI критично):** `actions/setup-java` с Java 21 — иначе Mockito падает (см. [[mvn-tests-need-jdk21]]). `@Tag("integration")` (Testcontainers) исключены surefire'ом; при желании — отдельный шаг с Docker (в GH-раннере Docker есть).
- **T2 fail-fast:** `{{ required "MinIO creds must be set" .Values.secrets.minioRootPassword }}` и т.п. Проверить, что app-access-key == provisioned user (`mc admin user add`). Стабильный RELEASE-тег уточнить через find-docs/ctx7.
- **T3:** `.env` в `.gitignore`, `.env.example` — committed шаблон без реальных значений.

## Не-цели
- Внешний секрет-менеджер (External Secrets/Sealed Secrets) для прода — отдельная задача, не сюда.
- Ротация утёкших секретов — на человека (вне автоматизации).

## Дисциплина
Каждая задача — своя ветка (`feat/*`/`chore/*`), коммит туда, интеграция в `feat/minio-image-pipeline` по-задачно с проверкой (`helm lint`/`template`, `mvn test` на JDK21, локальный прогон `build.yml`-шагов насколько возможно).
