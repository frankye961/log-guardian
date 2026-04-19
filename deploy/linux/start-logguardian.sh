#!/usr/bin/env bash
set -euo pipefail

APP_DIR="/opt/logguardian"
ENV_FILE="/etc/logguardian/logguardian.env"
JAR_PATH="$APP_DIR/logguardian.jar"

if [[ -f "$ENV_FILE" ]]; then
  # shellcheck disable=SC1090
  source "$ENV_FILE"
fi

if [[ ! -f "$JAR_PATH" ]]; then
  echo "Missing application jar at $JAR_PATH" >&2
  exit 1
fi

JAVA_BIN="${JAVA_BIN:-java}"
JAVA_OPTS="${JAVA_OPTS:-}"
SPRING_ARGS="${SPRING_ARGS:-}"

cd "$APP_DIR"
exec "$JAVA_BIN" $JAVA_OPTS -jar "$JAR_PATH" $SPRING_ARGS
