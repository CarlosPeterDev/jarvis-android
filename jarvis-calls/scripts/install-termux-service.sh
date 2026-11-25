#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail
umask 077
command -v sv >/dev/null && command -v svlogd >/dev/null || { echo 'termux-services is required for this optional supervisor setup' >&2; exit 1; }
service_root="${PREFIX:?}/var/service/jarvis-calls"
[[ ! -e "$service_root" ]] || { echo 'Jarvis service already exists; refusing overwrite' >&2; exit 1; }
[[ -f "$HOME/.config/jarvis-calls/config.json" && -f "$HOME/.config/jarvis-calls/server.key" ]] || { echo 'Configure the companion and its TLS certificate first' >&2; exit 1; }
mkdir -p "$HOME/.cache" "$HOME/.local/state/jarvis-calls/log"
staging="$(mktemp -d "$HOME/.cache/jarvis-service-XXXXXX")"
trap 'if [[ -d "$staging" ]]; then rm -r "$staging"; fi' EXIT
mkdir -p "$staging/log"
cat > "$staging/run" <<'RUN'
#!/data/data/com.termux/files/usr/bin/bash
exec 2>&1
exec "$PREFIX/bin/node" "$HOME/.local/lib/jarvis-calls/server/src/main.mjs" serve
RUN
cat > "$staging/log/run" <<'RUN'
#!/data/data/com.termux/files/usr/bin/bash
exec svlogd -tt "$HOME/.local/state/jarvis-calls/log"
RUN
chmod 700 "$staging/run" "$staging/log/run"
mv "$staging" "$service_root"
for attempt in {1..10}; do
  [[ -p "$service_root/supervise/ok" ]] && break
  sleep 1
done
sv up "$service_root"
sv status "$service_root"
printf 'Installed only the Jarvis companion supervisor. Android may still stop Termux.\n'
