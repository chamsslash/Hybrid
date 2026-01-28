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

echo "▶ helm deploy"
helm upgrade --install hybrid ./Helm \
  --namespace "$NS" \
  --create-namespace \
  --set authservice.image.repository=authservice \
  --set authservice.image.tag=latest \
  --set httpservice.image.repository=httpservice \
  --set httpservice.image.tag=latest \
  --set messegerparody.image.repository=messegerparody \
  --set messegerparody.image.tag=latest

echo "▶ wait for workloads"
kubectl wait -n "$NS" --for=condition=Available deployment --all --timeout=300s
kubectl get pods -n "$NS"
