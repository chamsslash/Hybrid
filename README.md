# Hybrid

Мессенджер: чаты, сообщения в реальном времени через STOMP/WebSocket, аватарки и
картинки в MinIO, AI-ассист. Три Spring Boot сервиса и SPA поверх Kubernetes,
раскатка — Helm в кластер kind.

Архитектура целиком — [`docs/OVERVIEW.md`](docs/OVERVIEW.md). Здесь только то, что
нужно, чтобы снова начать работать.

## Поднять локально

Нужны `docker` и `openssl`; `kind`, `kubectl` и `helm` скрипт скачает сам, если их
нет в PATH. Docker должен быть уже запущен.

```bash
./deploy-kind.sh
```

Скрипт собирает три образа, создаёт кластер `hybrid`, ставит ingress-nginx,
генерирует одноразовые ключи подписи JWT и раскатывает чарт в namespace
`hybrid-platform`.

Открывать: **http://myapp.localtest.me** — домен резолвится в `127.0.0.1` публичным
DNS, `/etc/hosts` править не надо.

Свои значения вместо local-dev дефолтов (`postgres123`, `admin123` и т.п.):

```bash
cp .env.example .env
# заполнить .env
./deploy-kind.sh
```

`.env` в `.gitignore`, `.env.example` — шаблон без секретов.

## Раскатать правку

Ради правки в одном сервисе гонять весь скрипт не нужно и вредно: он каждый раз
генерирует новую пару ключей подписи, то есть выбрасывает все живые сессии. Точечно:

```bash
docker build -t httpservice:latest HTTPService
kind load docker-image httpservice:latest --name hybrid
kubectl -n hybrid-platform rollout restart deploy/httpservice
```

Так же для `authservice`/`AuthService` и `messegerparody`/`MessegerParody`. Правки в
`Helm/` — только полный `./deploy-kind.sh`.

**Проверять, что правка доехала до рантайма, а не только собралась.** `rollout status`
отчитается успехом и на старом образе, если загрузка в кластер не случилась:

```bash
curl -s http://myapp.localtest.me/<путь-к-файлу> | shasum -a 256   # против локального файла
```

## Тесты

**Только на JDK 21.** На 25-й ломается Mockito, и падения выглядят не связанными с
кодом. Корневого `pom.xml` нет — прогон помодульно, из каталога сервиса:

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn -B test   # macOS; иначе свой путь к JDK 21
```

Что именно покрыто — [`docs/TESTS.md`](docs/TESTS.md). Реестр живой: меняешь тесты —
правишь каталог тем же коммитом.

## Фронтенд

`HTTPService/src/main/resources/static/` — SPA на чистом JS: ни сборки, ни бандлера,
ни фреймворка, модули грузятся браузером как есть. App-shell — Thymeleaf-шаблон
`templates/app.html`, роутинг клиентский (`router.js`), постоянный навигационный
рельс живёт вне `#app` и переживает переходы (`shell.js`).

Два следствия, о которых легко забыть:

- статика лежит внутри jar, поэтому **любая правка фронта требует пересборки образа**
  сервиса — файл на диске сам по себе ни на что не влияет;
- CSP включает `require-trusted-types-for 'script'`, поэтому любая запись разметки в
  DOM идёт через `policy.createHTML(...)`, а DOMPurify вырезает часть атрибутов
  (например `name="title"`).

## Публичный стенд

```bash
PUBLIC=true ./deploy-kind.sh
```

Подмешивает `Helm/values-public.yaml`: https, `Secure`-кука, доверие к
`X-Forwarded-*`, домен проекта. Кластер при этом уезжает на `127.0.0.1:8081/8443`, а
наружу его публикует Caddy на хосте ([`deploy/Caddyfile`](deploy/Caddyfile)).
Порядок раскатки и подводные камни — [`docs/public-deploy.md`](docs/public-deploy.md).

Если скрипт падает на проверке опубликованных портов — это защита, а не поломка:
чаще всего означает, что забыли `PUBLIC=true` и он собрался занять 80/443.

## Секреты

В `values.yaml` их нет — только пустые плейсхолдеры. Чарт собирает k8s Secret
`<release>-app-secrets` из значений `secrets.*`, а деплойменты читают его через
`env.valueFrom.secretKeyRef`. `deploy-kind.sh` берёт значения из `.env` и передаёт
их в `helm --set` / `--set-file`.

Внешний менеджер секретов (External Secrets, Sealed Secrets) — создать Secret заранее
и отключить встроенный:

```bash
helm upgrade --install hybrid ./Helm \
  --set secrets.create=false \
  --set global.appSecretName=<имя-внешнего-secret>
```

Набор ключей — в [`Helm/templates/secrets.yaml`](Helm/templates/secrets.yaml).
Публичные ключи (`*.env.jwtPublicKey`) секретами не являются и живут в values, но
обязаны соответствовать приватным из Secret.

Реальные значения не коммитить — ни в `values.yaml`, ни в скрипты.

## Ветки

Работа идёт в `dev`. Под каждую самодостаточную правку — своя ветка
(`feat/…`, `fix/…`, `chore/…`), затем мёрж в `dev`. `main` отстал на месяцы и в
раскатке не участвует.

## Документация

| Документ | О чём |
|---|---|
| [OVERVIEW](docs/OVERVIEW.md) | Точка входа: сервисы, потоки данных, топология деплоя |
| [AuthService](docs/AuthService.md) · [HTTPService](docs/HTTPService.md) · [MessegerParody](docs/MessegerParody.md) | Разбор по сервисам |
| [security-flow](docs/security-flow.md) | Access в памяти + refresh в куке + fingerprint |
| [TESTS](docs/TESTS.md) | Каталог автотестов: что, зачем, на каком уровне |
| [public-deploy](docs/public-deploy.md) | Публикация стенда наружу по HTTPS |
| [observability](docs/observability.md) | Как устроен сбор метрик: Prometheus + Grafana |
| [tls-cert-manager](docs/tls-cert-manager.md) | TLS внутри кластера — опция, по умолчанию выключена |
| [vm-data-disk](docs/vm-data-disk.md) | Раскладка данных по дискам на боевой ВМ |
| [kind-stand-clock](docs/kind-stand-clock.md) | Расхождение часов VM: сообщения приходят с задержкой |

Ключевая идея безопасности, если читать некогда: ingress проверяет access-токен через
`/jwtcheck` и выставляет `X-User-ID` / `X-Authorities` / `X-Jti`, а сервисы доверяют
только этим заголовкам — не тому, что прислал клиент.
