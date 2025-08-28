# 🚀 Настройка Kubernetes кластера для Hybrid Platform

Этот документ описывает процесс настройки и развертывания Hybrid Platform в локальном Kubernetes кластере с использованием kind.

## 📋 Предварительные требования

Убедитесь, что у вас установлены следующие инструменты:

- [Docker](https://docs.docker.com/get-docker/)
- [kind](https://kind.sigs.k8s.io/docs/user/quick-start/#installation)
- [kubectl](https://kubernetes.io/docs/tasks/tools/install-kubectl/)
- [Helm](https://helm.sh/docs/intro/install/)

## 🔧 Быстрая настройка

### 1. Запуск кластера

```bash
# Проверка существующих кластеров
kind get clusters

# Создание нового кластера (если не существует)
kind create cluster --name hybrid-cluster --config kind-config.yaml

# Экспорт kubeconfig
kind export kubeconfig --name hybrid-cluster

# Проверка состояния кластера
kubectl get nodes
```

### 2. Создание тестовых образов

```bash
# Создание простых тестовых образов для всех микросервисов
./create-test-images.sh
```

### 3. Развертывание приложения

```bash
# Рекомендуемый способ: только через Helm
./deploy-helm-only.sh

# Или полное развертывание с инфраструктурой
./deploy-full-cluster.sh
```

## 🏗️ Архитектура развертывания

### Микросервисы (управляются через Helm)
- **AuthService** (порт 8081) - Аутентификация и авторизация
- **HTTPService** (порт 8080) - Основной HTTP API
- **JwtProxy** (порт 8084) - Прокси для JWT токенов
- **MessegerParody** (порт 8085) - Сервис сообщений
- **HandleService** (порт 8082) - Обработка запросов

### Инфраструктура (устанавливается отдельно)
- **Zookeeper** - Координация для Kafka
- **Kafka** - Очереди сообщений
- **Redis** - Кэширование
- **Prometheus** - Мониторинг и метрики
- **Grafana** - Визуализация метрик

## 📊 Почему Helm?

### ✅ Преимущества использования Helm чартов:
1. **Единый источник истины** - все конфигурации в одном месте
2. **Версионирование** - легко обновлять и откатывать изменения
3. **Переиспользование** - чарты можно использовать в разных окружениях
4. **Шаблонизация** - параметризация через values.yaml
5. **Зависимости** - автоматическое управление зависимостями

### ❌ Проблемы дублирования:
- Сложность поддержки - изменения нужно делать в двух местах
- Возможность рассинхронизации конфигураций
- Увеличение размера скриптов
- Сложность отладки

## 🔧 Управление через Helm

### Основные команды:
```bash
# Установка
helm install hybrid-platform ./Helm -n hybrid-platform --create-namespace

# Обновление
helm upgrade hybrid-platform ./Helm -n hybrid-platform

# Удаление
helm uninstall hybrid-platform -n hybrid-platform

# Список релизов
helm list -n hybrid-platform

# Статус релиза
helm status hybrid-platform -n hybrid-platform
```

### Структура Helm чартов:
```
Helm/
├── Chart.yaml              # Основной чарт с зависимостями
├── values.yaml             # Глобальные значения
├── charts/                 # Подчарты для каждого сервиса
│   ├── authservice/        # AuthService
│   ├── httpservice/        # HTTPService
│   ├── jwtproxy/          # JwtProxy
│   ├── messegerparody/    # MessegerParody
│   └── handleservice/     # HandleService
└── templates/              # Общие шаблоны
```

## 📊 Мониторинг и управление

### Просмотр состояния подов
```bash
# Все поды в namespace
kubectl get pods -n hybrid-platform

# Мониторинг в реальном времени
kubectl get pods -n hybrid-platform -w

# Логи конкретного сервиса
kubectl logs -n hybrid-platform -l app=authservice
```

### Доступ к сервисам
```bash
# AuthService
kubectl port-forward -n hybrid-platform svc/authservice 8081:8081

# HTTPService
kubectl port-forward -n hybrid-platform svc/httpservice 8080:8080

# Grafana
kubectl port-forward -n hybrid-platform svc/grafana 3000:3000

# Prometheus
kubectl port-forward -n hybrid-platform svc/prometheus 9090:9090
```

### Grafana
- URL: http://localhost:3000
- Логин: `admin`
- Пароль: `admin123`

## 🧪 Тестирование

### Проверка Helm чартов
```bash
# Валидация чартов
helm lint ./Helm

# Тестовый рендеринг
helm template test ./Helm --values ./Helm/values.yaml

# Проверка зависимостей
helm dependency list ./Helm
```

## 🔍 Устранение неполадок

### Проблемы с Helm
```bash
# Проверка статуса релиза
helm status hybrid-platform -n hybrid-platform

# История релиза
helm history hybrid-platform -n hybrid-platform

# Откат к предыдущей версии
helm rollback hybrid-platform 1 -n hybrid-platform
```

### Проблемы с образами
```bash
# Проверка образов в кластере
docker exec hybrid-cluster-control-plane crictl images

# Загрузка образа в кластер
kind load docker-image image:tag --name hybrid-cluster
```

### Проблемы с сетью
```bash
# Проверка событий кластера
kubectl get events --sort-by='.lastTimestamp'

# Описание пода
kubectl describe pod <pod-name>
```

## 🧹 Очистка

### Удаление приложения
```bash
# Удаление через Helm
helm uninstall hybrid-platform -n hybrid-platform

# Удаление namespace
kubectl delete namespace hybrid-platform
```

### Удаление кластера
```bash
# Удаление кластера kind
kind delete cluster --name hybrid-cluster

# Очистка образов Docker
docker rmi authservice:latest httpservice:latest jwtproxy:latest messegerparody:latest handleservice:latest
```

## 📚 Полезные команды

### Helm
```bash
# Проверка чартов
helm lint ./Helm

# Обновление зависимостей
helm dependency update ./Helm

# Установка чарта
helm install hybrid-platform ./Helm --namespace hybrid-platform --create-namespace

# Просмотр значений
helm get values hybrid-platform -n hybrid-platform
```

### Kubernetes
```bash
# Контекст
kubectl config current-context
kubectl config use-context kind-hybrid-cluster

# Namespace
kubectl config set-context --current --namespace=hybrid-platform

# Ресурсы
kubectl get all -n hybrid-platform
kubectl get services -n hybrid-platform
kubectl get deployments -n hybrid-platform
```

## 🎯 Следующие шаги

1. **Настройка мониторинга** - Настройка алертов и дашбордов
2. **Масштабирование** - Настройка HPA и VPA
3. **Безопасность** - Настройка RBAC и сетевых политик
4. **CI/CD** - Интеграция с GitLab/GitHub Actions
5. **Резервное копирование** - Настройка Velero для бэкапов

## 📞 Поддержка

При возникновении проблем:

1. Проверьте логи подов: `kubectl logs <pod-name> -n hybrid-platform`
2. Проверьте события кластера: `kubectl get events -n hybrid-platform`
3. Проверьте статус Helm релиза: `helm status hybrid-platform -n hybrid-platform`
4. Убедитесь, что все образы загружены в кластер
5. Проверьте ресурсы узла: `kubectl describe node`

---

**Примечание**: Этот кластер предназначен для разработки и тестирования. Для продакшена используйте управляемые Kubernetes сервисы (EKS, GKE, AKS) или развертывайте на собственной инфраструктуре.
