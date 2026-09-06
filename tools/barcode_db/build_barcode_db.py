#!/usr/bin/env python3
"""
build_barcode_db.py — Download the bulk product-data dumps published by the
Open Food Facts family and build ONE small SQLite lookup database keyed by
barcode (GTIN/EAN/UPC), for the Lens Whisper Android app to ship to phones.

Covered sources
----------------
  off   Open Food Facts     https://world.openfoodfacts.org/
  opf   Open Products Facts https://world.openproductsfacts.org/

Both projects publish a full export of their database as a gzip-compressed
JSON-Lines file (one JSON object per product, one product per line) under
https://static.<site>.org/data/ . This script streams that file straight into
SQLite, keeping only the handful of fields the app actually reads aloud, and
merges every source into a single output file.

NOT covered: OpenGTinDB (https://opengtindb.org/)
----------------------------------------------------------------------------
OpenGTinDB's terms of use require a free registered API account, and they ask
that the database be queried barcode-by-barcode rather than mirrored in bulk
without prior permission. That is a fundamentally different access pattern
(rate-limited, per-GTIN HTTP calls, not a static dump), so it is deliberately
left out of this bulk-download script. If you want it later, write a small
separate rate-limited fetcher that queries one barcode at a time using your own
OpenGTinDB account credentials.

Usage
-----
    python build_barcode_db.py                       # both sources -> products_barcodes.db
    python build_barcode_db.py --site off
    python build_barcode_db.py --limit 5000          # quick smoke test
    python build_barcode_db.py --force-download
    python build_barcode_db.py --out /tmp/test.db

Output
------
A single SQLite file (default: products_barcodes.db next to this script):

    CREATE TABLE products (
        barcode      TEXT PRIMARY KEY,
        product_name TEXT NOT NULL,
        brands       TEXT,
        category     TEXT,
        countries    TEXT
    )

WHY THIS SCHEMA, AND WHAT CHANGED (2026-09-06 rewrite)
------------------------------------------------------
The first version of this script produced a 708 MB database from Open Food
Facts, much of which was waste or actively harmful to the app that reads it
aloud to blind users. Measured against the real 4.7-million-row output:

* `image_url` was ALWAYS NULL — 4,708,561 rows, zero populated. The dump has no
  `image_front_url`/`image_url` key at all (images live under an `images`
  object), so the column only ever recorded a failed lookup. Dropped.

* `CREATE INDEX idx_products_name ON products(product_name)` indexed 93 MB of
  text that nothing ever queries — the app looks products up by barcode, which
  is already the primary key. Dropped; this alone was a large fraction of the
  file.

* `categories` was the biggest column (134 MB) and held the *entire taxonomy
  chain*, e.g. "Plant-based foods and beverages, Beverages, Hot beverages,
  Plant-based beverages, Teas, Tea bags". Spoken aloud that is unbearable, and
  only the last term carries information. It also leaked raw taxonomy slugs
  ("en:tea-bags") into 307,980 rows, the literal string "Null", untranslated
  German/French slugs, and in at least one case a contributor's marketing blob
  complete with a price. Now a single, most-specific, English category taken
  from `categories_tags` and humanised — see `pick_category`.

* `countries` carried raw `en:` slugs in 2,626,175 rows (63% of them) and could
  run to 150+ characters listing every country in a product's distribution, in
  whichever language the contributor used. Now at most two humanised English
  country names from `countries_tags`.

* Rows with no product name at all (326,346 of them) were stored anyway. The
  app treats a row with nothing to say as "not found", so they were pure bulk.
  Now skipped at extraction.

* `quantity` ("350 g", "1.5 g") was dropped on request (2026-09-06). It is
  free-text the contributor typed, it is only present on about a third of rows,
  and it is the least useful thing to hear when what you want to know is what
  the packet in your hand actually is.

* The database was left in WAL mode and never VACUUMed, so it shipped with its
  free pages and needed a sidecar `-wal` file. Android opens this file
  read-only, where WAL is a liability. Now switched to `journal_mode=DELETE`
  and VACUUMed before close.

* Each source produced its own file. The app downloads one file from one URL,
  so both sources now merge into one database, Open Food Facts first, with
  Open Products Facts filling in barcodes it does not already have.

Requirements
------------
Python 3.8+, standard library only (urllib, gzip, json, sqlite3) — nothing to
pip install.

A note on politeness
---------------------
These are free, community-run, non-profit databases. Downloading the dumps
below is exactly what they are published for. Still, set CONTACT_EMAIL below to
your own address before running a full download — Open Food Facts asks bulk
users to identify themselves via the User-Agent header so they can reach out if
something about your usage needs attention.
"""

from __future__ import annotations

import argparse
import gzip
import io
import json
import shutil
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
# "A note on politeness" section above).
CONTACT_EMAIL = "amiri.moalla@gmail.com"

USER_AGENT = (
    "LensWhisper-BarcodeDBBuilder/2.0"
    + (f" (contact: {CONTACT_EMAIL})" if CONTACT_EMAIL else " (no contact set)")
)

SCRIPT_DIR = Path(__file__).resolve().parent

# The app downloads exactly one file, so this is the one output. The name matches
# what the server serves it as; see publish_barcode_db.py.
DEFAULT_OUT = SCRIPT_DIR / "products_barcodes.db"

# Order matters: the first source to supply a barcode wins, and later sources
# only fill in barcodes not already present. Open Food Facts is both far larger
# and better curated, so it goes first.
SITES = {
    "off": {
        "label": "Open Food Facts",
        "dump_url": "https://static.openfoodfacts.org/data/openfoodfacts-products.jsonl.gz",
        "cache_file": SCRIPT_DIR / "cache" / "openfoodfacts-products.jsonl.gz",
    },
    "opf": {
        "label": "Open Products Facts",
        "dump_url": "https://static.openproductsfacts.org/data/openproductsfacts-products.jsonl.gz",
        "cache_file": SCRIPT_DIR / "cache" / "openproductsfacts-products.jsonl.gz",
    },
}

BATCH_SIZE = 5000
PROGRESS_EVERY = 20000

# Taxonomy tags that carry no information and must never reach a user's ears.
JUNK_TAGS = {"null", "unknown", "none", "no-category", "not-applicable"}

# Words left lowercase inside a title-cased country name ("Isle of Man",
# "Trinidad and Tobago", "United States of America").
TITLE_CASE_MINOR_WORDS = {"of", "and", "the", "in", "on", "at", "for", "da", "de", "du", "la", "le"}

# A category or name longer than this is contributor junk (one row's "category"
# was a whole marketing blurb with a price in it), not a category.
MAX_CATEGORY_LEN = 60
MAX_NAME_LEN = 120
MAX_BRANDS_LEN = 60
MAX_COUNTRIES = 2
MAX_COUNTRY_LEN = 40

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

    If use_cache is True, a complete local copy is downloaded first (or reused
    if one already exists) and lines are read from disk. If False, lines are
    streamed straight from the HTTP response and decompressed on the fly,
    without ever writing the (multi-gigabyte) file to disk — used for --limit
    smoke-test runs so they stay cheap.
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


def clean_text(value, max_len: Optional[int] = None) -> Optional[str]:
    """Normalize a raw field value to a clean single-line string, or None.

    Handles the upstream quirks actually present in the dumps: values that come
    back as lists rather than strings, the literal text "null"/"NULL" standing
    in for an absent value, embedded newlines and tabs (contributors paste whole
    product descriptions into single-line fields), and runs of whitespace.
    """
    if value is None:
        return None
    if isinstance(value, list):
        text = ", ".join(str(v) for v in value if v)
    else:
        text = str(value)
    # Collapse every kind of whitespace, including the newlines that turn up in
    # pasted-in text, into single spaces.
    text = " ".join(text.split())
    if not text or text.lower() in JUNK_TAGS:
        return None
    if max_len is not None and len(text) > max_len:
        return None
    return text


def humanise_tag(tag: str, title_case: bool = False) -> Optional[str]:
    """Turn a taxonomy tag like "en:cocoa-and-hazelnuts-spreads" into
    "Cocoa and hazelnuts spreads".

    [title_case] capitalises every significant word, for country names; the
    default sentence case is what a category wants.

    Tags are language-prefixed. Only `en:` tags are used: a `fr:`/`de:` tag is
    the contributor's own language, and this app speaks the result aloud in a
    context where a stray German slug in the middle of an English sentence is
    worse than saying nothing. (The app has its own offline translator for the
    user's language — see OfflineTranslator — which works from English.)
    """
    if not isinstance(tag, str):
        return None
    tag = tag.strip()
    if ":" in tag:
        lang, _, rest = tag.partition(":")
        if lang != "en":
            return None
        tag = rest
    if not tag or tag.lower() in JUNK_TAGS:
        return None
    words = tag.replace("-", " ").replace("_", " ").split()
    if not words:
        return None
    if title_case:
        # Proper nouns — country names — want every significant word capitalised
        # ("United States", "Isle of Man"), which is wrong for a category
        # ("Cocoa And Hazelnuts Spreads" reads as a headline, not a category).
        return " ".join(
            w if i > 0 and w in TITLE_CASE_MINOR_WORDS else w[0].upper() + w[1:]
            for i, w in enumerate(words)
        )
    text = " ".join(words)
    return text[0].upper() + text[1:]


def pick_category(product: dict) -> Optional[str]:
    """The single most specific English category, or None.

    `categories_tags` is ordered general-to-specific ("en:beverages",
    "en:hot-beverages", "en:teas", "en:tea-bags"), so the last usable entry is
    the informative one — the whole chain was what made the old `categories`
    column both the largest in the database and painful to listen to. Falls back
    to the free-text `categories` field's last comma-separated part only when
    there are no usable tags at all, since that field is unvalidated and is
    where the contributor junk lives.
    """
    tags = product.get("categories_tags")
    if isinstance(tags, list):
        for tag in reversed(tags):
            name = humanise_tag(tag)
            if name and len(name) <= MAX_CATEGORY_LEN:
                return name

    raw = clean_text(product.get("categories"), max_len=400)
    if raw:
        last = raw.split(",")[-1].strip()
        # Only accept it if it looks like a category rather than a slug or a
        # paste — no colons (taxonomy prefix), no asterisks/currency (the
        # marketing-blob case), and short.
        if last and ":" not in last and "*" not in last and "$" not in last and len(last) <= MAX_CATEGORY_LEN:
            return last[0].upper() + last[1:]
    return None


def pick_countries(product: dict) -> Optional[str]:
    """At most [MAX_COUNTRIES] humanised English country names.

    Uses `countries_tags` rather than the free-text `countries` field: 63% of
    that field's values carried raw `en:` slugs, and the rest were in whatever
    language the contributor used, so a French product would announce "Allemagne"
    to an English speaker. Truncated because the point is "roughly where this is
    sold", not a distribution list — some rows listed forty countries.
    """
    tags = product.get("countries_tags")
    names: list[str] = []
    if isinstance(tags, list):
        for tag in tags:
            name = humanise_tag(tag, title_case=True)
            # `countries_tags` is not a closed vocabulary — contributors have
            # typed company addresses into it, which arrive here as tags like
            # "en:real-chimica-srl-via-monte-santo37". No real country name
            # contains a digit or runs past 40 characters, and announcing a
            # stranger's street address as a country would be worse than
            # announcing nothing.
            if not name or len(name) > MAX_COUNTRY_LEN or any(ch.isdigit() for ch in name):
                continue
            if name not in names:
                names.append(name)
            if len(names) >= MAX_COUNTRIES:
                break
    if names:
        return ", ".join(names)
    return None


def pick_name(product: dict) -> Optional[str]:
    """The product's name, preferring whatever the contributor set as the main
    one, then the English variant, then any other language's.

    A row without a name is dropped by the caller: the app has nothing to say
    about it, and 326,346 such rows were being stored by the previous version.
    """
    for key in ("product_name", "product_name_en"):
        name = clean_text(product.get(key), max_len=MAX_NAME_LEN)
        if name:
            return name
    # Any remaining product_name_xx — a name in the wrong language still
    # identifies the product, where no name at all makes the record useless.
    # Sorted, not in dict order: JSON key order varies between dump rebuilds, so
    # leaving it to chance would mean the same product could come out named in
    # German one month and Spanish the next, for no reason the user could see.
    for key in sorted(k for k in product if k.startswith("product_name_") and not k.endswith("_imported")):
        name = clean_text(product[key], max_len=MAX_NAME_LEN)
        if name:
            return name
    return None


def extract_row(product: dict) -> Optional[tuple]:
    barcode = clean_text(product.get("code"))
    # Barcodes are digit strings; anything else is a malformed record. Guarded
    # here rather than in the app, which would otherwise have to defend against
    # it on every lookup.
    if not barcode or not barcode.isdigit():
        return None

    name = pick_name(product)
    if not name:
        return None

    return (
        barcode,
        name,
        clean_text(product.get("brands"), max_len=MAX_BRANDS_LEN),
        pick_category(product),
        pick_countries(product),
    )


# --------------------------------------------------------------------------
# SQLite
# --------------------------------------------------------------------------


def open_db(db_file: Path) -> sqlite3.Connection:
    conn = sqlite3.connect(db_file)
    # WAL + synchronous OFF make the multi-hour build far faster. Both are undone
    # in finalise_db() before the file is shipped — see there.
    conn.execute("PRAGMA journal_mode = WAL")
    conn.execute("PRAGMA synchronous = OFF")
    conn.execute(
        """
        CREATE TABLE IF NOT EXISTS products (
            barcode      TEXT PRIMARY KEY,
            product_name TEXT NOT NULL,
            brands       TEXT,
            category     TEXT,
            countries    TEXT
        )
        """
    )
    conn.commit()
    return conn


def finalise_db(db_file: Path) -> None:
    """Make the built file fit to ship: no WAL, no free pages.

    The Android app opens this read-only (see ProductDatabase.kt). A database
    left in WAL mode expects to be able to create `-wal`/`-shm` sidecars next to
    itself, which is exactly what a read-only open cannot do, and VACUUM
    reclaims the free pages a long insert run leaves behind — on a database this
    size that is a large, entirely free saving for every user who downloads it.
    """
    # VACUUM rebuilds the file into a fresh copy before swapping it in, so it needs
    # roughly the database's own size free on the same volume. Worth saying out loud:
    # this runs at the very end of a multi-hour ingest, and failing here for want of
    # disk after all that work would be a miserable way to find out.
    size = db_file.stat().st_size
    free = shutil.disk_usage(db_file.parent).free
    print(f"\n  finalising (journal mode, vacuum) — needs ~{human_size(size)} free, "
          f"{human_size(free)} available")
    if free < size:
        print("  [warn] free space looks too low for VACUUM; it may fail. The database is "
              "already complete and usable, just larger than it needs to be.")
    conn = sqlite3.connect(db_file)
    try:
        conn.execute("PRAGMA journal_mode = DELETE")
        conn.commit()
        conn.execute("VACUUM")
        conn.commit()
    finally:
        conn.close()
    # VACUUM cannot run inside WAL's sidecars; remove any that survived.
    for suffix in ("-wal", "-shm"):
        sidecar = db_file.with_name(db_file.name + suffix)
        sidecar.unlink(missing_ok=True)


def ingest(conn: sqlite3.Connection, site_key: str, limit: Optional[int], force_download: bool) -> None:
    site = SITES[site_key]
    print(f"\n=== {site['label']} ===")

    # A full run (no --limit) always wants a complete, cached copy on disk. A
    # --limit smoke test only needs to stream a preview — unless a full copy is
    # already cached, in which case reading from it is just as fast.
    use_cache = limit is None or site["cache_file"].exists()

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
        # INSERT OR IGNORE, not an upsert: sources are ingested in priority order
        # and a later one must not overwrite a better-curated earlier record.
        cur.executemany(
            """
            INSERT OR IGNORE INTO products
                (barcode, product_name, brands, category, countries)
            VALUES (?, ?, ?, ?, ?)
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
                print(f"\r  {seen:>10,} lines read | {inserted:>10,} kept | "
                      f"{skipped:>9,} skipped | {rate:6.0f} lines/s", end="", flush=True)

            if limit and seen >= limit:
                break
    except EOFError:
        print("\n  [warn] the dump ended unexpectedly (incomplete/truncated data). "
              "If you were reading from a cached file, re-run with --force-download.")

    flush()
    print(f"\r  {seen:>10,} lines read | {inserted:>10,} kept | {skipped:>9,} skipped"
          + " " * 20)


# --------------------------------------------------------------------------
# CLI
# --------------------------------------------------------------------------


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--site", choices=["off", "opf", "all"], default="all",
                        help="Which source(s) to include (default: all, merged into one file)")
    parser.add_argument("--limit", type=int, default=None,
                        help="Only process the first N lines of each dump (for a quick test run)")
    parser.add_argument("--force-download", action="store_true",
                        help="Re-download the dump even if a cached copy already exists")
    parser.add_argument("--out", type=Path, default=DEFAULT_OUT,
                        help=f"Output database file (default: {DEFAULT_OUT.name})")
    args = parser.parse_args()

    # Always build from scratch: an incremental run over a previous file would
    # keep rows for products that have since been deleted upstream, and would
    # silently retain rows written by an older version of this script's own
    # extraction rules.
    if args.out.exists():
        print(f"Removing existing {args.out.name} (a build always starts clean).")
        args.out.unlink()
    for suffix in ("-wal", "-shm"):
        args.out.with_name(args.out.name + suffix).unlink(missing_ok=True)

    conn = open_db(args.out)
    try:
        for key in (list(SITES) if args.site == "all" else [args.site]):
            ingest(conn, key, args.limit, args.force_download)
        total = conn.execute("SELECT COUNT(*) FROM products").fetchone()[0]
    finally:
        conn.close()

    finalise_db(args.out)

    print(f"\nBuilt {args.out}")
    print(f"  {total:,} products, {human_size(args.out.stat().st_size)}")
    print("\nNext: python publish_barcode_db.py   (checksums it and prepares the upload)")
    print("Note: OpenGTinDB was intentionally skipped — see the module docstring for why.")


if __name__ == "__main__":
    main()
