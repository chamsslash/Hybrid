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
: "${REFRESH_SECRET:=local-refresh-secret}"
: "${MINIO_ROOT_USER:=minioadmin}"
: "${MINIO_ROOT_PASSWORD:=minioadmin123}"
: "${MINIO_ACCESS_KEY:=hybrid-app}"
: "${MINIO_SECRET_KEY:=hybrid-app-secret123}"
: "${GEMINI_API_KEY:=local-gemini-api-key-placeholder}"

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
helm upgrade --install hybrid ./Helm \
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
  --set secrets.geminiApiKey="$GEMINI_API_KEY"

echo "▶ wait for workloads"
kubectl wait -n "$NS" --for=condition=Available deployment --all --timeout=300s
kubectl get pods -n "$NS"
