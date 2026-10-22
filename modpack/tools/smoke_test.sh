#!/usr/bin/env bash
# Boots the built server once, checks that every mod loads, rolls the crate loot tables,
# prints a memory report and stops. Usage: smoke_test.sh <server-dir> <report-dir>
set -uo pipefail
SERVER="$(cd "$1" && pwd)"
REPORT="$(mkdir -p "$2" && cd "$2" && pwd)"
PACK_GLUE="$(cd "$(dirname "$0")/../glue" && pwd)"
cd "$SERVER"

FORGE="$(tr -d '[:space:]' < forge-version.txt)"
curl -fsSL --retry 4 -o installer.jar \
  "https://maven.minecraftforge.net/net/minecraftforge/forge/${FORGE}/forge-${FORGE}-installer.jar"
java -jar installer.jar --installServer > "$REPORT/forge-install.log" 2>&1 || { tail -50 "$REPORT/forge-install.log"; exit 1; }
rm -f installer.jar installer.jar.log

echo "eula=true" > eula.txt
# CI runners have less RAM than a real host.
sed -i -e 's/^-Xms.*/-Xms1G/' -e 's/^-Xmx.*/-Xmx4G/' -e '/AlwaysPreTouch/d' zoneraids_jvm_args.txt

rm -f console.in && mkfifo console.in
exec 3<>console.in
java @zoneraids_jvm_args.txt @"libraries/net/minecraftforge/forge/${FORGE}/unix_args.txt" nogui \
  < console.in > "$REPORT/console.log" 2>&1 &
PID=$!

status=fail
for _ in $(seq 1 900); do
  if grep -q 'Done (' "$REPORT/console.log"; then status=up; break; fi
  if ! kill -0 "$PID" 2>/dev/null; then break; fi
  # After a mod loading crash the JVM can hang instead of exiting.
  if grep -qE 'Failed to start the minecraft server|This crash report has been saved to' "$REPORT/console.log"; then break; fi
  sleep 1
done

if [ "$status" = up ]; then
  say() { echo "$1" >&3; sleep "${2:-2}"; }
  # Zone Raids builds the hub and the Cordon map on the first start of a world; wait for it if it runs.
  if grep -qi 'Zone Raids: .*build' "$REPORT/console.log"; then
    for _ in $(seq 1 900); do
      grep -qi 'Zone Raids: finished building cordon' "$REPORT/console.log" && break
      kill -0 "$PID" 2>/dev/null || break
      sleep 1
    done
  fi
  # Every modded item in the crate loot must exist: "give" rejects unknown ids with "Unknown item".
  for id in $(grep -rhoE '"name": "[a-z0-9_]+:[a-z0-9_/]+"' "$PACK_GLUE/data" | cut -d'"' -f4 | grep -v '^minecraft:' | sort -u); do
    echo "give @a $id" >&3
  done
  sleep 3
  # Item models alone do not prove an id is registered (some jars ship unused models): probe every id.
  IDS_FILE="$(dirname "$REPORT")/item-ids.txt"
  if [ -f "$IDS_FILE" ]; then
    echo "say PROBE_START" >&3
    for id in $(grep -oE '\b[a-z0-9_]+:[a-z0-9_/]+\b' "$IDS_FILE" | grep -vE '^(minecraft|tacz):' | sort -u); do
      echo "give @a $id" >&3
    done
    for id in $(grep -oE '\banomalyzone:anomaly_[a-z0-9_]+\b' "$IDS_FILE" | sort -u); do
      echo "setblock 0 -58 40 $id replace" >&3
    done
    echo "say PROBE_END" >&3
    sleep 8
  fi
  say "raidadmin map list" 3
  say "forceload add 0 0" 5
  i=0
  for table in common military medical; do
    for roll in 1 2 3; do
      z=$((i * 2)); i=$((i + 1))
      say "setblock 0 -60 $z minecraft:chest replace" 1
      say "loot insert 0 -60 $z loot zoneraids:crates/$table" 1
      say "data get block 0 -60 $z Items" 1
    done
  done
  say "spark health --memory" 10
  say "stop" 1
  wait "$PID"
  echo "exit code $?"
else
  kill "$PID" 2>/dev/null
  wait "$PID" 2>/dev/null
fi
exec 3>&-

cp -f logs/latest.log "$REPORT/latest.log" 2>/dev/null
cp -f logs/debug.log "$REPORT/debug.log" 2>/dev/null
cp -rf crash-reports "$REPORT/" 2>/dev/null
mkdir -p "$REPORT/generated-config"
cp -rf config "$REPORT/generated-config/config" 2>/dev/null
cp -rf world/serverconfig "$REPORT/generated-config/serverconfig" 2>/dev/null

echo "===== Mod list ====="
grep -E 'Found mod file|Loading [0-9]+ mods' "$REPORT/debug.log" 2>/dev/null | tail -5
echo "===== Errors and warnings worth reading ====="
grep -nE 'ERROR|Exception|Missing or unsupported mandatory dependencies|Couldn.t parse|Failed to load|Mixin apply failed' \
  "$REPORT/console.log" | grep -v 'Unable to find spawn biome' | head -150

if [ "$status" != up ]; then
  echo "SERVER DID NOT START"
  tail -120 "$REPORT/console.log"
  exit 1
fi

echo "===== Zone Raids ====="
grep -iE 'Zone Raids|zoneraids.*(map|cordon|hub)|Кордон' "$REPORT/console.log" | grep -v 'Found mod' | head -40

echo "===== Loot rolls ====="
grep -E 'has the following block data' "$REPORT/console.log" | sed 's/.*has the following block data: //' | cut -c1-400
fail=0
grep -q 'tacz:' "$REPORT/console.log" || { echo "No TaCZ items rolled from crates"; fail=1; }
grep -q 'firstaid:' "$REPORT/console.log" || { echo "No First Aid items rolled from crates"; fail=1; }
grep -qE "Couldn't parse element.*zoneraids|zoneraids:crates.*(error|invalid)" "$REPORT/console.log" && { echo "Crate loot table failed to load"; fail=1; }
LOOT_IDS="$(grep -rhoE '"name": "[a-z0-9_]+:[a-z0-9_/]+"' "$PACK_GLUE/data" | cut -d'"' -f4 | sort -u)"
UNKNOWN="$(grep -oE "Unknown item '[^']*'" "$REPORT/console.log" | cut -d"'" -f2 | sort -u)"
BAD="$(comm -12 <(echo "$LOOT_IDS") <(echo "$UNKNOWN"))"
if [ -n "$BAD" ]; then echo "Unknown items in crate loot:"; echo "$BAD"; fail=1; fi
if [ -f "$IDS_FILE" ]; then
  grep -oE '\b[a-z0-9_]+:[a-z0-9_/]+\b' "$IDS_FILE" | grep -vE '^(minecraft|tacz):' | sort -u \
    | comm -23 - <(echo "$UNKNOWN") > "$REPORT/registered-items.txt"
  echo "===== Item models without a registered item (do not use these ids) ====="
  echo "$UNKNOWN" | tr '\n' ' '; echo
  echo "===== Anomaly blocks that are real blocks ====="
  grep -oE "Unknown block type '[^']*'" "$REPORT/console.log" | cut -d"'" -f2 | sort -u > "$REPORT/unknown-blocks.txt"
  grep -oE '\banomalyzone:anomaly_[a-z0-9_]+\b' "$IDS_FILE" | sort -u | comm -23 - "$REPORT/unknown-blocks.txt" | tr '\n' ' '; echo
fi
echo "===== Memory ====="
grep -A12 -iE 'Memory usage|Memory Usage' "$REPORT/console.log" | head -30
exit $fail
