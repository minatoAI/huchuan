#!/usr/bin/env python3
"""Pack libtorrent4j.so into a jar laid out like the official Android artifact.

The zip entry order, compression and timestamps are fixed so that re-running the
build with an identical .so produces a byte-identical jar.
"""
import pathlib
import sys
import zipfile

FIXED_DATE = (2026, 1, 1, 0, 0, 0)
ENTRY = "lib/arm64-v8a/libtorrent4j.so"


def main() -> int:
    if len(sys.argv) != 3:
        print("usage: make_jar.py <libtorrent4j.so> <out.jar>", file=sys.stderr)
        return 2
    so = pathlib.Path(sys.argv[1])
    jar = pathlib.Path(sys.argv[2])
    data = so.read_bytes()

    with zipfile.ZipFile(jar, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as z:
        for name in ("lib/", "lib/arm64-v8a/"):
            info = zipfile.ZipInfo(name, date_time=FIXED_DATE)
            info.external_attr = (0o40755 << 16) | 0x10
            info.create_system = 3
            z.writestr(info, b"")
        info = zipfile.ZipInfo(ENTRY, date_time=FIXED_DATE)
        info.external_attr = 0o100644 << 16
        info.create_system = 3
        info.compress_type = zipfile.ZIP_DEFLATED
        z.writestr(info, data)

    print(f"{jar}  {jar.stat().st_size} bytes  ({len(data)} bytes of .so, stored at {ENTRY})")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
