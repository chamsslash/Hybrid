# Hybrid Platform - Простая микросервисная платформа

Простая микросервисная платформа с автоматическим деплоем в Kubernetes.

## Микросервисы

- **AuthService** (порт 8081) - Аутентификация
- **HTTPService** (порт 8080) - Основной HTTP сервис  
- **JwtProxy** (порт 8084) - JWT прокси
- **MessegerParody** (порт 8082) - Сообщения
- **HandleService** (порт 8083) - Обработка

## Инфраструктура

- **Kafka** (порт 9092) - Брокер сообщений
- **Zookeeper** (порт 2181) - Координация Kafka
- **Prometheus** (порт 9090) - Мониторинг
- **Grafana** (порт 3000) - Дашборды

## Структура проекта

```
├── AuthService/          # Микросервис аутентификации
├── HTTPService/          # Основной HTTP сервис
├── JwtProxy/            # JWT прокси сервис
├── MessegerParody/      # Сервис сообщений
├── HandleService/       # Сервис обработки
├── Helm/                # Helm чарты
│   ├── charts/
│   │   ├── authservice/
│   │   ├── httpservice/
│   │   ├── jwtproxy/
│   │   ├── messegerparody/
│   │   └── handleservice/
│   └── templates/       # Инфраструктура (Kafka, Prometheus, Grafana)
└── .github/workflows/   # CI/CD пайплайны
```

## Быстрый старт

### Развертывание

1. **Разверните все сразу:**
```bash
helm upgrade --install hybrid-platform ./Helm \
  --namespace hybrid-platform \
  --create-namespace
```

2. **Разверните отдельные сервисы:**
```bash
# AuthService
helm upgrade --install authservice ./Helm/charts/authservice \
  --namespace hybrid-platform \
  --set image.repository=your-registry/authservice \
  --set image.tag=latest

# Остальные сервисы аналогично...
```

## CI/CD

Один универсальный пайплайн `.github/workflows/deploy.yml`:

- Запускается при push в main
- Автоматически определяет какие сервисы изменились
- Собирает и деплоит только измененные сервисы
- Деплоит инфраструктуру при изменении Helm чартов

## Проверка статуса

```bash
kubectl get pods -n hybrid-platform
kubectl get services -n hybrid-platform
kubectl get hpa -n hybrid-platform
```

## Доступ к сервисам

```bash
# Prometheus
kubectl port-forward svc/prometheus 9090:9090 -n hybrid-platform

# Grafana
kubectl port-forward svc/grafana 3000:3000 -n hybrid-platform
# Логин: admin, Пароль: admin123

# Kafka
kubectl port-forward svc/kafka 9092:9092 -n hybrid-platform
```
