#!/data/data/com.termux/files/usr/bin/bash
set -u

export PREFIX="/data/data/com.termux/files/usr"
export HOME="/data/data/com.termux/files/home"
export PATH="$HOME/.local/bin:$PREFIX/bin:$PATH"
export LD_LIBRARY_PATH="$PREFIX/lib"
export TMPDIR="$PREFIX/tmp"
export TERM="xterm-256color"
export LANG="en_US.UTF-8"
export AGY_ENABLE_HUB=1
export AGY_CLI_DISABLE_AUTO_UPDATE=1

BRIDGE_DIR="$HOME/.agy_bridge"
AGY_BIN="$HOME/.local/bin/agy"
AGY_LOG="$BRIDGE_DIR/agy.log"
BRIDGE_LOG="$BRIDGE_DIR/bridge.log"

mkdir -p "$BRIDGE_DIR" "$TMPDIR" "$HOME/storage"

# Ensure direct phone storage symlink exists
if [ ! -e "$HOME/storage/shared" ]; then
    ln -s /storage/emulated/0 "$HOME/storage/shared" 2>/dev/null || true
fi

# Start Antigravity Hub if installed and not already running
if [ -f "$AGY_BIN" ]; then
    chmod +x "$AGY_BIN" 2>/dev/null || true
    if ! pgrep -f "agy --hub" >/dev/null 2>&1; then
        : > "$AGY_LOG"
        nohup "$AGY_BIN" --hub --hub-port=4400 > "$AGY_LOG" 2>&1 < /dev/null &
    fi
fi

# Start Python bridge server if python3 is installed and not already running
if command -v python3 >/dev/null 2>&1 && [ -f "$BRIDGE_DIR/bridge_server.py" ]; then
    if ! pgrep -f "bridge_server.py" >/dev/null 2>&1; then
        nohup python3 "$BRIDGE_DIR/bridge_server.py" > "$BRIDGE_LOG" 2>&1 < /dev/null &
    fi
fi
