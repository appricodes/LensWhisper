#!/usr/bin/env python3
"""
build_barcode_db.py — Download the bulk product-data dumps published by the
Open Food Facts family and build small, local SQLite lookup databases keyed
by barcode (GTIN/EAN/UPC).

Covered sources
----------------
  off   Open Food Facts     https://world.openfoodfacts.org/
  opf   Open Products Facts https://world.openproductsfacts.org/

Both projects publish a full export of their database as a gzip-compressed
JSON-Lines file (one JSON object per product, one product per line) under
https://static.<site>.org/data/ . This script streams that file straight
into SQLite, keeping only a handful of "essential" fields per barcode
instead of the full product record, so the resulting database stays small.

NOT covered: OpenGTinDB (https://opengtindb.org/)
----------------------------------------------------------------------------
OpenGTinDB's terms of use require a free registered API account, and they
ask that the database be queried barcode-by-barcode rather than mirrored in
bulk without prior permission. That is a fundamentally different access
pattern (rate-limited, per-GTIN HTTP calls, not a static dump), so it is
deliberately left out of this bulk-download script. If you want it later,
write a small separate rate-limited fetcher that queries one barcode at a
time using your own OpenGTinDB account credentials.

Usage
-----
    python build_barcode_db.py --site off
    python build_barcode_db.py --site opf
    python build_barcode_db.py --site all
    python build_barcode_db.py --site off --limit 5000   # quick smoke test
    python build_barcode_db.py --site off --force-download

Output
------
One SQLite file per site is written next to this script (or into --out-dir):
    openfoodfacts.sqlite3
    openproductsfacts.sqlite3

Each has a single table:
    CREATE TABLE products (
        barcode      TEXT PRIMARY KEY,
        product_name TEXT,
        brands       TEXT,
        categories   TEXT,
        quantity     TEXT,
        countries    TEXT,
        image_url    TEXT
    )

Requirements
------------
Python 3.8+, standard library only (urllib, gzip, json, sqlite3) — nothing
to pip install.

A note on politeness
---------------------
These are free, community-run, non-profit databases. Downloading the dumps
below is exactly what they are published for. Still, set CONTACT_EMAIL below
to your own address before running a full download — Open Food Facts asks
bulk users to identify themselves via the User-Agent header so they can
reach out if something about your usage needs attention.
"""

from __future__ import annotations

import argparse
import gzip
import io
import json
import sqlite3
import time
import urllib.error
import urllib.request
from pathlib import Path
from typing import Iterator, Optional

# --------------------------------------------------------------------------
# Configuration
# --------------------------------------------------------------------------

# Fill this in with your own address before running a full download (see the
# "A note on politeness" section above). Left blank by default.
CONTACT_EMAIL = "amiri.moalla@gmail.com"

USER_AGENT = (
    "LensWhisper-BarcodeDBBuilder/1.0"
    + (f" (contact: {CONTACT_EMAIL})" if CONTACT_EMAIL else " (no contact set)")
)

SCRIPT_DIR = Path(__file__).resolve().parent

SITES = {
    "off": {
        "label": "Open Food Facts",
        "dump_url": "https://static.openfoodfacts.org/data/openfoodfacts-products.jsonl.gz",
        "cache_file": SCRIPT_DIR / "cache" / "openfoodfacts-products.jsonl.gz",
        "db_file": SCRIPT_DIR / "openfoodfacts.sqlite3",
    },
    "opf": {
        "label": "Open Products Facts",
        "dump_url": "https://static.openproductsfacts.org/data/openproductsfacts-products.jsonl.gz",
        "cache_file": SCRIPT_DIR / "cache" / "openproductsfacts-products.jsonl.gz",
        "db_file": SCRIPT_DIR / "openproductsfacts.sqlite3",
    },
}

BATCH_SIZE = 5000
PROGRESS_EVERY = 20000

# --------------------------------------------------------------------------
# Download
# --------------------------------------------------------------------------


def human_size(num_bytes: float) -> str:
    for unit in ("B", "KB", "MB", "GB", "TB"):
        if num_bytes < 1024:
            return f"{num_bytes:.1f}{unit}"
        num_bytes /= 1024
    return f"{num_bytes:.1f}PB"


def download(url: str, dest: Path, force: bool = False) -> None:
    dest.parent.mkdir(parents=True, exist_ok=True)

    if dest.exists() and not force:
        print(f"  [skip] {dest.name} already downloaded ({human_size(dest.stat().st_size)}). "
              f"Use --force-download to re-fetch.")
        return

    request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    tmp_dest = dest.with_suffix(dest.suffix + ".part")

    try:
        with urllib.request.urlopen(request) as response, open(tmp_dest, "wb") as out_file:
            total = response.getheader("Content-Length")
            total_bytes = int(total) if total else None
            downloaded = 0
            chunk_size = 1024 * 1024
            last_print = 0.0

            while True:
                chunk = response.read(chunk_size)
                if not chunk:
                    break
                out_file.write(chunk)
                downloaded += len(chunk)

                now = time.time()
                if now - last_print > 0.5:
                    last_print = now
                    if total_bytes:
                        pct = downloaded / total_bytes * 100
                        print(f"\r  downloading {dest.name}: {human_size(downloaded)} "
                              f"/ {human_size(total_bytes)} ({pct:5.1f}%)", end="", flush=True)
                    else:
                        print(f"\r  downloading {dest.name}: {human_size(downloaded)}",
                              end="", flush=True)

        print()  # newline after the progress line
    except urllib.error.HTTPError as exc:
        tmp_dest.unlink(missing_ok=True)
        raise SystemExit(
            f"  [error] HTTP {exc.code} downloading {url}\n"
            f"          The export URL may have moved — check the site's own \"data\" page "
            f"(e.g. https://world.openfoodfacts.org/data or "
            f"https://world.openproductsfacts.org/data) for the current link and update "
            f"SITES[...]['dump_url'] in this script."
        ) from exc
    except urllib.error.URLError as exc:
        tmp_dest.unlink(missing_ok=True)
        raise SystemExit(f"  [error] could not reach {url}: {exc.reason}") from exc

    tmp_dest.rename(dest)


def iter_dump_lines(url: str, cache_file: Path, force: bool, use_cache: bool) -> Iterator[str]:
    """Yield decoded JSONL lines from a dump.

    If use_cache is True, a complete local copy is downloaded first (or
    reused if one already exists) and lines are read from disk. If False,
    lines are streamed straight from the HTTP response and decompressed
    on the fly, without ever writing the (potentially multi-gigabyte) file
    to disk — used for --limit smoke-test runs so they stay cheap.
    """
    if use_cache:
        download(url, cache_file, force=force)
        with gzip.open(cache_file, "rt", encoding="utf-8", errors="replace") as fh:
            yield from fh
        return

    print("  (no cache found; streaming a limited preview directly, nothing saved to disk)")
    request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    with urllib.request.urlopen(request) as response:
        with gzip.GzipFile(fileobj=response) as gz:
            with io.TextIOWrapper(gz, encoding="utf-8", errors="replace") as fh:
                yield from fh


# --------------------------------------------------------------------------
# Parsing / extraction
# --------------------------------------------------------------------------


def flatten(value) -> Optional[str]:
    """Normalize a raw field value to a clean string, or None if it's absent.

    Handles a few upstream data quirks seen in the dumps: some fields come
    back as lists instead of strings, and some records store the literal
    text "null" or an empty string instead of an actual JSON null.
    """
    if value is None:
        return None
    if isinstance(value, list):
        text = ", ".join(str(v) for v in value if v)
    else:
        text = str(value)
    text = text.strip()
    if not text or text.lower() == "null":
        return None
    return text


def extract_row(product: dict) -> Optional[tuple]:
    barcode = (product.get("code") or "").strip()
    if not barcode:
        return None

    product_name = product.get("product_name") or product.get("product_name_en")
    image_url = product.get("image_front_url") or product.get("image_url")

    return (
        barcode,
        flatten(product_name),
        flatten(product.get("brands")),
        flatten(product.get("categories")),
        flatten(product.get("quantity")),
        flatten(product.get("countries")),
        flatten(image_url),
    )


# --------------------------------------------------------------------------
# SQLite
# --------------------------------------------------------------------------


def open_db(db_file: Path) -> sqlite3.Connection:
    conn = sqlite3.connect(db_file)
    conn.execute("PRAGMA journal_mode = WAL")
    conn.execute("PRAGMA synchronous = OFF")
    conn.execute(
        """
        CREATE TABLE IF NOT EXISTS products (
            barcode      TEXT PRIMARY KEY,
            product_name TEXT,
            brands       TEXT,
            categories   TEXT,
            quantity     TEXT,
            countries    TEXT,
            image_url    TEXT
        )
        """
    )
    conn.commit()
    return conn


def build_database(site_key: str, limit: Optional[int], force_download: bool) -> None:
    site = SITES[site_key]
    print(f"\n=== {site['label']} ===")

    # A full run (no --limit) always wants a complete, cached copy on disk.
    # A --limit smoke test only needs to stream a preview — unless a full
    # copy is already cached, in which case reading from it is just as fast.
    use_cache = limit is None or site["cache_file"].exists()

    print(f"  parsing -> {site['db_file'].name}")
    conn = open_db(site["db_file"])
    cur = conn.cursor()

    batch: list[tuple] = []
    seen = 0
    inserted = 0
    skipped = 0
    start = time.time()

    def flush():
        nonlocal inserted
        if not batch:
            return
        cur.executemany(
            """
            INSERT INTO products (barcode, product_name, brands, categories, quantity, countries, image_url)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(barcode) DO UPDATE SET
                product_name = excluded.product_name,
                brands       = excluded.brands,
                categories   = excluded.categories,
                quantity     = excluded.quantity,
                countries    = excluded.countries,
                image_url    = excluded.image_url
            """,
            batch,
        )
        inserted += len(batch)
        conn.commit()
        batch.clear()

    try:
        for line in iter_dump_lines(site["dump_url"], site["cache_file"], force_download, use_cache):
            seen += 1
            line = line.strip()
            if not line:
                continue

            try:
                product = json.loads(line)
            except json.JSONDecodeError:
                skipped += 1
                continue

            row = extract_row(product)
            if row is None:
                skipped += 1
                continue

            batch.append(row)
            if len(batch) >= BATCH_SIZE:
                flush()

            if seen % PROGRESS_EVERY == 0:
                elapsed = time.time() - start
                rate = seen / elapsed if elapsed > 0 else 0
                print(f"\r  {seen:>10,} lines read | {inserted:>10,} inserted | "
                      f"{skipped:>7,} skipped | {rate:6.0f} lines/s", end="", flush=True)

            if limit and seen >= limit:
                break
    except EOFError:
        print("\n  [warn] the dump ended unexpectedly (incomplete/truncated data). "
              "If you were reading from a cached file, re-run with --force-download.")

    flush()
    conn.execute("CREATE INDEX IF NOT EXISTS idx_products_name ON products(product_name)")
    conn.commit()
    conn.close()

    print(f"\r  {seen:>10,} lines read | {inserted:>10,} inserted | {skipped:>7,} skipped"
          + " " * 20)
    print(f"  done -> {site['db_file']}")


# --------------------------------------------------------------------------
# CLI
# --------------------------------------------------------------------------


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--site", choices=["off", "opf", "all"], default="all",
                         help="Which source to build (default: all)")
    parser.add_argument("--limit", type=int, default=None,
                         help="Only process the first N lines of the dump (for a quick test run)")
    parser.add_argument("--force-download", action="store_true",
                         help="Re-download the dump even if a cached copy already exists")
    args = parser.parse_args()

    targets = list(SITES) if args.site == "all" else [args.site]
    for key in targets:
        build_database(key, args.limit, args.force_download)

    print("\nAll requested databases built.")
    print("Note: OpenGTinDB was intentionally skipped — see the module docstring for why.")


if __name__ == "__main__":
    main()
