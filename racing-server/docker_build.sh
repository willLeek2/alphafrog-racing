#!/usr/bin/env bash
set -euo pipefail

RACING_REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RACING_IMAGE_TAG="${1:-${IMAGE_TAG:-racing-server:latest}}"

# Dockerfile 使用本次构建的可执行 jar；构建过程不跳过测试。
(cd "$RACING_REPO_ROOT" && ./mvnw -B package)

# 代理沿用调用环境，避免在仓库中固定某台机器的网络配置。
docker build --build-arg http_proxy --build-arg https_proxy \
  -t "$RACING_IMAGE_TAG" "$RACING_REPO_ROOT/racing-server"
