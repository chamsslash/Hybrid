# 🚀 Автоматический деплой Hybrid Platform

## 🎯 Что настроено

### ✅ GitHub Actions Workflow
- **Файл**: `.github/workflows/deploy.yml`
- **Триггеры**: Push в main/develop, Pull Request, ручной запуск
- **Автоматизация**: Сборка, тестирование, деплой

### ✅ Helm чарты
- **Структура**: Основной чарт + подчарты для каждого сервиса
- **Управление**: Единый источник истины для конфигураций
- **Версионирование**: Легкое обновление и откат изменений

### ✅ Скрипты автоматизации
- `deploy-helm-only.sh` - Деплой через Helm (рекомендуется)
- `create-test-images.sh` - Создание тестовых образов
- `test-workflow.sh` - Локальное тестирование workflow

## 🚀 Как использовать

### 1. Настройка GitHub Secrets
```bash
# В GitHub репозитории:
Settings → Secrets and variables → Actions → New repository secret

DOCKER_USERNAME=your_username
DOCKER_PASSWORD=your_password_or_token
```

### 2. Автоматический деплой
```bash
# Просто сделайте push в main ветку
git add .
git commit -m "🚀 Автоматический деплой"
git push origin main
```

### 3. Ручной запуск
- Перейдите в GitHub → Actions
- Выберите workflow "Deploy to Kubernetes"
- Нажмите "Run workflow"

## 🏗️ Что происходит автоматически

1. **🔍 Тестирование**
   - Проверка Helm чартов
   - Валидация конфигураций
   - Тестирование рендеринга

2. **🐳 Сборка образов**
   - AuthService, HTTPService, JwtProxy
   - MessegerParody, HandleService
   - Загрузка в Docker Hub

3. **🏗️ Создание кластера**
   - Временный kind кластер
   - Настройка kubectl и Helm

4. **🚀 Деплой**
   - Установка через Helm
   - Проверка статуса
   - Очистка ресурсов

## 📊 Мониторинг

### GitHub Actions
- **Вкладка Actions** в репозитории
- **Логи выполнения** каждого job
- **Статус деплоя** в реальном времени

### Локальная проверка
```bash
# Тестирование workflow
./test-workflow.sh

# Локальный деплой
./deploy-helm-only.sh

# Проверка статуса
kubectl get all -n hybrid-platform
```

## 🔧 Управление

### Helm команды
```bash
# Установка
helm install hybrid-platform ./Helm -n hybrid-platform --create-namespace

# Обновление
helm upgrade hybrid-platform ./Helm -n hybrid-platform

# Удаление
helm uninstall hybrid-platform -n hybrid-platform

# Статус
helm status hybrid-platform -n hybrid-platform
```

### Kubernetes команды
```bash
# Поды
kubectl get pods -n hybrid-platform

# Сервисы
kubectl get services -n hybrid-platform

# Логи
kubectl logs -n hybrid-platform -l app=authservice

# Port-forward
kubectl port-forward -n hybrid-platform svc/authservice 8081:8081
```

## 🚨 Устранение неполадок

### Проблемы с workflow
1. **Проверьте GitHub Secrets** (DOCKER_USERNAME, DOCKER_PASSWORD)
2. **Проверьте логи** в GitHub Actions
3. **Запустите локальное тестирование** (`./test-workflow.sh`)

### Проблемы с деплоем
1. **Проверьте Helm чарты** (`helm lint ./Helm`)
2. **Обновите зависимости** (`helm dependency update ./Helm`)
3. **Проверьте образы** в Docker Hub

### Проблемы с кластером
1. **Проверьте статус узлов** (`kubectl get nodes`)
2. **Проверьте события** (`kubectl get events`)
3. **Проверьте ресурсы** (`kubectl describe node`)

## 📚 Документация

- **GITHUB-SETUP.md** - Подробная настройка GitHub Actions
- **KUBERNETES-SETUP.md** - Настройка Kubernetes кластера
- **README-DEPLOYMENT.md** - Правильный подход к деплою

## 🎯 Следующие шаги

1. **Настройте GitHub Secrets**
2. **Сделайте первый push** для тестирования
3. **Настройте продакшн кластер** (если нужно)
4. **Добавьте мониторинг** и алерты
5. **Настройте уведомления** в Slack/Discord

## 🔄 Workflow триггеры

| Событие | Условие | Действие |
|---------|---------|----------|
| Push в main | Автоматически | Полный деплой + продакшн |
| Push в develop | Автоматически | Тестирование + деплой |
| Pull Request | Автоматически | Только тестирование |
| Ручной запуск | По требованию | Полный деплой |

---

**🎉 Готово к автоматическому деплою!**

Просто настройте GitHub Secrets и сделайте push в main ветку.
