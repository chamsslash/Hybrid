# Hybrid Platform

Минимальный деплой в Kubernetes без лишнего, с Kafka (KRaft) и мониторингом.

## Сервисы

- AuthService (8081)
- HTTPService (8080)
- JwtProxy (8084)
- MessegerParody (8082)

## Инфраструктура

- Kafka (KRaft, без Zookeeper)
- PostgreSQL (если не внешний)
- Redis (если не внешний)
- Prometheus
- Grafana

## Поток изображений

1) AuthService/HTTPService -> Kafka `Images` (base64)
2) MessegerParody -> Google Drive -> DB
3) MessegerParody -> Kafka `Images` (final `Image_url`)
4) HTTPService -> WebSocket -> UI

## Деплой (программа минимум)

### 1) Собрать образы (локально для kind)

Собери образы и загрузись в kind:

```
docker build -t authservice:latest AuthService
docker build -t httpservice:latest HTTPService
docker build -t jwtproxy:latest JwtProxy
docker build -t messegerparody:latest MessegerParody

kind load docker-image authservice:latest
kind load docker-image httpservice:latest
kind load docker-image jwtproxy:latest
kind load docker-image messegerparody:latest
```


### 2) Поднять инфраструктуру

Нужны рабочие инстансы:

- Kafka в KRaft-режиме
- PostgreSQL
- Redis
- Prometheus
- Grafana

Можно ставить через свои манифесты или готовые чарты. Важно только,
чтобы сервисы видели их по адресам, которые ты укажешь в env.

### 3) Деплой сервисов

Каждый сервис должен получать переменные окружения:

- SPRING_KAFKA_BOOTSTRAP_SERVERS
- SPRING_DATASOURCE_URL
- SPRING_DATASOURCE_USERNAME
- SPRING_DATASOURCE_PASSWORD
- SPRING_REDIS_HOST
- SPRING_REDIS_PORT

### 4) Локальный ingress

Используем ingress с локальным доменом через `/etc/hosts`.

1) Подними ingress-controller (nginx)
2) Добавь домен в `/etc/hosts`, например:
   127.0.0.1 myapp.local
3) В ingress укажи `host: myapp.local` и сервис HTTPService

После этого открывай `http://myapp.local`.

## Примечания

- Секреты храним в открытую (пока без sealed/external secret).
- Ingress только для HTTP. gRPC ingress не нужен для минимума.
- Kafka должна быть без Zookeeper (KRaft).
