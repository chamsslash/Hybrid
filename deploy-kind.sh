#!/usr/bin/env bash
set -e

CLUSTER=hybrid
NS=hybrid-platform

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
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out "$KEYDIR/yandex-priv.pem" 2>/dev/null
openssl rsa -in "$KEYDIR/yandex-priv.pem" -pubout -out "$KEYDIR/yandex-pub.pem" 2>/dev/null

echo "▶ helm deploy"
# LOCAL DEV credentials. In real environments provide these from a secret
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
  --set secrets.dbPassword=postgres123 \
  --set secrets.grafanaAdminPassword=admin123 \
  --set secrets.googleClientSecret=local-google-client-secret \
  --set secrets.refreshSecret=local-refresh-secret \
  --set secrets.minioRootUser=minioadmin \
  --set secrets.minioRootPassword=minioadmin123 \
  --set secrets.minioAccessKey=hybrid-app \
  --set secrets.minioSecretKey=hybrid-app-secret123 \
  --set-file secrets.jwtPrivateKeyPem="$KEYDIR/jwt-priv.pem" \
  --set-file secrets.yandexPrivateKeyPem="$KEYDIR/yandex-priv.pem" \
  --set-file authservice.env.jwtPublicKey="$KEYDIR/jwt-pub.pem" \
  --set-file httpservice.env.jwtPublicKey="$KEYDIR/jwt-pub.pem" \
  --set-file httpservice.env.yandexPublicKey="$KEYDIR/yandex-pub.pem"

echo "▶ wait for workloads"
kubectl wait -n "$NS" --for=condition=Available deployment --all --timeout=300s
kubectl get pods -n "$NS"
