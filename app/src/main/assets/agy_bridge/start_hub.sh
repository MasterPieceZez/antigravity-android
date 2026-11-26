#!/data/data/com.agy.os/files/usr/bin/bash
set -u

export PREFIX="/data/data/com.agy.os/files/usr"
export HOME="/data/data/com.agy.os/files/home"
export PATH="$HOME/.local/bin:$PREFIX/bin:$PATH"
export LD_LIBRARY_PATH="$PREFIX/lib"
export TMPDIR="$PREFIX/tmp"
export TERM="xterm-256color"
export LANG="en_US.UTF-8"
export RES_OPTIONS="attempts:2 timeout:2"
export AGY_ENABLE_HUB=1
export AGY_CLI_DISABLE_AUTO_UPDATE=1

BRIDGE_DIR="$HOME/.agy_bridge"
AGY_BIN="$HOME/.local/bin/agy"
AGY_LOG="$BRIDGE_DIR/agy.log"

mkdir -p "$BRIDGE_DIR" "$TMPDIR" "$HOME/storage"

# Ensure fast localhost DNS resolution (avoids IPv6 stalls on mobile data)
HOSTS_FILE="$PREFIX/etc/hosts"
if [ -d "$PREFIX/etc" ]; then
    if [ ! -f "$HOSTS_FILE" ] || ! grep -q "127.0.0.1.*localhost" "$HOSTS_FILE" 2>/dev/null; then
        printf "127.0.0.1 localhost\n::1 localhost ip6-localhost\n" >> "$HOSTS_FILE" 2>/dev/null || true
    fi
fi

# Ensure direct phone storage symlink exists
if [ ! -e "$HOME/storage/shared" ]; then
    ln -s /storage/emulated/0 "$HOME/storage/shared" 2>/dev/null || true
fi

# Stop any legacy Python bridge daemon (now handled natively in Kotlin)
pkill -f "bridge_server.py" >/dev/null 2>&1 || true

# Start Antigravity Hub if installed and not already running
if [ -f "$AGY_BIN" ]; then
    chmod +x "$AGY_BIN" 2>/dev/null || true
    if ! pgrep -f "agy --hub" >/dev/null 2>&1; then
        : > "$AGY_LOG"
        nohup "$AGY_BIN" --hub --hub-port=4400 > "$AGY_LOG" 2>&1 < /dev/null &
    fi
fi
