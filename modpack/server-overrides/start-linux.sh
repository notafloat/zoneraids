#!/usr/bin/env bash
# Zone Raids server launcher: installs Forge on the first run, then starts the server.
set -euo pipefail
cd "$(dirname "$0")"

FORGE="$(tr -d '[:space:]' < forge-version.txt)"          # e.g. 1.20.1-47.4.0
ARGS="libraries/net/minecraftforge/forge/${FORGE}/unix_args.txt"
JAVA="${JAVA:-java}"

if ! "$JAVA" -version 2>&1 | grep -q 'version "17'; then
  echo "Нужна Java 17 (Temurin 17). Укажите путь: JAVA=/path/to/java ./start-linux.sh" >&2
  "$JAVA" -version || true
  exit 1
fi

if [ ! -f "$ARGS" ]; then
  echo "Устанавливаю Forge ${FORGE}..."
  INSTALLER="forge-${FORGE}-installer.jar"
  curl -fL --retry 4 -o "$INSTALLER" \
    "https://maven.minecraftforge.net/net/minecraftforge/forge/${FORGE}/forge-${FORGE}-installer.jar"
  "$JAVA" -jar "$INSTALLER" --installServer
  rm -f "$INSTALLER" "$INSTALLER.log"
fi

if ! grep -qs '^eula=true' eula.txt; then
  echo "Прочитайте https://aka.ms/MinecraftEULA и запишите eula=true в eula.txt, затем запустите снова."
  echo "eula=false" > eula.txt
  exit 1
fi

# Heap from installed RAM, leaving room for the OS (and a game client on the same machine).
if [ -z "${HEAP:-}" ]; then
  RAM_GB=$(awk '/MemTotal/ {printf "%d", $2/1024/1024 + 0.5}' /proc/meminfo)
  HEAP=3G
  [ "$RAM_GB" -ge 12 ] && HEAP=4G
  [ "$RAM_GB" -ge 16 ] && HEAP=6G
fi
echo "Server heap: $HEAP (set HEAP=4G to override)"

while true; do
  "$JAVA" -Xms"$HEAP" -Xmx"$HEAP" @zoneraids_jvm_args.txt @"$ARGS" nogui "$@"
  [ "${AUTO_RESTART:-0}" = "1" ] || break
  echo "Сервер остановлен, перезапуск через 10 секунд (Ctrl+C для выхода)..."
  sleep 10
done
