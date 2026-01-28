#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

echo "Building images..."
docker compose -f docker-compose.local.yml build

echo "Starting stack..."
docker compose -f docker-compose.local.yml up
