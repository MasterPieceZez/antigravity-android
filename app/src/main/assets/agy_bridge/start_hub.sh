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
LOCK_DIR="$BRIDGE_DIR/hub.lock"

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

# Start Antigravity Hub if installed and not already running (serialize concurrent launches)
if [ -f "$AGY_BIN" ]; then
    chmod +x "$AGY_BIN" 2>/dev/null || true

    ACQUIRED=0
    for _ in 1 2 3 4 5 6 7 8 9 10; do
        if mkdir "$LOCK_DIR" 2>/dev/null; then
            printf '%s\n' "$$" > "$LOCK_DIR/pid" 2>/dev/null || true
            ACQUIRED=1
            break
        fi
        OLD_PID=$(cat "$LOCK_DIR/pid" 2>/dev/null || true)
        if [ -n "$OLD_PID" ] && ! kill -0 "$OLD_PID" 2>/dev/null; then
            rm -rf "$LOCK_DIR" 2>/dev/null || true
            continue
        fi
        sleep 0.2
    done

    if [ "$ACQUIRED" -eq 1 ]; then
        trap 'rm -rf "$LOCK_DIR" 2>/dev/null || true' EXIT
    fi

    if ! pgrep -f "[/]agy --hub" >/dev/null 2>&1; then
        rm -f "$BRIDGE_DIR/hub.url" 2>/dev/null || true
        if [ -f "$AGY_LOG" ] && [ -s "$AGY_LOG" ]; then
            mv -f "$AGY_LOG" "$AGY_LOG.prev" 2>/dev/null || true
        fi
        : > "$AGY_LOG"
        nohup "$AGY_BIN" --hub --hub-port=4400 > "$AGY_LOG" 2>&1 < /dev/null &
        printf '%s\n' "$!" > "$BRIDGE_DIR/hub.pid" 2>/dev/null || true
    fi
fi
