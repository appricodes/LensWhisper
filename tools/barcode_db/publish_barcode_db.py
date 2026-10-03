#!/usr/bin/env python3
"""
publish_barcode_db.py — Check the database built by build_barcode_db.py and get it
ready to upload for the Lens Whisper Android app.

The app downloads exactly one file from one fixed URL:

    https://wikiponder.com/lense_whisper/products_barcodes.db

(see ProductDbSource.DATABASE_URL in the app). There is no catalog and nothing to
configure app-side, so this script's whole job is to make sure the file you are about
to upload is sound, tell you how big the user's download will be, and write the small
sidecar file the app looks for.

Usage
-----
    python publish_barcode_db.py                    # check products_barcodes.db, write sidecar
    python publish_barcode_db.py --gzip             # also write a .gz for a ~3x smaller download
    python publish_barcode_db.py --db some/other.db

What it writes, next to the database
------------------------------------
    products_barcodes.db.sha256   one line: the hex SHA-256 of the .db file

The app fetches that sidecar before downloading and uses it to verify what arrived. It
is optional — the app falls back to checking Content-Length and that the file opens as
a valid database — but with it, a truncated or corrupted download is caught for
certain rather than probably. Upload it alongside the database.

Uploading
---------
Upload however you normally reach the host; the target directory /lense_whisper/
already exists and is served.

Whatever the transport, the result must be readable at:

    https://wikiponder.com/lense_whisper/products_barcodes.db
    https://wikiponder.com/lense_whisper/products_barcodes.db.sha256

Check it landed with:

    curl -sI https://wikiponder.com/lense_whisper/products_barcodes.db

ONE THING THE SERVER MUST GET RIGHT
-----------------------------------
If you serve the gzipped copy (--gzip), give the app the `.gz` URL and serve it as a
plain `application/octet-stream` download — NOT with a `Content-Encoding: gzip` header.
With that header the app's HTTP client transparently decompresses the body, and the app
would then try to gunzip an already-plain stream and fail. nginx and Apache both do the
right thing for a `.gz` file by default; just make sure on-the-fly compression is off
for that location. You must also point ProductDbSource.DATABASE_URL at the `.gz` name —
the app decides whether to decompress from the URL's own extension.

Requirements: Python 3.8+, standard library only.
"""

from __future__ import annotations

import argparse
import gzip
import hashlib
import shutil
import sqlite3
import sys
from pathlib import Path

SCRIPT_DIR = Path(__file__).resolve().parent
DEFAULT_DB = SCRIPT_DIR / "products_barcodes.db"

# Exactly what the app's ProductDatabase.kt reads. Checked here so a schema drift
# between the builder and the app is caught before hundreds of megabytes are uploaded,
# rather than by a user whose scan silently stops naming anything.
REQUIRED_COLUMNS = {"barcode", "product_name", "brands", "category", "countries"}


def human(num_bytes: float) -> str:
    for unit in ("B", "KB", "MB", "GB"):
        if num_bytes < 1024 or unit == "GB":
            return f"{num_bytes:.1f} {unit}"
        num_bytes /= 1024.0
    return f"{num_bytes:.1f} GB"


def sha256_of(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(4 * 1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def check_database(path: Path) -> int:
    """Verify the file is the shape the app expects, and return the row count.

    Exits non-zero on anything wrong: it is far cheaper to fail here than to upload a
    database the app cannot use.
    """
    problems: list[str] = []
    conn = sqlite3.connect(f"file:{path}?mode=ro", uri=True)
    try:
        journal = conn.execute("PRAGMA journal_mode").fetchone()[0]
        if str(journal).lower() == "wal":
            problems.append(
                "the database is in WAL mode; the app opens it read-only and cannot create "
                "the -wal sidecar it would need. Re-run build_barcode_db.py, which sets "
                "journal_mode=DELETE before closing."
            )

        columns = {row[1] for row in conn.execute("PRAGMA table_info(products)")}
        if not columns:
            problems.append("there is no `products` table in this file.")
        else:
            missing = REQUIRED_COLUMNS - columns
            if missing:
                problems.append(f"the `products` table is missing column(s): {', '.join(sorted(missing))}.")
            extra = columns - REQUIRED_COLUMNS
            if extra:
                problems.append(
                    f"the `products` table has column(s) the app does not read: "
                    f"{', '.join(sorted(extra))} — every one of them is bulk the user pays to "
                    f"download for nothing."
                )

        rows = conn.execute("SELECT COUNT(*) FROM products").fetchone()[0] if columns else 0
        if columns and rows == 0:
            problems.append("the `products` table is empty.")

        # The app looks up by barcode only; anything else in here is dead weight it paid
        # to download. (sqlite_autoindex_products_1 is the primary key's own index.)
        extra_indexes = [
            name for (name,) in conn.execute(
                "SELECT name FROM sqlite_master WHERE type='index' AND name NOT LIKE 'sqlite_autoindex%'"
            )
        ]
        if extra_indexes:
            problems.append(
                f"unused index(es) present: {', '.join(extra_indexes)} — the app queries by "
                f"barcode (the primary key) only, so these only make the download bigger."
            )
    finally:
        conn.close()

    if problems:
        print("Problems with this database:", file=sys.stderr)
        for problem in problems:
            print(f"  - {problem}", file=sys.stderr)
        raise SystemExit(1)

    return rows


def sample(path: Path, count: int = 3) -> None:
    """Print what the app would actually read aloud for a few rows — the cheapest
    possible guard against shipping a database full of taxonomy slugs again."""
    conn = sqlite3.connect(f"file:{path}?mode=ro", uri=True)
    try:
        labels = ("Product", "Brand", "Category", "Sold in")
        print("\nWhat the app will say, for a few products:")
        for row in conn.execute(
            "SELECT barcode, product_name, brands, category, countries "
            "FROM products WHERE category IS NOT NULL LIMIT ?", (count,)
        ):
            spoken = "; ".join(f"{l}: {v}" for l, v in zip(labels, row[1:]) if v)
            print(f"  {row[0]}  ->  {spoken}")
    finally:
        conn.close()


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--db", type=Path, default=DEFAULT_DB,
                        help=f"The database to publish (default: {DEFAULT_DB.name})")
    parser.add_argument("--gzip", action="store_true",
                        help="Also write a .gz copy — about three times smaller to download. "
                             "Read the note above about serving it before you use this.")
    args = parser.parse_args()

    if not args.db.exists():
        print(f"{args.db} does not exist. Run build_barcode_db.py first.", file=sys.stderr)
        raise SystemExit(1)

    size = args.db.stat().st_size
    print(f"{args.db.name}: {human(size)} ({size:,} bytes)")

    rows = check_database(args.db)
    print(f"  {rows:,} products, schema OK, no unused indexes")

    print("  hashing (this reads the whole file, so it takes a moment)…")
    digest = sha256_of(args.db)
    sidecar = args.db.with_name(args.db.name + ".sha256")
    sidecar.write_text(digest + "\n", encoding="utf-8")
    print(f"  sha256 {digest}")
    print(f"  wrote {sidecar.name}")

    upload = [args.db.name, sidecar.name]

    if args.gzip:
        gz_path = args.db.with_name(args.db.name + ".gz")
        print("  compressing…")
        # Level 6, not 9: on a file this size the last couple of per cent costs several
        # times the CPU, and this runs on every publish.
        with args.db.open("rb") as raw, gzip.open(gz_path, "wb", compresslevel=6) as packed:
            shutil.copyfileobj(raw, packed, length=4 * 1024 * 1024)
        gz_size = gz_path.stat().st_size
        print(f"  wrote {gz_path.name}, {human(gz_size)} "
              f"({size / gz_size:.1f}x smaller to download)")
        # The sidecar still holds the hash of the UNCOMPRESSED file, because that is what
        # the app has after unpacking and therefore what it can verify.
        upload = [gz_path.name, sidecar.name]

    sample(args.db)

    print("\nUpload these to https://wikiponder.com/lense_whisper/ :")
    for name in upload:
        print(f"  {name}")
    if args.gzip:
        print("\nRemember: point ProductDbSource.DATABASE_URL at the .gz name, and serve it")
        print("as application/octet-stream WITHOUT a Content-Encoding: gzip header.")


if __name__ == "__main__":
    main()
