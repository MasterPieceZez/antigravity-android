#!/usr/bin/env python3
"""Host-side test suite for tools/agy_patch.c."""

import gzip
import io
import os
import stat
import subprocess
import sys
import tarfile
import tempfile


def add_tar_bytes(tf: tarfile.TarFile, name: str, data: bytes, typeflag=tarfile.REGTYPE, linkname: str = ""):
    info = tarfile.TarInfo(name=name)
    info.type = typeflag
    info.linkname = linkname
    info.size = len(data) if typeflag == tarfile.REGTYPE else 0
    tf.addfile(info, io.BytesIO(data) if typeflag == tarfile.REGTYPE else None)


def make_raw_ustar_header(name: bytes, typeflag: bytes = b"0", size: int = 0, linkname: bytes = b"") -> bytes:
    hdr = bytearray(512)
    hdr[0 : len(name)] = name
    hdr[100:108] = b"0000755\0"
    hdr[108:116] = b"0000000\0"
    hdr[116:124] = b"0000000\0"
    hdr[124:136] = f"{size:011o}\0".encode("ascii")
    hdr[136:148] = b"00000000000\0"
    hdr[148:156] = b"        "
    hdr[156:157] = typeflag
    hdr[157 : 157 + len(linkname)] = linkname
    hdr[257:263] = b"ustar\0"
    hdr[263:265] = b"00"
    chk = sum(hdr)
    hdr[148:156] = f"{chk:06o}\0 ".encode("ascii")
    return bytes(hdr)


def run_patcher(bin_path: str, args: list[str], stdin_data: bytes | None = None) -> subprocess.CompletedProcess:
    return subprocess.run(
        [bin_path, *args],
        input=stdin_data,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        check=False,
    )


def main() -> int:
    if len(sys.argv) != 2:
        print("Usage: test_agy_patch.py <path-to-agy_patch>", file=sys.stderr)
        return 2
    bin_path = sys.argv[1]

    # 1. --bytes straddling 65536 buffer boundary
    payload = b"A" * 65530 + b"/data/data/com.termux/files/usr" + b"Z" * 100
    res = run_patcher(bin_path, ["--bytes"], payload)
    assert res.returncode == 0, res.stderr
    assert res.stdout == payload.replace(b"com.termux", b"com.agy.os")

    # 2. --file atomic patch preserving mode & skipping symlinks
    with tempfile.TemporaryDirectory() as tmp:
        target = os.path.join(tmp, "script.sh")
        with open(target, "wb") as f:
            f.write(b"#!/data/data/com.termux/files/usr/bin/bash\n")
        os.chmod(target, 0o755)
        res = run_patcher(bin_path, ["--file", target])
        assert res.returncode == 0, res.stderr
        with open(target, "rb") as f:
            assert f.read() == b"#!/data/data/com.agy.os/files/usr/bin/bash\n"
        assert stat.S_IMODE(os.stat(target).st_mode) == 0o755

        outside = os.path.join(tmp, "outside.txt")
        with open(outside, "wb") as f:
            f.write(b"com.termux")
        sym = os.path.join(tmp, "link.txt")
        os.symlink(outside, sym)
        res = run_patcher(bin_path, ["--file", sym])
        assert res.returncode == 0, res.stderr
        with open(outside, "rb") as f:
            assert f.read() == b"com.termux"

    # 3. --tar patches text/headers but skips compressed/binary payloads
    tar_buf = io.BytesIO()
    gz_payload = gzip.compress(b"inside gzip com.termux should stay intact")
    png_payload = b"\x89PNG\r\n\x1a\ncom.termux"
    with tarfile.open(fileobj=tar_buf, mode="w", format=tarfile.USTAR_FORMAT) as tf:
        add_tar_bytes(tf, "usr/bin/hello.sh", b"#!/data/data/com.termux/files/usr/bin/sh\n")
        add_tar_bytes(tf, "usr/share/doc/readme.gz", gz_payload)
        add_tar_bytes(tf, "usr/share/icon.bin", png_payload)
        add_tar_bytes(tf, "./home/.bashrc", b"export PREFIX=/data/data/com.termux/files/usr\n")
        add_tar_bytes(tf, "./home/projects/notes.txt", b"Reference to com.termux should stay intact\n")
    res = run_patcher(bin_path, ["--tar"], tar_buf.getvalue())
    assert res.returncode == 0, res.stderr
    out_tf = tarfile.open(fileobj=io.BytesIO(res.stdout), mode="r:")
    assert out_tf.extractfile("usr/bin/hello.sh").read() == b"#!/data/data/com.agy.os/files/usr/bin/sh\n"
    assert out_tf.extractfile("usr/share/doc/readme.gz").read() == gz_payload
    assert out_tf.extractfile("usr/share/icon.bin").read() == png_payload
    assert out_tf.extractfile("./home/.bashrc").read() == b"export PREFIX=/data/data/com.agy.os/files/usr\n"
    assert out_tf.extractfile("./home/projects/notes.txt").read() == b"Reference to com.termux should stay intact\n"

    # 3b. --tar preserves PAX long-path compressed files
    pax_gz_buf = io.BytesIO()
    long_gz_name = "usr/share/doc/" + ("sub_" * 28) + "readme.gz"
    with tarfile.open(fileobj=pax_gz_buf, mode="w", format=tarfile.PAX_FORMAT) as tf:
        add_tar_bytes(tf, long_gz_name, gz_payload)
    res = run_patcher(bin_path, ["--tar"], pax_gz_buf.getvalue())
    assert res.returncode == 0, res.stderr
    out_pax_tf = tarfile.open(fileobj=io.BytesIO(res.stdout), mode="r:")
    assert out_pax_tf.extractfile(long_gz_name).read() == gz_payload

    # 4. --check-tar accepts safe entries and safe symlinks
    safe_buf = io.BytesIO()
    with tarfile.open(fileobj=safe_buf, mode="w", format=tarfile.PAX_FORMAT) as tf:
        add_tar_bytes(tf, "./usr/bin/sh", b"", typeflag=tarfile.SYMTYPE, linkname="dash")
        add_tar_bytes(tf, "./usr/lib/foo/bar", b"", typeflag=tarfile.SYMTYPE, linkname="../baz")
        add_tar_bytes(tf, "./home/storage/shared", b"", typeflag=tarfile.SYMTYPE, linkname="/storage/emulated/0")
        add_tar_bytes(tf, "./usr/bin/" + ("a" * 130), b"ok")
    res = run_patcher(bin_path, ["--check-tar"], safe_buf.getvalue())
    assert res.returncode == 0, res.stderr

    # 5. --check-tar rejects traversal, unsafe symlinks, unsafe hardlinks, PAX overrides, empty tar
    unsafe_cases = [
        make_raw_ustar_header(b"../evil") + b"\0" * 1024,
        make_raw_ustar_header(b"/etc/passwd") + b"\0" * 1024,
        make_raw_ustar_header(b"usr\\evil") + b"\0" * 1024,
        make_raw_ustar_header(b"usr/evil", typeflag=b"2", linkname=b"../../etc/passwd") + b"\0" * 1024,
        make_raw_ustar_header(b"usr/evil", typeflag=b"2", linkname=b"/etc/passwd") + b"\0" * 1024,
        make_raw_ustar_header(b"usr/evil", typeflag=b"1", linkname=b"/etc/passwd") + b"\0" * 1024,
        make_raw_ustar_header(b"usr/evil", typeflag=b"1", linkname=b"../secret") + b"\0" * 1024,
        make_raw_ustar_header(b"usr/evil", typeflag=b"2", linkname=b"/storage/../../etc/passwd") + b"\0" * 1024,
        make_raw_ustar_header(b"usr/evil", typeflag=b"2", linkname=b"/data/data/com.agy.os/../shared_prefs/p") + b"\0" * 1024,
        make_raw_ustar_header(b"a/", typeflag=b"2", linkname=b"../etc/passwd") + b"\0" * 1024,
        make_raw_ustar_header(b"a/.", typeflag=b"2", linkname=b"../etc/passwd") + b"\0" * 1024,
        make_raw_ustar_header(b"usr/pax", typeflag=b"x", size=44) + b"99999999999999999999999999999999999 path=ok\n".ljust(512, b"\0") + b"\0" * 1024,
        b"\0" * 1024,
    ]
    pax_buf = io.BytesIO()
    with tarfile.open(fileobj=pax_buf, mode="w", format=tarfile.PAX_FORMAT) as tf:
        add_tar_bytes(tf, "usr/ok", b"", typeflag=tarfile.SYMTYPE, linkname="../" * 5 + "etc/passwd")
    unsafe_cases.append(pax_buf.getvalue())

    sym_write_buf = io.BytesIO()
    with tarfile.open(fileobj=sym_write_buf, mode="w", format=tarfile.PAX_FORMAT) as tf:
        add_tar_bytes(tf, "usr/out", b"", typeflag=tarfile.SYMTYPE, linkname="/storage/emulated/0/Download")
        add_tar_bytes(tf, "usr/out/evil.txt", b"pwn")
    unsafe_cases.append(sym_write_buf.getvalue())

    hard_sym_buf = io.BytesIO()
    with tarfile.open(fileobj=hard_sym_buf, mode="w", format=tarfile.PAX_FORMAT) as tf:
        add_tar_bytes(tf, "usr/link", b"", typeflag=tarfile.SYMTYPE, linkname="/storage/emulated/0/foo")
        add_tar_bytes(tf, "usr/hard", b"", typeflag=tarfile.LNKTYPE, linkname="usr/link")
    unsafe_cases.append(hard_sym_buf.getvalue())

    for idx, case_bytes in enumerate(unsafe_cases):
        res = run_patcher(bin_path, ["--check-tar"], case_bytes)
        assert res.returncode != 0, f"Unsafe case {idx} unexpectedly passed"


    # Oversized GNU longname header (>= 8192 bytes) must be rejected in --tar mode
    oversized_name = b"a" * 8200
    oversized_tar = make_raw_ustar_header(b"././@LongLink", typeflag=b"L", size=len(oversized_name)) + oversized_name
    pad_len = (512 - (len(oversized_name) % 512)) % 512
    oversized_tar += b"\x00" * pad_len + b"\x00" * 1024
    res = run_patcher(bin_path, ["--tar"], oversized_tar)
    assert res.returncode != 0, "Expected oversized GNU longname to fail in --tar mode"

    print("All agy_patch tests passed!")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
