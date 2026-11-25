#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail
umask 077
source_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
install_root="${JARVIS_CALLS_INSTALL:-$HOME/.local/lib/jarvis-calls}"
for tool in node npm openssl python3 openclaw; do command -v "$tool" >/dev/null || { echo "Missing required tool: $tool" >&2; exit 1; }; done
if [[ -e "$install_root" ]]; then echo "Destination exists; review and update source manually: $install_root" >&2; exit 1; fi
mkdir -p "$install_root"
cp -R "$source_root/server" "$source_root/plugin" "$install_root/"
# Never copy development node_modules or an upstream OpenClaw instance.
if [[ -d "$install_root/server/node_modules" ]]; then rm -r "$install_root/server/node_modules"; fi
(cd "$install_root/server" && npm ci --omit=dev --ignore-scripts)
node "$install_root/server/src/main.mjs" init
printf 'Installed service source. Configure TLS and the bind address using jarvis-calls/README.md.\n'
printf 'No OpenClaw config, credentials, Telegram, Composio, models or service startup changed.\n'
