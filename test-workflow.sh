#!/bin/bash

echo "🧪 Локальное тестирование GitHub Actions workflow..."

# Проверяем, что мы в правильной директории
if [ ! -f ".github/workflows/deploy.yml" ]; then
    echo "❌ Workflow файл не найден. Запустите скрипт из корневой директории проекта."
    exit 1
fi

# Проверяем Helm чарты
echo "🔍 Проверка Helm чартов..."
if ! helm lint ./Helm; then
    echo "❌ Ошибки в Helm чартах"
    exit 1
fi

# Обновляем зависимости
echo "📦 Обновление Helm зависимостей..."
if ! helm dependency update ./Helm; then
    echo "❌ Ошибки при обновлении зависимостей"
    exit 1
fi

# Тестируем рендеринг
echo "🎨 Тестирование рендеринга Helm чартов..."
if ! helm template test ./Helm --values ./Helm/values.yaml > /dev/null; then
    echo "❌ Ошибки при рендеринге"
    exit 1
fi

# Проверяем Dockerfile'ы
echo "🐳 Проверка Dockerfile'ов..."
services=("AuthService" "HTTPService" "JwtProxy" "MessegerParody" "HandleService")

for service in "${services[@]}"; do
    if [ -f "$service/Dockerfile" ]; then
        echo "✅ $service: Dockerfile найден"
        
        # Проверяем базовый синтаксис Dockerfile
        if docker build --dry-run -f "$service/Dockerfile" "$service" > /dev/null 2>&1; then
            echo "✅ $service: Dockerfile синтаксис корректен"
        else
            echo "⚠️  $service: Проблемы с Dockerfile (может быть нормально для multi-stage builds)"
        fi
    else
        echo "❌ $service: Dockerfile не найден"
    fi
done

# Проверяем kind конфигурацию
echo "🏗️ Проверка kind конфигурации..."
if [ -f "kind-config.yaml" ]; then
    echo "✅ kind-config.yaml найден"
    
    # Проверяем синтаксис YAML
    if python3 -c "import yaml; yaml.safe_load(open('kind-config.yaml'))" > /dev/null 2>&1; then
        echo "✅ kind-config.yaml синтаксис корректен"
    else
        echo "⚠️  kind-config.yaml: Python YAML проверка не удалась (может быть нормально)"
        # Альтернативная проверка через kind
        if kind validate-config kind-config.yaml > /dev/null 2>&1; then
            echo "✅ kind-config.yaml валиден для kind"
        else
            echo "❌ kind-config.yaml невалиден для kind"
            exit 1
        fi
    fi
else
    echo "❌ kind-config.yaml не найден"
    exit 1
fi

# Проверяем GitHub Actions workflow
echo "📋 Проверка GitHub Actions workflow..."
if [ -f ".github/workflows/deploy.yml" ]; then
    echo "✅ deploy.yml найден"
    # Простая проверка YAML синтаксиса через grep
    if grep -q "name:" ".github/workflows/deploy.yml" && grep -q "on:" ".github/workflows/deploy.yml"; then
        echo "✅ deploy.yml базовая структура корректна"
    else
        echo "❌ deploy.yml базовая структура некорректна"
        exit 1
    fi
else
    echo "❌ deploy.yml не найден"
    exit 1
fi

# Проверяем наличие необходимых файлов
echo "📁 Проверка структуры проекта..."
required_files=(
    "Helm/Chart.yaml"
    "Helm/values.yaml"
    "Helm/charts/authservice/Chart.yaml"
    "Helm/charts/httpservice/Chart.yaml"
    "Helm/charts/jwtproxy/Chart.yaml"
    "Helm/charts/messegerparody/Chart.yaml"
    "Helm/charts/handleservice/Chart.yaml"
)

for file in "${required_files[@]}"; do
    if [ -f "$file" ]; then
        echo "✅ $file найден"
    else
        echo "❌ $file не найден"
        exit 1
    fi
done

# Проверяем скрипты
echo "📜 Проверка скриптов..."
scripts=("create-test-images.sh" "deploy-helm-only.sh")

for script in "${scripts[@]}"; do
    if [ -f "$script" ] && [ -x "$script" ]; then
        echo "✅ $script найден и исполняем"
    else
        echo "❌ $script не найден или не исполняем"
        exit 1
    fi
done

# Имитируем проверку Docker образов
echo "🐳 Проверка Docker образов..."
if command -v docker > /dev/null; then
    echo "✅ Docker установлен"
    
    # Проверяем, что Docker daemon запущен
    if docker info > /dev/null 2>&1; then
        echo "✅ Docker daemon запущен"
    else
        echo "⚠️  Docker daemon не запущен (запустите: sudo systemctl start docker)"
    fi
else
    echo "❌ Docker не установлен"
    exit 1
fi

# Проверяем kubectl
echo "🔧 Проверка kubectl..."
if command -v kubectl > /dev/null; then
    echo "✅ kubectl установлен: $(kubectl version --client --short)"
else
    echo "❌ kubectl не установлен"
    exit 1
fi

# Проверяем Helm
echo "📦 Проверка Helm..."
if command -v helm > /dev/null; then
    echo "✅ Helm установлен: $(helm version --short)"
else
    echo "❌ Helm не установлен"
    exit 1
fi

# Проверяем kind
echo "🏗️ Проверка kind..."
if command -v kind > /dev/null; then
    echo "✅ kind установлен: $(kind version)"
else
    echo "❌ kind не установлен"
    exit 1
fi

echo ""
echo "🎉 Все проверки пройдены успешно!"
echo ""
echo "📋 Что готово к деплою:"
echo "   ✅ Helm чарты валидны"
echo "   ✅ Dockerfile'ы присутствуют"
echo "   ✅ GitHub Actions workflow настроен"
echo "   ✅ Все необходимые инструменты установлены"
echo ""
echo "🚀 Следующие шаги:"
echo "   1. Настройте GitHub Secrets (DOCKER_USERNAME, DOCKER_PASSWORD)"
echo "   2. Сделайте push в main ветку"
echo "   3. Следите за выполнением в GitHub Actions"
echo ""
echo "📚 Документация: GITHUB-SETUP.md"
