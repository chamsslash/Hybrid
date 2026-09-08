#!/usr/bin/env bash
set -e

CLUSTER=hybrid
NS=hybrid-platform

# ── Временный каталог ─────────────────────────────────────────────────────────
# Все временные файлы уходят в $TMPDIR, а не жёстко в /tmp. Ради конфига kind городить
# переменную не стоило бы — он крошечный. Дело в другом: TMPDIR читает и `kind load
# docker-image`, который перекладывает образы в ноду через промежуточный tar. Три образа
# с JRE — сотни мегабайт разом, и на сервере, где под систему отведён небольшой
# загрузочный диск, а данные лежат на отдельном, это упирается в место ровно посередине
# раскатки. Сюда же качаются недостающие инструменты (см. ниже).
#
# Дефолт выбирается по наличию диска с данными, а не жёстко: на сервере $DATA_DIR
# смонтирован и временные файлы едут туда сами, на машине разработчика его нет и всё
# работает как раньше, через /tmp. Явно заданный TMPDIR всегда побеждает.
#
# Проверка именно на КАТАЛОГ, а не на точку монтирования, сознательно: если монтирование
# не приехало, /mnt/data существует как пустой каталог на корневом разделе, и мы честно
# положим туда временные файлы — то есть на загрузочный диск. Отдельно ловить этот случай
# здесь незачем, его закрывает зависимость демонов от юнита монтирования
# (см. docs/vm-data-disk.md); дублировать ту же проверку тут значило бы чинить симптом.
: "${DATA_DIR:=/mnt/data}"
if [ -z "${TMPDIR:-}" ]; then
  if [ -d "$DATA_DIR" ] && [ -w "$DATA_DIR" ]; then
    TMPDIR="${DATA_DIR%/}/tmp"
  else
    TMPDIR=/tmp
  fi
fi
mkdir -p "$TMPDIR"
# export, а не просто присваивание: значение нужно ДОЧЕРНИМ процессам — тому же
# `kind load docker-image`. Без export переменная осталась бы видна только этому скрипту,
# и весь смысл потерялся бы молча.
export TMPDIR
echo "▶ временные файлы раскатки: ${TMPDIR}"
KIND_CONFIG="${TMPDIR%/}/kind-hybrid-config.yaml"

# ── Инструменты ───────────────────────────────────────────────────────────────
# Заведено после того, как на чистом сервере не оказалось kind: `kind create cluster ...
# || true` проглотил «command not found» ровно так же, как проглатывает «кластер уже
# существует». Скрипт поехал дальше, потратил три минуты на сборку образов и упал только
# на `kind load` — в месте, никак не связанном с настоящей причиной.
#
# Версии ПРИБИТЫ, а не «последняя доступная». Раскатка, которая тянет свежий релиз в
# момент запуска, воспроизводима ровно до следующего мажора у любого из трёх проектов, и
# ломается она в самый неудобный момент — на сервере, а не на машине, где разрабатывали.
# Значения ниже совпадают с тем, на чём проект гоняется локально.
: "${KIND_VERSION:=v0.33.0}"
: "${KUBECTL_VERSION:=v1.36.2}"
: "${HELM_VERSION:=v4.2.4}"   # мажор 4 — на нём чарт и раскатывается локально
: "${TOOLS_BIN_DIR:=/usr/local/bin}"
# false — только проверять и падать со ссылками, ничего не устанавливая.
: "${AUTO_INSTALL_TOOLS:=true}"

case "$(uname -m)" in
  x86_64|amd64)  TOOLS_ARCH=amd64 ;;
  aarch64|arm64) TOOLS_ARCH=arm64 ;;
  *)             TOOLS_ARCH="" ;;
esac
TOOLS_OS="$(uname -s | tr '[:upper:]' '[:lower:]')"

# Установка в системный каталог требует прав. sudo зовём ТОЛЬКО когда каталог реально
# недоступен на запись: на машине, где /usr/local/bin принадлежит пользователю
# (типичный homebrew-сетап), лишний пароль спрашивать незачем.
install_tool_binary() {   # $1 — файл, $2 — имя в PATH
  if [ -w "$TOOLS_BIN_DIR" ]; then
    install -m 0755 "$1" "${TOOLS_BIN_DIR}/$2"
  else
    sudo install -m 0755 "$1" "${TOOLS_BIN_DIR}/$2"
  fi
}

fetch_kind() {
  echo "  ↓ kind ${KIND_VERSION}"
  # Официальный шорткат проекта. Контрольной суммы по нему не публикуется, поэтому
  # целостность здесь держится на TLS и на том, что домен принадлежит проекту.
  curl -fsSLo "${TMPDIR}/kind" \
    "https://kind.sigs.k8s.io/dl/${KIND_VERSION}/kind-${TOOLS_OS}-${TOOLS_ARCH}"
  install_tool_binary "${TMPDIR}/kind" kind
}

fetch_kubectl() {
  echo "  ↓ kubectl ${KUBECTL_VERSION}"
  local base="https://dl.k8s.io/release/${KUBECTL_VERSION}/bin/${TOOLS_OS}/${TOOLS_ARCH}"
  curl -fsSLo "${TMPDIR}/kubectl" "${base}/kubectl"
  curl -fsSLo "${TMPDIR}/kubectl.sha256" "${base}/kubectl.sha256"
  # Проверка суммы не факультативна: без неё оборванная загрузка даёт «установленный»
  # бинарник, который падает уже внутри раскатки.
  (cd "$TMPDIR" && echo "$(cat kubectl.sha256)  kubectl" | sha256sum --check --status)
  install_tool_binary "${TMPDIR}/kubectl" kubectl
}

fetch_helm() {
  echo "  ↓ helm ${HELM_VERSION}"
  local tarball="helm-${HELM_VERSION}-${TOOLS_OS}-${TOOLS_ARCH}.tar.gz"
  curl -fsSLo "${TMPDIR}/${tarball}" "https://get.helm.sh/${tarball}"
  curl -fsSLo "${TMPDIR}/${tarball}.sha256sum" "https://get.helm.sh/${tarball}.sha256sum"
  (cd "$TMPDIR" && sha256sum --check --status "${tarball}.sha256sum")
  tar -xzf "${TMPDIR}/${tarball}" -C "$TMPDIR"
  install_tool_binary "${TMPDIR}/${TOOLS_OS}-${TOOLS_ARCH}/helm" helm
}

# docker и openssl докачкой не лечатся и не должны: docker — это демон, репозиторий пакетов
# и членство в группе с перелогином, а openssl приезжает с системой. Тихо ставить их
# из скрипта значило бы менять состояние машины сильнее, чем от него ждут.
missing_tools=()
for tool in docker kind kubectl helm openssl; do
  command -v "$tool" >/dev/null 2>&1 || missing_tools+=("$tool")
done

if [ ${#missing_tools[@]} -gt 0 ]; then
  installable=()
  manual=()
  for tool in "${missing_tools[@]}"; do
    case "$tool" in
      kind|kubectl|helm) installable+=("$tool") ;;
      *)                 manual+=("$tool") ;;
    esac
  done

  if [ ${#manual[@]} -gt 0 ]; then
    echo "✗ не найдены: ${manual[*]} — их нужно поставить вручную" >&2
    echo "    docker   https://docs.docker.com/engine/install/" >&2
    echo "    openssl  штатный пакет системы (apt install openssl)" >&2
    exit 1
  fi

  if [ "$AUTO_INSTALL_TOOLS" != "true" ]; then
    echo "✗ не найдены: ${installable[*]}, а AUTO_INSTALL_TOOLS=false" >&2
    exit 1
  fi

  # Только linux, и намеренно. Автоустановка нужна на чистом сервере; на машине
  # разработчика инструменты ставятся пакетным менеджером и уже есть. Плюс проверка сумм
  # опирается на sha256sum из coreutils, которого в macOS нет (там shasum) — тянуть сюда
  # ветвление ради платформы, где эта ветка не нужна, значит поддерживать мёртвый код.
  if [ "$TOOLS_OS" != "linux" ] || [ -z "$TOOLS_ARCH" ]; then
    echo "✗ не найдены: ${installable[*]}, а автоустановка для ${TOOLS_OS}/$(uname -m) не поддержана" >&2
    echo "    kind     https://kind.sigs.k8s.io/docs/user/quick-start/#installation" >&2
    echo "    kubectl  https://kubernetes.io/docs/tasks/tools/" >&2
    echo "    helm     https://helm.sh/docs/intro/install/" >&2
    exit 1
  fi

  echo "▶ доустановка инструментов в ${TOOLS_BIN_DIR}: ${installable[*]}"
  for tool in "${installable[@]}"; do
    "fetch_${tool}"
  done

  # Проверяем ПОСЛЕ установки, а не полагаемся на успешный curl: каталог назначения может
  # не входить в PATH, и тогда бинарник лежит, а команда по-прежнему не находится.
  still_missing=()
  for tool in "${installable[@]}"; do
    command -v "$tool" >/dev/null 2>&1 || still_missing+=("$tool")
  done
  if [ ${#still_missing[@]} -gt 0 ]; then
    echo "✗ установлены, но не найдены в PATH: ${still_missing[*]}" >&2
    echo "  добавь ${TOOLS_BIN_DIR} в PATH" >&2
    exit 1
  fi
fi

if [ -f .env ]; then
  echo "▶ loading local secrets from .env"
  set -a
  # shellcheck disable=SC1091
  . ./.env
  set +a
else
  echo "⚠ no .env found — falling back to built-in local-dev defaults (see .env.example)." >&2
fi

# Local-dev fallback defaults. Only used for variables .env didn't set — these
# are throwaway kind-only credentials, never reuse them anywhere real.
: "${DB_PASSWORD:=postgres123}"
: "${GRAFANA_ADMIN_PASSWORD:=admin123}"
: "${GOOGLE_CLIENT_ID:=local-google-client-id}"
: "${GOOGLE_CLIENT_SECRET:=local-google-client-secret}"
# TokensResolver.refreshKey() requires REFRESH_SECRET to be valid Base64 (it's
# decoded into HMAC key bytes) — a plain string default breaks token issuance
# with "Illegal base64 character". `openssl base64` line-wraps at 64 chars, so
# strip newlines too — an embedded '\n' is just as invalid as the old plain string.
: "${REFRESH_SECRET:=$(openssl rand -base64 64 | tr -d '\n')}"
: "${MINIO_ROOT_USER:=minioadmin}"
: "${MINIO_ROOT_PASSWORD:=minioadmin123}"
: "${MINIO_ACCESS_KEY:=hybrid-app}"
: "${MINIO_SECRET_KEY:=hybrid-app-secret123}"
: "${GEMINI_API_KEY:=local-gemini-api-key-placeholder}"

# TLS выключен по умолчанию — стенд работает по чистому HTTP. Включение
# (TLS_ENABLED=true ./deploy-kind.sh) ставит cert-manager и включает tls в чарте;
# оно ломает вход через Google и включает ssl-redirect на всех путях —
# цена и порядок отката описаны в docs/tls-cert-manager.md.
: "${TLS_ENABLED:=false}"
# Пин версии, а не latest: cert-manager применяется сырым манифестом релиза, и
# «поехавшая» версия CRD между запусками ломает уже выпущенные Certificate.
: "${CERT_MANAGER_VERSION:=v1.21.1}"

# Предполётная проверка часов VM (beads 75d). Docker Desktop/Colima гоняют кластер
# внутри Linux-VM, и её CLOCK_REALTIME умеет расходиться с хостом. Наблюдалось живьём:
# wall-clock VM каждые ~10 с прыгал на +39 с и тут же возвращался, монотонные часы при
# этом шли ровно. Kafka считает таймауты запросов по wall-clock, поэтому BROKER_HEARTBEAT
# от брокера к его же KRaft-контроллеру «истекал», брокер переставал годиться как
# координатор группы, и сообщения доезжали до БД с задержкой ~50 с — со стороны
# приложения это выглядит ровно как потеря сообщений. Проверка не чинит часы (это
# настройка машины разработчика, не репозитория), но не даёт диагностировать симптом
# заново с нуля. Подробности и лечение — docs/kind-stand-clock.md.
# Порты хоста, на которые выводится ingress кластера (beads ybg). Были захардкожены
# 80/443 на всех интерфейсах — то есть стенд торчал наружу, и его недоступность
# держалась только на firewall-правилах облака.
#
# Два сценария, ради которых это вынесено в переменные:
#   1. Публикация через прокси на хосте (Caddy с сертификатом Let's Encrypt): 80 и 443
#      занимает он, кластер уезжает на KIND_HTTP_HOST_PORT=8081 и слушает только
#      loopback — снаружи к нему в обход прокси не подключиться в принципе.
#   2. Доступ только через SSH-туннель: KIND_LISTEN_ADDRESS=127.0.0.1 и вопрос «а не
#      забыл ли я закрыть порт в firewall» перестаёт существовать.
# Дефолты сохраняют прежнее поведение.
: "${KIND_HTTP_HOST_PORT:=80}"
: "${KIND_HTTPS_HOST_PORT:=443}"
: "${KIND_LISTEN_ADDRESS:=0.0.0.0}"

# Доверять ли X-Forwarded-* от того, кто пришёл на ingress (beads ybg).
#
# Обязателен, когда TLS терминируется ДО кластера (Caddy на хосте VM). Без него
# ingress-nginx НЕ пробрасывает схему, а вычисляет её сам: в lua_ingress.lua
# pass_access_scheme перезаписывается из http_x_forwarded_proto только под
# `if config.use_forwarded_headers`, иначе остаётся равным $scheme. А $scheme на участке
# Caddy -> ingress всегда http. То есть Spring с forward-headers-strategy: framework
# получил бы X-Forwarded-Proto: http поверх реального https и собрал бы OAuth
# redirect-uri по http — Google ответил бы redirect_uri_mismatch, а причина выглядела бы
# как ошибка конфигурации приложения, а не прокси.
#
# Включать ТОЛЬКО вместе с KIND_LISTEN_ADDRESS=127.0.0.1: доверие к этим заголовкам от
# произвольного клиента означает, что клиент сам объявляет схему своего соединения.
# На loopback-адресе достучаться до ingress в обход Caddy неоткуда.
: "${INGRESS_USE_FORWARDED_HEADERS:=false}"

# Публичная раскатка (beads ybg): к чарту подмешивается Helm/values-public.yaml — https,
# Secure-кука, доверие к X-Forwarded-* и домен проекта.
#
#   PUBLIC=true KIND_HTTP_HOST_PORT=8081 \
#   KIND_LISTEN_ADDRESS=127.0.0.1 INGRESS_USE_FORWARDED_HEADERS=true ./deploy-kind.sh
#
# PUBLIC_HOST=<домен> перекрывает домен из overlay и сам по себе включает публичный режим
# (задать хост и не получить публичный стенд было бы ловушкой).
#
# Почему это делает скрипт, а не отдельная команда helm следом: ключи подписи живут в
# $KEYDIR, который удаляется по trap EXIT. Отдельный `helm upgrade` без --set-file на них
# откатил бы приватный ключ к плейсхолдеру из values.yaml — то есть починил бы домен и
# сломал выдачу токенов. Пустое значение = прежний локальный стенд.
: "${PUBLIC:=false}"
: "${PUBLIC_HOST:=}"
HELM_PUBLIC_ARGS=()
if [ "$PUBLIC" = "true" ] || [ -n "$PUBLIC_HOST" ]; then
  HELM_PUBLIC_ARGS=(-f Helm/values-public.yaml)
  if [ -n "$PUBLIC_HOST" ]; then
    # Домен перекрывается ТОЛЬКО когда его задали явно: иначе выигрывает тот, что зашит
    # в values-public.yaml, и раскатка совпадает с тем, что показывает
    # `helm template -f Helm/values-public.yaml`. Дублировать домен ещё и здесь незачем —
    # две копии константы расходятся ровно тогда, когда про вторую забываешь.
    HELM_PUBLIC_ARGS+=(--set "global.ingressHost=$PUBLIC_HOST" --set "ingress.host=$PUBLIC_HOST")
    echo "▶ публичная раскатка на ${PUBLIC_HOST} (values-public.yaml + override домена)"
  else
    echo "▶ публичная раскатка на домене из Helm/values-public.yaml"
  fi
fi

echo "▶ create kind cluster (ingress -> ${KIND_LISTEN_ADDRESS}:${KIND_HTTP_HOST_PORT}/${KIND_HTTPS_HOST_PORT})"
cat <<EOF >"$KIND_CONFIG"
kind: Cluster
apiVersion: kind.x-k8s.io/v1alpha4
nodes:
  - role: control-plane
    extraPortMappings:
      - containerPort: 80
        hostPort: ${KIND_HTTP_HOST_PORT}
        listenAddress: "${KIND_LISTEN_ADDRESS}"
        protocol: TCP
      - containerPort: 443
        hostPort: ${KIND_HTTPS_HOST_PORT}
        listenAddress: "${KIND_LISTEN_ADDRESS}"
        protocol: TCP
EOF
# Существование кластера проверяется явно, а не глушится через `|| true`. Раньше стояло
# `kind create cluster ... || true`, и это гасило ВСЕ ошибки, а не только «уже
# существует»: отсутствующий бинарник, нехватку памяти, недоступный docker. Скрипт после
# такого продолжал работу с кластером, которого нет, и падал где-нибудь на `kind load` —
# в месте, ничего не говорящем о причине.
if kind get clusters 2>/dev/null | grep -qx "$CLUSTER"; then
  echo "  кластер ${CLUSTER} уже существует — создание пропущено"
else
  kind create cluster --name "$CLUSTER" --config "$KIND_CONFIG"
fi

# extraPortMappings применяются ТОЛЬКО при создании кластера. Если кластер уже есть,
# `kind create` выше падает, ошибка глотается через `|| true`, и раскатка спокойно
# доезжает до конца — с ingress'ом, который по-прежнему висит на СТАРЫХ портах. Симптом
# при публикации самый неприятный из возможных: Caddy отвечает 502, приложение при этом
# полностью здорово, и искать начинаешь в приложении.
published="$(docker inspect "${CLUSTER}-control-plane" \
  --format '{{range $p, $conf := .NetworkSettings.Ports}}{{range $conf}}{{.HostIp}}:{{.HostPort}} {{end}}{{end}}' 2>/dev/null || true)"
if [ -n "$published" ] && ! printf '%s' "$published" | grep -q ":${KIND_HTTP_HOST_PORT} "; then
  echo "⚠ кластер ${CLUSTER} уже существует и опубликован как: ${published}" >&2
  echo "  запрошен хост-порт ${KIND_HTTP_HOST_PORT} на ${KIND_LISTEN_ADDRESS}, но у живого кластера его нет." >&2
  echo "  Проброс портов задаётся только при создании — пересоздай кластер:" >&2
  echo "      kind delete cluster --name ${CLUSTER} && ./deploy-kind.sh" >&2
  echo "  Данные при этом не теряются сверх обычного: postgres в чарте на emptyDir," >&2
  echo "  то есть схема и так живёт ровно столько, сколько под." >&2
  exit 1
fi

# Проверка часов ноды (beads 75d). Kafka считает таймауты запросов по wall-clock, а не по
# монотонным часам, поэтому скачки времени в VM бьют по ней первой: BROKER_HEARTBEAT от
# брокера к его же KRaft-контроллеру «истекает», брокер перестаёт годиться как координатор
# группы, консьюмеры входят в цикл 'coordinator unavailable', и сообщения доезжают до БД с
# задержкой в десятки секунд. Со стороны приложения это выглядит ровно как потеря
# сообщений — на этом уже один раз потратили полдиагностики.
#
# Ищем именно СКАЧКИ, а не расхождение с хостом: расхождение — ненадёжный признак, сразу
# после перезагрузки VM оно равно нулю, а скачки при этом идут. Признак скачка — wall-clock
# ушёл далеко вперёд или назад относительно монотонных часов за один шаг цикла.
# Наблюдалось: +39 с раз в ~10 с, поэтому 12-секундного окна хватает, чтобы поймать.
#
# Проверка только предупреждает: часы VM — настройка машины разработчика, а не репозитория.
# Диагностика и лечение (виновник — systemd-timesyncd внутри VM): docs/kind-stand-clock.md
echo "▶ проверка часов ноды кластера (12 с)"
jumps=$(docker exec "${CLUSTER}-control-plane" sh -c \
  'for i in $(seq 1 60); do echo "$(cut -d" " -f1 /proc/uptime) $(date +%s.%N)"; sleep 0.2; done' 2>/dev/null \
  | awk 'NR>1{dm=$1-pm; dw=$2-pw; d=dw-dm; if(d>0.5||d<-0.5) n++} {pm=$1; pw=$2} END{print n+0}')
if [ -n "$jumps" ] && [ "$jumps" -gt 0 ]; then
  echo "⚠ часы ноды скачут: ${jumps} скачков за 12 с." >&2
  echo "  Kafka будет ложно ронять BROKER_HEARTBEAT, сообщения — доезжать с задержкой," >&2
  echo "  что неотличимо от их потери. Лечение: docs/kind-stand-clock.md" >&2
else
  echo "  скачков не обнаружено — в норме"
fi

echo "▶ build images"
docker build -t authservice:latest AuthService
docker build -t httpservice:latest HTTPService
docker build -t messegerparody:latest MessegerParody

echo "▶ load images into kind"
kind load docker-image authservice:latest --name "$CLUSTER"
kind load docker-image httpservice:latest --name "$CLUSTER"
kind load docker-image messegerparody:latest --name "$CLUSTER"

echo "▶ install ingress-nginx"
kubectl apply -f https://raw.githubusercontent.com/kubernetes/ingress-nginx/main/deploy/static/provider/kind/deploy.yaml
kubectl wait -n ingress-nginx --for=condition=ready pod --selector=app.kubernetes.io/component=controller --timeout=180s
kubectl -n ingress-nginx patch configmap ingress-nginx-controller --type merge \
  -p "{\"data\":{\"allow-snippet-annotations\":\"true\",\"use-forwarded-headers\":\"${INGRESS_USE_FORWARDED_HEADERS}\"}}"
kubectl -n ingress-nginx rollout restart deployment ingress-nginx-controller
kubectl wait -n ingress-nginx --for=condition=available deployment/ingress-nginx-controller --timeout=180s
kubectl wait -n ingress-nginx --for=condition=ready pod --selector=app.kubernetes.io/component=controller --timeout=180s

# Deployment "Available" (and even pod "Ready") can flip true before the
# admission webhook's Service endpoint has propagated through kube-proxy —
# helm's Ingress apply then hits "connection refused" against the webhook.
# Poll the Endpoints object directly for a real address before proceeding.
echo "▶ wait for ingress-nginx admission webhook endpoint"
for i in $(seq 1 30); do
  ep="$(kubectl get endpoints -n ingress-nginx ingress-nginx-controller-admission -o jsonpath='{.subsets[0].addresses[0].ip}' 2>/dev/null)"
  if [ -n "$ep" ]; then
    echo "webhook endpoint ready: $ep"
    break
  fi
  sleep 2
done

HELM_TLS_ARGS=()
if [ "$TLS_ENABLED" = "true" ]; then
  # cert-manager ставится ЗДЕСЬ, до helm, и это не стилистика: его CRD
  # (ClusterIssuer/Certificate) должны существовать в кластере раньше, чем helm
  # применит Helm/templates/tls-cert-manager.yaml. Зависимостью чарта это не
  # решается — см. шапку того же файла.
  echo "▶ install cert-manager $CERT_MANAGER_VERSION"
  kubectl apply -f "https://github.com/cert-manager/cert-manager/releases/download/${CERT_MANAGER_VERSION}/cert-manager.yaml"

  # Ждать надо все три деплоймента, а не только контроллер: Certificate проходит
  # через conversion/validating-вебхук (cert-manager-webhook), а CA-инъекцию в него
  # делает cainjector. Готовый контроллер при неподнятом вебхуке — это ровно та же
  # гонка, из-за которой выше отдельно ждём admission-вебхук ingress-nginx.
  echo "▶ wait for cert-manager deployments"
  for d in cert-manager cert-manager-webhook cert-manager-cainjector; do
    kubectl wait -n cert-manager --for=condition=available "deployment/$d" --timeout=300s
  done

  # Deployment "Available" наступает раньше, чем Endpoints вебхука расходятся по
  # kube-proxy — тот же приём, что и для ingress-nginx выше: дожидаемся реального
  # адреса, иначе первый же apply Certificate ловит "connection refused".
  echo "▶ wait for cert-manager webhook endpoint"
  for i in $(seq 1 30); do
    cmep="$(kubectl get endpoints -n cert-manager cert-manager-webhook -o jsonpath='{.subsets[0].addresses[0].ip}' 2>/dev/null)"
    if [ -n "$cmep" ]; then
      echo "cert-manager webhook endpoint ready: $cmep"
      break
    fi
    sleep 2
  done

  HELM_TLS_ARGS=(--set tls.enabled=true)
fi

echo "▶ generate throwaway signing keys (LOCAL DEV ONLY — never reuse in prod)"
# Secrets are NOT stored in values.yaml. For local kind we mint fresh, disposable
# key pairs at deploy time and inject them via --set-file. The matching public
# keys override the placeholders in values.yaml so signing/verification agree.
KEYDIR="$(mktemp -d)"
trap 'rm -rf "$KEYDIR"' EXIT
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out "$KEYDIR/jwt-priv.pem" 2>/dev/null
openssl rsa -in "$KEYDIR/jwt-priv.pem" -pubout -out "$KEYDIR/jwt-pub.pem" 2>/dev/null

echo "▶ helm deploy"
# LOCAL DEV credentials, sourced from .env (see .env.example) with built-in
# fallback defaults above. In real environments provide these from a secret
# manager (e.g. External Secrets Operator) via secrets.create=false +
# global.appSecretName, and do NOT pass plaintext on the command line.
#
# The webhook-endpoint check above confirms the Endpoints object has an
# address, but kube-proxy programming the Service's iptables/ipvs rule on
# the node can lag a moment behind that — helm's Ingress apply can still
# hit "connection refused" even right after the check passes. Retry the
# whole upgrade a few times rather than trying to close that timing gap
# more precisely.
for attempt in 1 2 3; do
  if helm upgrade --install hybrid ./Helm \
    --namespace "$NS" \
    --create-namespace \
    --set authservice.image.repository=authservice \
    --set authservice.image.tag=latest \
    --set httpservice.image.repository=httpservice \
    --set httpservice.image.tag=latest \
    --set messegerparody.image.repository=messegerparody \
    --set messegerparody.image.tag=latest \
    --set secrets.dbPassword="$DB_PASSWORD" \
    --set secrets.grafanaAdminPassword="$GRAFANA_ADMIN_PASSWORD" \
    --set authservice.google.clientId="$GOOGLE_CLIENT_ID" \
    --set secrets.googleClientSecret="$GOOGLE_CLIENT_SECRET" \
    --set secrets.refreshSecret="$REFRESH_SECRET" \
    --set secrets.minioRootUser="$MINIO_ROOT_USER" \
    --set secrets.minioRootPassword="$MINIO_ROOT_PASSWORD" \
    --set secrets.minioAccessKey="$MINIO_ACCESS_KEY" \
    --set secrets.minioSecretKey="$MINIO_SECRET_KEY" \
    --set-file secrets.jwtPrivateKeyPem="$KEYDIR/jwt-priv.pem" \
    --set-file authservice.env.jwtPublicKey="$KEYDIR/jwt-pub.pem" \
    --set-file httpservice.env.jwtPublicKey="$KEYDIR/jwt-pub.pem" \
    --set secrets.geminiApiKey="$GEMINI_API_KEY" \
    "${HELM_PUBLIC_ARGS[@]}" \
    "${HELM_TLS_ARGS[@]}"; then
    break
  fi
  if [ "$attempt" = 3 ]; then
    echo "helm upgrade failed after 3 attempts" >&2
    exit 1
  fi
  echo "▶ helm upgrade failed (attempt $attempt/3, likely ingress-webhook race) — retrying in 5s"
  sleep 5
done

echo "▶ force rollout restart of app deployments"
# Image tag stays ":latest" on every run, and imagePullPolicy is IfNotPresent —
# helm upgrade sees no diff in the rendered manifest and Kubernetes has no
# reason to recreate already-running pods, even though `kind load
# docker-image` just replaced the image content in containerd. Without this,
# a pod can keep serving a build that's days old despite every subsequent
# `./deploy-kind.sh` reporting success (found via messegerparody running a
# 2026-07-26 build for 5+ days while later commits changed its query logic).
for svc in authservice httpservice messegerparody; do
  kubectl rollout restart deployment/"$svc" -n "$NS"
done
for svc in authservice httpservice messegerparody; do
  kubectl rollout status deployment/"$svc" -n "$NS" --timeout=180s
done

echo "▶ wait for workloads"
kubectl wait -n "$NS" --for=condition=Available deployment --all --timeout=300s
kubectl get pods -n "$NS"
