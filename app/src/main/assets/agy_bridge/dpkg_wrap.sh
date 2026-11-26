#!/data/data/com.agy.os/files/usr/bin/bash
set -u

PREFIX="/data/data/com.agy.os/files/usr"
HOME_DIR="/data/data/com.agy.os/files/home"
DPKG_REAL="$PREFIX/bin/dpkg.real"
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

if [ "$HAS_DEB" -eq 0 ] || [ ! -x "$PATCH_BIN" ]; then
    exec "$DPKG_REAL" "$@"
fi

WORK=$(mktemp -d "${TMPDIR:-$PREFIX/tmp}/agydpkg.XXXXXX")
trap 'rm -rf "$WORK"' EXIT

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
                    [ -f "$cf" ] && "$PATCH_BIN" --file "$cf"
                done
                dpkg-deb --fsys-tarfile "$arg" | "$PATCH_BIN" --tar | tar -xf - --delay-directory-restore -C "$PKG_DIR"
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

"$DPKG_REAL" "${NEW_ARGS[@]}"
