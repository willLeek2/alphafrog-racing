#!/usr/bin/env bash
set -euo pipefail

RACING_REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RACING_JAVA="${JAVA_HOME:+${JAVA_HOME}/bin/}java"

exec "$RACING_JAVA" -Dloader.main=world.willfrog.racing.server.cli.PromptAdminClient \
  -cp "$RACING_REPO_ROOT/racing-server/target/racing-server-0.1.0-SNAPSHOT.jar" \
  org.springframework.boot.loader.launch.PropertiesLauncher "$@"
