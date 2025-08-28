#!/bin/bash

echo "🐳 Создание тестовых образов Docker..."

# Создаем простой образ для AuthService
echo "🔐 Создание образа AuthService..."
cat > Dockerfile.auth << 'EOF'
FROM nginx:alpine
RUN echo "AuthService is running" > /usr/share/nginx/html/index.html
EXPOSE 8081
CMD ["nginx", "-g", "daemon off;"]
EOF

docker build -f Dockerfile.auth -t authservice:latest .

# Создаем простой образ для HTTPService
echo "🌐 Создание образа HTTPService..."
cat > Dockerfile.http << 'EOF'
FROM nginx:alpine
RUN echo "HTTPService is running" > /usr/share/nginx/html/index.html
EXPOSE 8080
CMD ["nginx", "-g", "daemon off;"]
EOF

docker build -f Dockerfile.http -t httpservice:latest .

# Создаем простой образ для JwtProxy
echo "🔑 Создание образа JwtProxy..."
cat > Dockerfile.jwt << 'EOF'
FROM nginx:alpine
RUN echo "JwtProxy is running" > /usr/share/nginx/html/index.html
EXPOSE 8084
CMD ["nginx", "-g", "daemon off;"]
EOF

docker build -f Dockerfile.jwt -t jwtproxy:latest .

# Создаем простой образ для MessegerParody
echo "💬 Создание образа MessegerParody..."
cat > Dockerfile.messeger << 'EOF'
FROM nginx:alpine
RUN echo "MessegerParody is running" > /usr/share/nginx/html/index.html
EXPOSE 8085
CMD ["nginx", "-g", "daemon off;"]
EOF

docker build -f Dockerfile.messeger -t messegerparody:latest .

# Создаем простой образ для HandleService
echo "🔄 Создание образа HandleService..."
cat > Dockerfile.handle << 'EOF'
FROM nginx:alpine
RUN echo "HandleService is running" > /usr/share/nginx/html/index.html
EXPOSE 8082
CMD ["nginx", "-g", "daemon off;"]
EOF

docker build -f Dockerfile.handle -t handleservice:latest .

# Загружаем образы в kind кластер
echo "📤 Загрузка образов в kind кластер..."
kind load docker-image authservice:latest --name hybrid-cluster
kind load docker-image httpservice:latest --name hybrid-cluster
kind load docker-image jwtproxy:latest --name hybrid-cluster
kind load docker-image messegerparody:latest --name hybrid-cluster
kind load docker-image handleservice:latest --name hybrid-cluster

# Очищаем временные файлы
echo "🧹 Очистка временных файлов..."
rm -f Dockerfile.auth Dockerfile.http Dockerfile.jwt Dockerfile.messeger Dockerfile.handle

echo "✅ Тестовые образы созданы и загружены в кластер!"
echo ""
echo "📋 Доступные образы:"
docker images | grep -E "(auth|http|jwt|messeger|handle)"
