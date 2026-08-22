#!/usr/bin/env bash
set -e

CLUSTER=hybrid
NS=hybrid-platform

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

echo "▶ create kind cluster"
cat <<'EOF' >/tmp/kind-hybrid-config.yaml
kind: Cluster
apiVersion: kind.x-k8s.io/v1alpha4
nodes:
  - role: control-plane
    extraPortMappings:
      - containerPort: 80
        hostPort: 80
        protocol: TCP
      - containerPort: 443
        hostPort: 443
        protocol: TCP
EOF
kind create cluster --name "$CLUSTER" --config /tmp/kind-hybrid-config.yaml || true

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
kubectl -n ingress-nginx patch configmap ingress-nginx-controller --type merge -p '{"data":{"allow-snippet-annotations":"true"}}'
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
