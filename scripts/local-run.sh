#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

COMPOSE_FILE="docker-compose.yml"

echo "Building images..."
docker compose -f "$COMPOSE_FILE" build

echo "Starting database for migrations..."
docker compose -f "$COMPOSE_FILE" up -d postgres

echo "Running liquibase (messegerparody)..."
docker compose -f "$COMPOSE_FILE" run --rm --no-deps \
  -e SPRING_PROFILES_ACTIVE=liquibase \
  messegerparody

echo "Starting stack..."
docker compose -f "$COMPOSE_FILE" up
