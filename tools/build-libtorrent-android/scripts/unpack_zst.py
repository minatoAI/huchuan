#!/usr/bin/env python3
"""Unpack a .pkg.tar.zst archive onto disk using the stdlib only.

Python 3.14 ships compression.zstd (PEP 784), which is enough to decompress the
msys2 packages that build.sh needs on Windows hosts. No external zstd or tar
binary is required.
"""
import pathlib
import sys
import tarfile

try:
    from compression import zstd  # Python 3.14+
except ImportError:  # pragma: no cover - older interpreters
    zstd = None


def main() -> int:
    if len(sys.argv) != 3:
        print("usage: unpack_zst.py <archive.pkg.tar.zst> <destdir>", file=sys.stderr)
        return 2
    archive = pathlib.Path(sys.argv[1])
    dest = pathlib.Path(sys.argv[2])
    dest.mkdir(parents=True, exist_ok=True)
    if zstd is None:
        print("compression.zstd unavailable; install zstd or use Python 3.14+", file=sys.stderr)
        return 1
    raw = zstd.decompress(archive.read_bytes())
    tar_path = archive.with_suffix("")
    tar_path.write_bytes(raw)
    with tarfile.open(tar_path) as tf:
        tf.extractall(dest, filter="data")
    tar_path.unlink()
    print(f"unpacked {archive.name} -> {dest}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
