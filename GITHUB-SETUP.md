# 🚀 Настройка GitHub Actions для автоматического деплоя

## 📋 Что делает этот workflow

При каждом push в `main` ветку автоматически:
1. ✅ Проверяет и тестирует Helm чарты
2. 🐳 Собирает Docker образы для всех микросервисов
3. 📦 Загружает образы в Docker Hub
4. 🏗️ Создает временный Kubernetes кластер (kind)
5. 🚀 Деплоит приложение через Helm
6. 🧪 Проверяет успешность деплоя
7. 🧹 Очищает временные ресурсы

## 🔐 Настройка GitHub Secrets

### 1. Docker Hub учетные данные

Перейдите в ваш GitHub репозиторий:
```
Settings → Secrets and variables → Actions → New repository secret
```

Добавьте следующие секреты:

| Имя | Описание | Пример |
|-----|----------|---------|
| `DOCKER_USERNAME` | Ваш Docker Hub username | `yourusername` |
| `DOCKER_PASSWORD` | Ваш Docker Hub password или access token | `yourpassword` |

### 2. Продакшн Kubernetes конфигурация (опционально)

Если хотите деплоить в продакшн кластер:

| Имя | Описание | Как получить |
|-----|----------|--------------|
| `KUBE_CONFIG` | Base64-encoded kubeconfig | `cat ~/.kube/config \| base64 -w 0` |

## 🏗️ Структура workflow

### Job: `test`
- Проверяет Helm чарты
- Обновляет зависимости
- Тестирует рендеринг

### Job: `deploy`
- Собирает Docker образы
- Создает временный kind кластер
- Деплоит приложение
- Проверяет статус

### Job: `deploy-production` (опционально)
- Деплоит в продакшн кластер
- Требует approval (environment protection)

## 🔄 Триггеры

Workflow запускается при:
- ✅ Push в `main` ветку
- ✅ Push в `develop` ветку
- ✅ Pull Request в `main` ветку
- ✅ Ручной запуск (workflow_dispatch)

## 🐳 Docker образы

Создаются образы для всех сервисов:
- `yourusername/authservice:latest`
- `yourusername/httpservice:latest`
- `yourusername/jwtproxy:latest`
- `yourusername/messegerparody:latest`
- `yourusername/handleservice:latest`

## 📊 Мониторинг

### GitHub Actions
- Перейдите в `Actions` вкладку репозитория
- Следите за выполнением workflow
- Проверяйте логи каждого job

### Локальная проверка
```bash
# Проверка образов в Docker Hub
docker pull yourusername/authservice:latest
docker pull yourusername/httpservice:latest

# Тестирование локально
./deploy-helm-only.sh
```

## 🚨 Устранение неполадок

### Проблемы с Docker Hub
```bash
# Проверка логина
docker login

# Тестирование push
docker push yourusername/test:latest
```

### Проблемы с Helm
```bash
# Проверка чартов
helm lint ./Helm

# Обновление зависимостей
helm dependency update ./Helm
```

### Проблемы с Kubernetes
```bash
# Проверка кластера
kubectl get nodes

# Проверка подов
kubectl get pods -n hybrid-platform
```

## 🔧 Кастомизация

### Изменение Docker Hub реестра
Отредактируйте `.github/workflows/deploy.yml`:
```yaml
- name: 🔐 Login to Docker Hub
  uses: docker/login-action@v3
  with:
    username: ${{ secrets.DOCKER_USERNAME }}
    password: ${{ secrets.DOCKER_PASSWORD }}
    registry: ghcr.io  # Для GitHub Container Registry
```

### Добавление новых сервисов
1. Добавьте сборку образа в job `deploy`
2. Добавьте параметры в Helm установку
3. Обновите values.yaml

### Изменение триггеров
Отредактируйте секцию `on`:
```yaml
on:
  push:
    branches: [ main, develop, feature/* ]
  pull_request:
    branches: [ main, develop ]
  schedule:
    - cron: '0 2 * * *'  # Ежедневно в 2:00
```

## 📚 Полезные ссылки

- [GitHub Actions Documentation](https://docs.github.com/en/actions)
- [Docker Hub](https://hub.docker.com/)
- [Helm Documentation](https://helm.sh/docs/)
- [Kind Documentation](https://kind.sigs.k8s.io/)

## 🎯 Следующие шаги

1. **Настройте GitHub Secrets** (DOCKER_USERNAME, DOCKER_PASSWORD)
2. **Сделайте push в main ветку** для запуска workflow
3. **Настройте продакшн кластер** (если нужно)
4. **Настройте уведомления** в Slack/Discord
5. **Добавьте мониторинг** и алерты

---

**Примечание**: Убедитесь, что у вас есть права на push в main ветку и настройку GitHub Actions в репозитории.
