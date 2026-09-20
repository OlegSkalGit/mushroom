#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Mushroom Offline Database - Quick UNIQUE Error Fixer & Duplicate Resolver
Fixes 'UNIQUE constraint failed: taxa.inat_id' by:
1. Migrating schema to remove UNIQUE constraint from inat_id (preserving all data).
2. Identifying all missing species that failed due to duplicate inat_id.
3. Instantly cloning metadata and photos from already-downloaded donor records in SQLite (zero re-downloading!).
4. Seamlessly completing remaining species without errors.
"""

import os
import sys
import io
import time
import json
import sqlite3
import argparse
import urllib.parse
from typing import Dict, List, Optional, Tuple, Any

import requests
from PIL import Image

if sys.platform == "win32":
    try:
        sys.stdout.reconfigure(encoding="utf-8")
        sys.stderr.reconfigure(encoding="utf-8")
    except Exception:
        pass

try:
    from tqdm import tqdm
except ImportError:
    def tqdm(iterable, **kwargs):
        return iterable


# =====================================================================
# Curated Knowledge & Taxonomy Rules
# =====================================================================

TUBES_GENERA = {
    "boletus", "leccinum", "suillus", "imleria", "xerocomus", "neoboletus",
    "rubroboletus", "tylopilus", "fomes", "trametes", "phellinus", "ganoderma",
    "laetiporus", "polyporus", "fomitopsis", "daedaleopsis", "trichaptum",
    "caloboletus", "buthyriboletus", "hemileccinum", "pseudoboletus", "gyroporus"
}

SESSION = requests.Session()
SESSION.headers.update({
    "User-Agent": "MushroomOfflineDBFixer/1.0 (Android; Contact: olegskal)"
})


def http_get(url: str, params: Optional[dict] = None, timeout: int = 12) -> Optional[requests.Response]:
    for attempt in range(3):
        try:
            r = SESSION.get(url, params=params, timeout=timeout)
            if r.status_code == 200:
                return r
            elif r.status_code == 429:
                time.sleep(3.0 * (attempt + 1))
            elif r.status_code == 404:
                return None
            else:
                time.sleep(1.0)
        except Exception:
            time.sleep(1.5 * (attempt + 1))
    return None


def migrate_and_connect_db(db_path: str) -> sqlite3.Connection:
    conn = sqlite3.connect(db_path, timeout=30.0)
    conn.execute("PRAGMA journal_mode = WAL;")
    conn.execute("PRAGMA synchronous = NORMAL;")
    conn.execute("PRAGMA foreign_keys = ON;")

    cur = conn.cursor()
    cur.execute("SELECT sql FROM sqlite_master WHERE type='table' AND name='taxa'")
    row = cur.fetchone()

    if row and "inat_id INTEGER UNIQUE" in row[0]:
        print("[MIGRATE] Detected UNIQUE constraint on inat_id. Removing constraint...")
        conn.execute("PRAGMA foreign_keys = OFF;")
        conn.execute("ALTER TABLE taxa RENAME TO taxa_old;")
        conn.execute("""
            CREATE TABLE taxa (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                inat_id INTEGER,
                scientific_name TEXT UNIQUE NOT NULL,
                name_uk TEXT,
                name_en TEXT,
                family TEXT,
                order_name TEXT,
                genus TEXT,
                edibility TEXT,
                hymenium TEXT,
                desc_uk TEXT,
                desc_en TEXT,
                lookalikes_uk TEXT,
                lookalikes_en TEXT,
                props_cap TEXT,
                props_hymenium TEXT,
                props_stem TEXT,
                props_flesh TEXT,
                props_season TEXT,
                props_habitat TEXT,
                wiki_url_uk TEXT,
                wiki_url_en TEXT,
                photos_count INTEGER DEFAULT 0,
                updated_at INTEGER
            );
        """)
        conn.execute("INSERT OR IGNORE INTO taxa SELECT * FROM taxa_old;")
        conn.execute("DROP TABLE taxa_old;")
        conn.execute("PRAGMA foreign_keys = ON;")
        conn.commit()
        print("[MIGRATE] Migration completed successfully. All data preserved!")
    else:
        # Ensure table exists
        conn.execute("""
            CREATE TABLE IF NOT EXISTS taxa (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                inat_id INTEGER,
                scientific_name TEXT UNIQUE NOT NULL,
                name_uk TEXT,
                name_en TEXT,
                family TEXT,
                order_name TEXT,
                genus TEXT,
                edibility TEXT,
                hymenium TEXT,
                desc_uk TEXT,
                desc_en TEXT,
                lookalikes_uk TEXT,
                lookalikes_en TEXT,
                props_cap TEXT,
                props_hymenium TEXT,
                props_stem TEXT,
                props_flesh TEXT,
                props_season TEXT,
                props_habitat TEXT,
                wiki_url_uk TEXT,
                wiki_url_en TEXT,
                photos_count INTEGER DEFAULT 0,
                updated_at INTEGER
            );
        """)

    conn.execute("""
        CREATE TABLE IF NOT EXISTS photos (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            taxon_id INTEGER NOT NULL,
            photo_index INTEGER NOT NULL,
            original_url TEXT,
            attribution TEXT,
            license_code TEXT,
            width INTEGER,
            height INTEGER,
            image_data BLOB NOT NULL,
            FOREIGN KEY (taxon_id) REFERENCES taxa(id) ON DELETE CASCADE
        );
    """)

    conn.execute("CREATE INDEX IF NOT EXISTS idx_taxa_sci_name ON taxa(scientific_name);")
    conn.execute("CREATE INDEX IF NOT EXISTS idx_taxa_inat_id ON taxa(inat_id);")
    conn.execute("CREATE INDEX IF NOT EXISTS idx_taxa_name_uk ON taxa(name_uk);")
    conn.execute("CREATE INDEX IF NOT EXISTS idx_taxa_name_en ON taxa(name_en);")
    conn.execute("CREATE INDEX IF NOT EXISTS idx_photos_taxon_id ON photos(taxon_id);")
    conn.commit()
    return conn


def load_species_list(classes_path: str, labels_path: str) -> Tuple[List[str], Dict[str, str]]:
    labels_edibility = {}
    species_set = set()

    def priority(e: str) -> int:
        return {"deadly": 5, "toxic": 4, "cond-edible": 3, "edible": 2}.get(e, 1)

    if os.path.exists(labels_path):
        with open(labels_path, "r", encoding="utf-8") as f:
            for raw_line in f:
                line = raw_line.strip()
                if not line or not line.startswith("("):
                    continue
                end_idx = line.find(")")
                if end_idx > 1:
                    tag = line[1:end_idx].strip().lower()
                    raw_name = line[end_idx + 1:].strip().replace("_", " ").strip()
                    edibility = {
                        "deadly": "deadly",
                        "poisonous": "toxic",
                        "conditionally_edible": "cond-edible",
                        "edible": "edible"
                    }.get(tag, "unknown")
                    clean_name = " ".join(raw_name.split())
                    if clean_name:
                        species_set.add(clean_name)
                        low = clean_name.lower()
                        existing = labels_edibility.get(low)
                        if existing is None or priority(edibility) > priority(existing):
                            labels_edibility[low] = edibility

    if os.path.exists(classes_path):
        with open(classes_path, "r", encoding="utf-8") as f:
            classes_data = json.load(f)
            for item in classes_data:
                sp = item.get("species", "").strip()
                clean_name = " ".join(sp.split())
                if clean_name:
                    species_set.add(clean_name)

    sorted_species = sorted(list(species_set), key=lambda s: s.lower())
    return sorted_species, labels_edibility


def fetch_inat_id_only(scientific_name: str) -> Optional[Tuple[int, dict]]:
    search_url = "https://api.inaturalist.org/v1/taxa"
    params = {"q": scientific_name, "taxon_id": 47170, "rank": "species", "per_page": 5}
    res = http_get(search_url, params=params)
    if not res:
        res = http_get(search_url, params={"q": scientific_name, "per_page": 5})

    if not res:
        return None

    results = res.json().get("results", [])
    if not results:
        return None

    target = None
    for item in results:
        if item.get("name", "").strip().lower() == scientific_name.lower():
            target = item
            break
    if not target:
        target = results[0]

    tid = target.get("id")
    return (tid, target) if tid else None


def optimize_image_to_webp(image_bytes: bytes, max_size: int = 720, quality: int = 80) -> Optional[Tuple[bytes, int, int]]:
    try:
        img = Image.open(io.BytesIO(image_bytes))
        if img.mode not in ("RGB", "RGBA"):
            img = img.convert("RGB")
        elif img.mode == "RGBA":
            bg = Image.new("RGB", img.size, (20, 20, 20))
            bg.paste(img, mask=img.split()[3])
            img = bg
        img.thumbnail((max_size, max_size), Image.Resampling.LANCZOS)
        out_buf = io.BytesIO()
        img.save(out_buf, format="WEBP", quality=quality, method=4)
        return out_buf.getvalue(), img.width, img.height
    except Exception:
        return None


def fetch_wikipedia_summary(title: str, lang: str = "uk") -> Tuple[Optional[str], Optional[str]]:
    encoded = urllib.parse.quote(title)
    url = f"https://{lang}.wikipedia.org/api/rest_v1/page/summary/{encoded}"
    res = http_get(url)
    if res and res.status_code == 200:
        try:
            data = res.json()
            extract = data.get("extract", "").strip()
            page_url = data.get("content_urls", {}).get("desktop", {}).get("page")
            if extract and not extract.startswith("Redirect"):
                return extract, page_url
        except Exception:
            pass
    return None, None


def clone_from_donor(conn: sqlite3.Connection, donor_id: int, new_scientific_name: str, inat_id: int) -> bool:
    cur = conn.cursor()
    cur.execute("""
        SELECT name_uk, name_en, family, order_name, genus, edibility, hymenium,
               desc_uk, desc_en, lookalikes_uk, lookalikes_en, props_cap, props_hymenium,
               props_stem, props_flesh, props_season, props_habitat, wiki_url_uk, wiki_url_en,
               photos_count
        FROM taxa WHERE id = ?
    """, (donor_id,))
    donor = cur.fetchone()
    if not donor:
        return False

    now_ts = int(time.time())
    cur.execute("""
        INSERT INTO taxa (
            inat_id, scientific_name, name_uk, name_en, family, order_name, genus,
            edibility, hymenium, desc_uk, desc_en, lookalikes_uk, lookalikes_en,
            props_cap, props_hymenium, props_stem, props_flesh, props_season, props_habitat,
            wiki_url_uk, wiki_url_en, photos_count, updated_at
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
    """, (
        inat_id, new_scientific_name, donor[0], donor[1], donor[2], donor[3], donor[4],
        donor[5], donor[6], donor[7], donor[8], donor[9], donor[10],
        donor[11], donor[12], donor[13], donor[14], donor[15], donor[16],
        donor[17], donor[18], donor[19], now_ts
    ))
    new_taxon_id = cur.lastrowid

    # Instant copy all photos from donor without re-downloading!
    cur.execute("""
        INSERT INTO photos (taxon_id, photo_index, original_url, attribution, license_code, width, height, image_data)
        SELECT ?, photo_index, original_url, attribution, license_code, width, height, image_data
        FROM photos WHERE taxon_id = ?
    """, (new_taxon_id, donor_id))

    conn.commit()
    return True


def fetch_full_species(conn: sqlite3.Connection, species_name: str, inat_id: int, target_obj: dict, labels_edibility: Dict[str, str]) -> bool:
    cur = conn.cursor()
    genus = species_name.split()[0].lower() if species_name.split() else ""
    low_name = species_name.lower()

    edibility = labels_edibility.get(low_name)
    if not edibility:
        parts = low_name.split()
        if len(parts) >= 2:
            edibility = labels_edibility.get(f"{parts[0]} {parts[1]}")
    if not edibility:
        edibility = "unknown"

    hymenium = "tubes" if genus in TUBES_GENERA else "gills"

    # Fetch full taxon details with photos
    detail_url = f"https://api.inaturalist.org/v1/taxa/{inat_id}"
    d_res = http_get(detail_url, params={"all_names": "true"})
    inat_taxon = d_res.json().get("results", [{}])[0] if d_res else target_obj

    name_uk = None
    name_en = None
    family = None
    order_name = None
    wiki_summary_inat = inat_taxon.get("wikipedia_summary")
    wiki_url_inat = inat_taxon.get("wikipedia_url")

    for anc in inat_taxon.get("ancestors", []):
        if anc.get("rank") == "family":
            family = anc.get("name")
        elif anc.get("rank") == "order":
            order_name = anc.get("name")

    for n_item in inat_taxon.get("names", []):
        loc = n_item.get("locale")
        val = n_item.get("name", "").strip()
        if loc == "uk" and not name_uk:
            name_uk = val
        elif loc == "en" and not name_en:
            name_en = val

    if not name_en:
        name_en = inat_taxon.get("preferred_common_name")

    desc_uk, wiki_url_uk = None, None
    if name_uk:
        desc_uk, wiki_url_uk = fetch_wikipedia_summary(name_uk, lang="uk")
    if not desc_uk:
        desc_uk, wiki_url_uk = fetch_wikipedia_summary(species_name, lang="uk")

    desc_en, wiki_url_en = fetch_wikipedia_summary(species_name, lang="en")
    if not desc_en:
        desc_en = wiki_summary_inat
    if not wiki_url_en:
        wiki_url_en = wiki_url_inat

    taxon_photos = inat_taxon.get("taxon_photos", [])
    if not taxon_photos and inat_taxon.get("default_photo"):
        taxon_photos = [{"photo": inat_taxon.get("default_photo")}]

    now_ts = int(time.time())
    cur.execute("""
        INSERT INTO taxa (
            inat_id, scientific_name, name_uk, name_en, family, order_name, genus,
            edibility, hymenium, desc_uk, desc_en, wiki_url_uk, wiki_url_en, photos_count, updated_at
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
    """, (
        inat_id, species_name, name_uk, name_en, family, order_name, genus,
        edibility, hymenium, desc_uk, desc_en, wiki_url_uk, wiki_url_en, len(taxon_photos), now_ts
    ))
    new_id = cur.lastrowid

    saved_photos = 0
    for idx, tp in enumerate(taxon_photos):
        p = tp.get("photo", {})
        p_url = p.get("medium_url") or p.get("url")
        if p_url:
            img_res = http_get(p_url)
            if img_res and img_res.status_code == 200:
                converted = optimize_image_to_webp(img_res.content)
                if converted:
                    webp_data, w, h = converted
                    cur.execute("""
                        INSERT INTO photos (taxon_id, photo_index, original_url, attribution, license_code, width, height, image_data)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """, (new_id, idx, p_url, p.get("attribution", ""), p.get("license_code", ""), w, h, webp_data))
                    saved_photos += 1

    cur.execute("UPDATE taxa SET photos_count = ? WHERE id = ?", (saved_photos, new_id))
    conn.commit()
    return True


def main():
    script_dir = os.path.dirname(os.path.abspath(__file__))
    repo_root = os.path.dirname(script_dir) if os.path.basename(script_dir) == "scripts" else script_dir

    classes_candidates = [
        "classes.json",
        "model/classes.json",
        os.path.join(repo_root, "classes.json"),
        os.path.join(repo_root, "model", "classes.json")
    ]
    default_classes = next((p for p in classes_candidates if os.path.exists(p)), os.path.join(repo_root, "classes.json"))
    default_labels = "app/src/main/assets/labels.txt" if os.path.exists("app/src/main/assets/labels.txt") else os.path.join(repo_root, "app", "src", "main", "assets", "labels.txt")
    default_db = "mushrooms_offline.db" if os.path.exists("mushrooms_offline.db") else os.path.join(repo_root, "mushrooms_offline.db")

    parser = argparse.ArgumentParser(description="Quick UNIQUE Error Fixer & Duplicate Resolver")
    parser.add_argument("--db", default=default_db, help="Path to mushrooms_offline.db")
    parser.add_argument("--classes", default=default_classes, help="Path to classes.json")
    parser.add_argument("--labels", default=default_labels, help="Path to labels.txt")
    parser.add_argument("--delay", type=float, default=0.5, help="Delay between API requests")
    parser.add_argument("--only-clones", action="store_true", help="Only resolve duplicates with existing donors (super fast, ~10s)")
    args = parser.parse_args()

    print("=" * 65)
    print("  Mushroom Offline DB - Quick UNIQUE Error Fixer & Duplicate Resolver")
    print("=" * 65)

    if not os.path.exists(args.db):
        print(f"[ERROR] Database file not found: {args.db}")
        sys.exit(1)

    # 1. Migrate schema
    conn = migrate_and_connect_db(args.db)

    # 2. Find missing species
    species_list, labels_edibility = load_species_list(args.classes, args.labels)
    cur = conn.cursor()
    cur.execute("SELECT LOWER(scientific_name) FROM taxa WHERE updated_at IS NOT NULL")
    done_set = set(r[0] for r in cur.fetchall())

    missing = [sp for sp in species_list if sp.lower() not in done_set]
    print(f"\nTotal species in list: {len(species_list)}")
    print(f"Already in database:   {len(done_set)}")
    print(f"Missing to resolve:    {len(missing)}\n")

    if not missing:
        print("[OK] Database is already 100% complete! No missing records.")
        conn.close()
        return

    # 3. Process missing species
    cloned_count = 0
    fetched_count = 0
    skipped_count = 0

    pbar = tqdm(missing, desc="Resolving missing species", unit="sp")
    for sp in pbar:
        try:
            # Check if this species maps to an already-downloaded inat_id
            inat_result = fetch_inat_id_only(sp)
            time.sleep(args.delay)

            if not inat_result:
                skipped_count += 1
                continue

            inat_id, target_obj = inat_result

            # Check if we already have this inat_id in taxa
            cur.execute("SELECT id, scientific_name FROM taxa WHERE inat_id = ? ORDER BY id ASC LIMIT 1", (inat_id,))
            donor_row = cur.fetchone()

            if donor_row:
                donor_id, donor_name = donor_row
                # INSTANT CLONE (0.001s, no image re-download!)
                success = clone_from_donor(conn, donor_id, sp, inat_id)
                if success:
                    cloned_count += 1
                    pbar.set_postfix_str(f"Cloned '{sp}' from '{donor_name}'")
            else:
                if not args.only_clones:
                    # Brand new species, download full
                    success = fetch_full_species(conn, sp, inat_id, target_obj, labels_edibility)
                    if success:
                        fetched_count += 1
                        pbar.set_postfix_str(f"Downloaded '{sp}'")
                else:
                    skipped_count += 1
        except KeyboardInterrupt:
            print("\n[!] Interrupted by user. Progress safely committed.")
            break
        except Exception as e:
            print(f"\n[!] Error resolving '{sp}': {e}")

    print("\n" + "=" * 65)
    print("  SUMMARY")
    print("=" * 65)
    print(f"  Instantly Cloned from Existing Donors (0 MB downloaded): {cloned_count}")
    print(f"  Full Downloads for Truly New Species:                    {fetched_count}")
    print(f"  Skipped / Unmatched:                                     {skipped_count}")

    # Optimize
    print("\nOptimizing database (VACUUM)...")
    conn.execute("VACUUM;")
    conn.close()

    db_size_mb = os.path.getsize(args.db) / (1024 * 1024)
    print(f"Done! Final database size: {db_size_mb:.2f} MB")


if __name__ == "__main__":
    main()
