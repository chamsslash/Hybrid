#!/bin/bash

echo "🚀 Развертывание Hybrid Platform через Helm..."

# Проверяем, что кластер запущен
echo "🔍 Проверка состояния кластера..."
if ! kind get clusters | grep -q "hybrid-cluster"; then
    echo "❌ Кластер hybrid-cluster не найден. Создаем новый..."
    kind create cluster --name hybrid-cluster --config kind-config.yaml
else
    echo "✅ Кластер hybrid-cluster уже существует"
fi

# Экспортируем kubeconfig
echo "📋 Настройка kubectl..."
kind export kubeconfig --name hybrid-cluster

# Проверяем состояние кластера
echo "🔧 Проверка состояния узлов..."
kubectl get nodes

# Создаем тестовые образы
echo "🐳 Создание тестовых образов..."
./create-test-images.sh

# Создаем namespace для приложения
echo "📦 Создание namespace..."
kubectl create namespace hybrid-platform --dry-run=client -o yaml | kubectl apply -f -

# Обновляем зависимости Helm
echo "📦 Обновление Helm зависимостей..."
helm dependency update ./Helm

# Устанавливаем основной чарт
echo "📊 Установка основного Helm чарта..."
helm upgrade --install hybrid-platform ./Helm \
    --namespace hybrid-platform \
    --create-namespace \
    --wait \
    --timeout 10m

# Проверяем статус развертывания
echo "📋 Статус развертывания..."
kubectl get all -n hybrid-platform

echo "🎉 Развертывание завершено!"
echo ""
echo "📊 Для мониторинга используйте:"
echo "   kubectl get pods -n hybrid-platform -w"
echo ""
echo "🌐 Доступ к сервисам:"
echo "   kubectl port-forward -n hybrid-platform svc/authservice 8081:8081"
echo "   kubectl port-forward -n hybrid-platform svc/httpservice 8080:8080"
echo ""
echo "📚 Управление через Helm:"
echo "   helm list -n hybrid-platform"
echo "   helm upgrade hybrid-platform ./Helm -n hybrid-platform"
echo "   helm uninstall hybrid-platform -n hybrid-platform"
