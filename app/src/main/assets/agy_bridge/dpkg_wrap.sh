#!/data/data/com.agy.os/files/usr/bin/bash
set -euo pipefail

PREFIX="/data/data/com.agy.os/files/usr"
HOME_DIR="/data/data/com.agy.os/files/home"
DPKG_BIN="$PREFIX/bin/dpkg"
DPKG_REAL="$PREFIX/bin/dpkg.real"
WRAP_PATH="$HOME_DIR/.agy_bridge/dpkg_wrap.sh"
PATCH_BIN="${AGY_PATCH_BIN:-$HOME_DIR/.agy_bridge/agy_patch}"

if [ ! -x "$DPKG_REAL" ]; then
    echo "dpkg_wrap: missing $DPKG_REAL" >&2
    exit 1
fi

HAS_DEB=0
for arg in "$@"; do
    case "$arg" in
        *.deb) HAS_DEB=1; break ;;
    esac
done

repair_wrapper_link() {
    if [ -x "$WRAP_PATH" ] && [ -e "$DPKG_BIN" ]; then
        CURRENT_LINK=$(readlink "$DPKG_BIN" 2>/dev/null || true)
        if [ "$CURRENT_LINK" != "$WRAP_PATH" ]; then
            if [ ! -L "$DPKG_BIN" ]; then
                mv -f "$DPKG_BIN" "$DPKG_REAL" 2>/dev/null || true
            fi
            ln -sf "$WRAP_PATH" "$DPKG_BIN" 2>/dev/null || true
        fi
    fi
}

if [ "$HAS_DEB" -eq 0 ] || [ ! -x "$PATCH_BIN" ]; then
    STATUS=0
    "$DPKG_REAL" "$@" || STATUS=$?
    repair_wrapper_link
    exit "$STATUS"
fi

WORK=$(mktemp -d "${TMPDIR:-$PREFIX/tmp}/agydpkg.XXXXXX")
trap 'chmod -R u+rwX "$WORK" 2>/dev/null || true; rm -rf "$WORK"' EXIT

NEW_ARGS=()
IDX=0
for arg in "$@"; do
    case "$arg" in
        *.deb)
            if [ -f "$arg" ]; then
                PKG_DIR="$WORK/pkg_$IDX"
                OUT_DEB="$WORK/patched_$IDX.deb"
                mkdir -p "$PKG_DIR/DEBIAN"
                dpkg-deb --control "$arg" "$PKG_DIR/DEBIAN"
                for cf in "$PKG_DIR/DEBIAN"/*; do
                    if [ -f "$cf" ]; then
                        "$PATCH_BIN" --file "$cf"
                    fi
                done
                dpkg-deb --fsys-tarfile "$arg" | "$PATCH_BIN" --tar | tar -xf - --delay-directory-restore -C "$PKG_DIR"
                if [ -f "$PKG_DIR/DEBIAN/md5sums" ] && command -v md5sum >/dev/null 2>&1; then
                    (
                        cd "$PKG_DIR"
                        LC_ALL=C find . -type f ! -path './DEBIAN/*' -print0 | LC_ALL=C sort -z | xargs -0 -r md5sum | sed 's|  \./|  |'
                    ) > "$PKG_DIR/DEBIAN/md5sums"
                fi
                dpkg-deb -Znone -b "$PKG_DIR" "$OUT_DEB" >/dev/null
                NEW_ARGS+=("$OUT_DEB")
                IDX=$((IDX + 1))
            else
                NEW_ARGS+=("$arg")
            fi
            ;;
        *)
            NEW_ARGS+=("$arg")
            ;;
    esac
done

STATUS=0
"$DPKG_REAL" "${NEW_ARGS[@]}" || STATUS=$?
repair_wrapper_link
exit "$STATUS"
