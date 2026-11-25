#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail
umask 077
config_root="${JARVIS_CALLS_HOME:-$HOME/.config/jarvis-calls}"
host="${1:?Usage: create-certificate.sh TABLET_TAILSCALE_IP_OR_MAGICDNS}"
[[ "$host" =~ ^[a-zA-Z0-9.-]+$ ]] || { echo 'Invalid host' >&2; exit 1; }
mkdir -p "$config_root"
[[ ! -e "$config_root/server.key" && ! -e "$config_root/server.crt" ]] || { echo 'Certificate exists; refusing overwrite' >&2; exit 1; }
if [[ "$host" =~ ^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$ ]]; then san="IP:$host"; else san="DNS:$host"; fi
openssl req -x509 -newkey rsa:3072 -sha256 -nodes -days 365 -subj '/CN=Jarvis tablet' -addext "subjectAltName=$san" -keyout "$config_root/server.key" -out "$config_root/server.crt"
openssl x509 -in "$config_root/server.crt" -noout -fingerprint -sha256
