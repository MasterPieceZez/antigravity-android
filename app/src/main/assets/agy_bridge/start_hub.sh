#!/data/data/com.agy.os/files/usr/bin/bash
set -u

export PREFIX="/data/data/com.agy.os/files/usr"
export HOME="/data/data/com.agy.os/files/home"
export PATH="$HOME/.local/bin:$PREFIX/bin:$PATH"
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
if command -v pkill >/dev/null 2>&1; then
    pkill -f "bridge_server.py" >/dev/null 2>&1 || true
fi

is_lock_stale() {
    local old_pid
    old_pid=$(cat "$LOCK_DIR/pid" 2>/dev/null || true)
    if [ -n "$old_pid" ]; then
        if ! kill -0 "$old_pid" 2>/dev/null; then
            return 0
        fi
        return 1
    fi
    # Missing/empty pid file: treat lock as stale if older than 15 seconds
    local now mtime
    now=$(date +%s 2>/dev/null || echo 0)
    mtime=$(stat -c %Y "$LOCK_DIR" 2>/dev/null || echo 0)
    if [ "$now" -gt 0 ] && [ "$mtime" -gt 0 ] && [ $((now - mtime)) -ge 15 ]; then
        return 0
    fi
    return 1
}

is_hub_running() {
    if command -v pgrep >/dev/null 2>&1; then
        if pgrep -f "[/]agy --hub" >/dev/null 2>&1; then
            return 0
        fi
    fi
    local hub_pid
    hub_pid=$(cat "$BRIDGE_DIR/hub.pid" 2>/dev/null || true)
    if [ -n "$hub_pid" ] && kill -0 "$hub_pid" 2>/dev/null; then
        if [ -r "/proc/$hub_pid/cmdline" ]; then
            if tr '\0' ' ' < "/proc/$hub_pid/cmdline" 2>/dev/null | grep -q -- "--hub"; then
                return 0
            fi
        else
            return 0
        fi
    fi
    return 1
}

# Start Antigravity Hub if installed and not already running (serialize concurrent launches)
if [ -f "$AGY_BIN" ]; then
    chmod +x "$AGY_BIN" 2>/dev/null || true

    ACQUIRED=0
    for _ in 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15; do
        if mkdir "$LOCK_DIR" 2>/dev/null; then
            printf '%s\n' "$$" > "$LOCK_DIR/pid" 2>/dev/null || true
            ACQUIRED=1
            break
        fi
        if is_lock_stale; then
            rm -rf "$LOCK_DIR" 2>/dev/null || true
            continue
        fi
        sleep 0.2
    done

    if [ "$ACQUIRED" -eq 1 ]; then
        trap 'rm -rf "$LOCK_DIR" 2>/dev/null || true' EXIT
    else
        exit 0
    fi

    if ! is_hub_running; then
        rm -f "$BRIDGE_DIR/hub.url" 2>/dev/null || true
        if [ -f "$AGY_LOG" ] && [ -s "$AGY_LOG" ]; then
            mv -f "$AGY_LOG" "$AGY_LOG.prev" 2>/dev/null || true
        fi
        : > "$AGY_LOG"
        if command -v setsid >/dev/null 2>&1; then
            setsid "$AGY_BIN" --hub --hub-port=4400 > "$AGY_LOG" 2>&1 < /dev/null &
        else
            nohup "$AGY_BIN" --hub --hub-port=4400 > "$AGY_LOG" 2>&1 < /dev/null &
        fi
        printf '%s\n' "$!" > "$BRIDGE_DIR/hub.pid" 2>/dev/null || true
    fi
fi
