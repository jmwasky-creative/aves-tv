#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
ADB_BIN="${ADB:-}"
if [ -z "$ADB_BIN" ]; then
  if [ -n "${ANDROID_HOME:-}" ] && [ -x "$ANDROID_HOME/platform-tools/adb" ]; then
    ADB_BIN="$ANDROID_HOME/platform-tools/adb"
  elif [ -x "/Users/a1/Library/Android/sdk/platform-tools/adb" ]; then
    ADB_BIN="/Users/a1/Library/Android/sdk/platform-tools/adb"
  elif command -v adb >/dev/null 2>&1; then
    ADB_BIN="$(command -v adb)"
  fi
fi

ensure_reverse_for_online_devices() {
  local serials
  serials="$("$ADB_BIN" devices 2>/dev/null | awk 'NR>1 && $2=="device" {print $1}')"
  [ -z "$serials" ] && return 0
  for serial in $serials; do
    if ! "$ADB_BIN" -s "$serial" reverse --list 2>/dev/null | grep -q "tcp:53319 tcp:53319"; then
      if "$ADB_BIN" -s "$serial" reverse tcp:53319 tcp:53319 >/dev/null 2>&1; then
        echo "[USB 隧道] 已为设备 $serial 建立/恢复映射 127.0.0.1:53319 -> 电脑预览服务（手机关闭 Wi-Fi 也可自动发现）"
      fi
    fi
  done
}

WATCH_PID=""
cleanup() {
  if [ -n "$WATCH_PID" ]; then
    kill "$WATCH_PID" >/dev/null 2>&1 || true
  fi
}
trap cleanup EXIT INT TERM

if [ -n "$ADB_BIN" ]; then
  ensure_reverse_for_online_devices
  (
    while true; do
      sleep 2
      ensure_reverse_for_online_devices || true
    done
  ) &
  WATCH_PID="$!"
else
  echo "[USB 隧道] 未找到 adb，跳过 USB 端口映射，仅启动电脑预览服务。"
fi

"$ROOT_DIR/gradlew" -p "$ROOT_DIR" :protocol:run
