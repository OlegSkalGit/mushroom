#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Unified SQLite Database & Archive Manager for Mushroom App.

Combines functionality of:
- 1_build_offline_db.py (iNaturalist / Wikipedia fetch + WebP optimization)
- 2_repair_offline_db.py (synonym resolution + metadata normalization)
- 3_fix_photo_duplicates.py (URL/MD5 hash deduplication + photo_index normalization)
- 4_count_mushrooms.py (detailed statistical reporting)
- Multi-volume archive creation + MushroomDataConfig.kt auto-updater
"""

import os
import sys
import io
import time
import json
import sqlite3
import hashlib
import argparse
import subprocess
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

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
REPO_ROOT = os.path.dirname(SCRIPT_DIR) if os.path.basename(SCRIPT_DIR) == "scripts" else SCRIPT_DIR
DB_PATH = os.path.join(REPO_ROOT, "downloads", "mushrooms.db")
CLASSES_JSON_PATH = os.path.join(REPO_ROOT, "app", "src", "main", "assets", "classes.json")
CONFIG_KT_PATH = os.path.join(REPO_ROOT, "app", "src", "main", "java", "com", "olegskal", "mushroom", "mushrooms", "MushroomDataConfig.kt")
VOLUME_SIZE_BYTES = 69206016

KNOWN_SYNONYMS = {
    "lepista nuda": "Clitocybe nuda",
    "agaricus sylvaticus": "Agaricus silvaticus",
    "lactifluus volemus": "Lactarius volemus",
    "picipes badius": "Cerioporus badius",
}


def get_db_connection(db_path: str = DB_PATH) -> sqlite3.Connection:
    if not os.path.exists(db_path):
        raise FileNotFoundError(f"Database not found: {db_path}")
    return sqlite3.connect(db_path)


def load_classes_json(classes_path: str = CLASSES_JSON_PATH) -> List[Dict[str, Any]]:
    if not os.path.exists(classes_path):
        raise FileNotFoundError(f"classes.json not found: {classes_path}")
    with open(classes_path, "r", encoding="utf-8") as f:
        return json.load(f)


def cmd_stats(args):
    conn = get_db_connection(args.db)
    cur = conn.cursor()

    cur.execute("SELECT COUNT(*) FROM taxa")
    total_taxa = cur.fetchone()[0]

    cur.execute("SELECT COUNT(*) FROM photos")
    total_photos = cur.fetchone()[0]

    cur.execute("SELECT COUNT(*) FROM taxa WHERE photos_count > 0")
    with_photos = cur.fetchone()[0]

    cur.execute("SELECT edibility, COUNT(*) FROM taxa GROUP BY edibility ORDER BY COUNT(*) DESC")
    edib_stats = cur.fetchall()

    cur.execute("SELECT hymenium, COUNT(*) FROM taxa GROUP BY hymenium ORDER BY COUNT(*) DESC")
    hym_stats = cur.fetchall()

    classes = load_classes_json(args.classes)
    classes_names = {c["species"].strip().lower(): c["species"].strip() for c in classes}

    cur.execute("SELECT scientific_name FROM taxa")
    db_names = {r[0].strip().lower(): r[0].strip() for r in cur.fetchall()}

    missing_in_db = [orig for low, orig in classes_names.items() if low not in db_names and KNOWN_SYNONYMS.get(low, "").lower() not in db_names]

    db_size_mb = os.path.getsize(args.db) / (1024 * 1024)

    print("\n" + "=" * 65)
    print(f"   СТАТИСТИКА БАЗИ ДАНИХ: {os.path.basename(args.db)}")
    print("=" * 65)
    print(f" Файл:                 {args.db}")
    print(f" Розмір:               {db_size_mb:.2f} MB")
    print(f" Видів у базі:         {total_taxa}")
    print(f" Видів у classes.json: {len(classes_names)}")
    print(f" Всього фотографій:    {total_photos} ({with_photos}/{total_taxa} видів мають фото)")
    print(f" Відсутні у базі:      {len(missing_in_db)}")

    print("\n Розподіл їстівності:")
    for ed, cnt in edib_stats:
        print(f"  • {str(ed):<16} {cnt:>5} ({cnt/total_taxa*100:>5.1f}%)")

    print("\n Розподіл гіменофору:")
    for hm, cnt in hym_stats:
        print(f"  • {str(hm):<16} {cnt:>5} ({cnt/total_taxa*100:>5.1f}%)")

    if missing_in_db:
        print("\n Гриби з classes.json, яких немає в базі:")
        for m in sorted(missing_in_db):
            print(f"  ! {m}")

    print("=" * 65 + "\n")
    conn.close()


def cmd_sync(args):
    print("[*] Синхронізація їстівності та гіменофору з classes.json...")
    classes = load_classes_json(args.classes)
    conn = get_db_connection(args.db)
    cur = conn.cursor()

    updated_count = 0
    for item in classes:
        species = item["species"].strip()
        edibility = item.get("edibility", "inedible")
        hymenium = item.get("hymenium", "other")

        cur.execute("""
            UPDATE taxa
            SET edibility = ?, hymenium = ?
            WHERE LOWER(scientific_name) = LOWER(?)
        """, (edibility, hymenium, species))
        if cur.rowcount > 0:
            updated_count += cur.rowcount
        elif species.lower() in KNOWN_SYNONYMS:
            syn = KNOWN_SYNONYMS[species.lower()]
            cur.execute("""
                UPDATE taxa
                SET edibility = ?, hymenium = ?
                WHERE LOWER(scientific_name) = LOWER(?)
            """, (edibility, hymenium, syn))
            updated_count += cur.rowcount

    conn.commit()
    print(f"[✓] Оновлено таксонів у базі: {updated_count}")
    conn.close()


def cmd_dedup(args):
    print("[*] Виявлення та видалення дублікатів фото...")
    conn = get_db_connection(args.db)
    cur = conn.cursor()

    cur.execute("SELECT id, taxon_id, original_url FROM photos ORDER BY taxon_id, id")
    all_photos = cur.fetchall()

    seen_keys = set()
    to_delete = []

    for pid, tid, url in all_photos:
        if url and url.strip():
            key = (tid, "url", url.strip())
        else:
            cur.execute("SELECT image_data FROM photos WHERE id = ?", (pid,))
            blob = cur.fetchone()[0]
            blob_hash = hashlib.md5(blob).hexdigest()
            key = (tid, "hash", blob_hash)

        if key in seen_keys:
            to_delete.append(pid)
        else:
            seen_keys.add(key)

    if to_delete:
        print(f"[*] Видалення {len(to_delete)} дублікатів фото...")
        for i in range(0, len(to_delete), 900):
            batch = to_delete[i:i + 900]
            cur.execute(f"DELETE FROM photos WHERE id IN ({','.join('?' for _ in batch)})", batch)
        conn.commit()
    else:
        print("[✓] Дублікатів не виявлено.")

    # Orphan cleanup
    cur.execute("DELETE FROM photos WHERE taxon_id NOT IN (SELECT id FROM taxa)")
    conn.commit()

    # Reindex photos
    cur.execute("SELECT DISTINCT taxon_id FROM photos")
    tids = [r[0] for r in cur.fetchall()]
    for tid in tids:
        cur.execute("SELECT id FROM photos WHERE taxon_id = ? ORDER BY photo_index ASC, id ASC", (tid,))
        rows = cur.fetchall()
        for idx, (p_id,) in enumerate(rows):
            cur.execute("UPDATE photos SET photo_index = ? WHERE id = ?", (idx, p_id))

    # Update counts
    cur.execute("UPDATE taxa SET photos_count = (SELECT COUNT(*) FROM photos WHERE photos.taxon_id = taxa.id)")
    conn.commit()
    print("[✓] Лічильники фото перераховано.")
    conn.close()


def fetch_inat_data(species_name: str) -> Optional[Dict[str, Any]]:
    headers = {"User-Agent": "MushroomEncyclopediaApp/2.0 (offline-builder)"}
    search_url = "https://api.inaturalist.org/v1/taxa"
    params = {"q": species_name, "taxon_id": 47170, "rank": "species", "per_page": 5}
    try:
        r = requests.get(search_url, params=params, headers=headers, timeout=10)
        if r.status_code != 200 or not r.json().get("results"):
            params = {"q": species_name, "per_page": 5}
            r = requests.get(search_url, params=params, headers=headers, timeout=10)
            if r.status_code != 200 or not r.json().get("results"):
                return None

        results = r.json()["results"]
        target = next((item for item in results if item.get("name", "").lower() == species_name.lower()), results[0])
        target_id = target.get("id")

        detail_url = f"https://api.inaturalist.org/v1/taxa/{target_id}"
        d_res = requests.get(detail_url, params={"all_names": "true"}, headers=headers, timeout=10)
        if d_res.status_code == 200 and d_res.json().get("results"):
            return d_res.json()["results"][0]
        return target
    except Exception as e:
        print(f"[!] Помилка запиту iNaturalist для {species_name}: {e}")
        return None


def fetch_wiki_summary(title: str, lang: str = "uk") -> Tuple[Optional[str], Optional[str]]:
    encoded = urllib.parse.quote(title)
    url = f"https://{lang}.wikipedia.org/api/rest_v1/page/summary/{encoded}"
    try:
        r = requests.get(url, headers={"User-Agent": "MushroomEncyclopedia/2.0"}, timeout=10)
        if r.status_code == 200:
            data = r.json()
            extract = data.get("extract", "").strip()
            page_url = data.get("content_urls", {}).get("desktop", {}).get("page")
            if extract and not extract.startswith("Redirect"):
                return extract, page_url
    except Exception:
        pass
    return None, None


def optimize_webp(image_bytes: bytes, max_size: int = 720, quality: int = 80) -> Optional[Tuple[bytes, int, int]]:
    try:
        img = Image.open(io.BytesIO(image_bytes))
        if img.mode not in ("RGB", "RGBA"):
            img = img.convert("RGB")
        elif img.mode == "RGBA":
            bg = Image.new("RGB", img.size, (20, 20, 20))
            bg.paste(img, mask=img.split()[3])
            img = bg
        img.thumbnail((max_size, max_size), Image.Resampling.LANCZOS)
        out = io.BytesIO()
        img.save(out, format="WEBP", quality=quality, method=4)
        return out.getvalue(), img.width, img.height
    except Exception:
        return None


def cmd_fetch_missing(args):
    classes = load_classes_json(args.classes)
    conn = get_db_connection(args.db)
    cur = conn.cursor()

    cur.execute("SELECT scientific_name FROM taxa")
    db_names = {r[0].strip().lower(): r[0].strip() for r in cur.fetchall()}

    missing = []
    for item in classes:
        sp = item["species"].strip()
        low = sp.lower()
        if low not in db_names and KNOWN_SYNONYMS.get(low, "").lower() not in db_names:
            missing.append(item)

    print(f"[*] Виявлено відсутніх видів для завантаження: {len(missing)}")
    if not missing:
        conn.close()
        return

    added = 0
    for idx, item in enumerate(missing):
        sp = item["species"].strip()
        edibility = item.get("edibility", "inedible")
        hymenium = item.get("hymenium", "other")
        print(f"[{idx+1}/{len(missing)}] Завантаження даних для '{sp}'...")

        inat = fetch_inat_data(sp)
        inat_id = inat.get("id") if inat else None
        name_uk = None
        name_en = inat.get("preferred_common_name") if inat else None
        family = None
        order_name = None
        genus = sp.split()[0] if " " in sp else sp

        if inat:
            for anc in inat.get("ancestors", []):
                if anc.get("rank") == "family":
                    family = anc.get("name")
                elif anc.get("rank") == "order":
                    order_name = anc.get("name")

            for n in inat.get("names", []):
                if n.get("locale") == "uk" and not name_uk:
                    name_uk = n.get("name")
                elif n.get("locale") == "en" and not name_en:
                    name_en = n.get("name")

        desc_uk, wiki_uk = fetch_wiki_summary(name_uk or sp, "uk")
        desc_en, wiki_en = fetch_wiki_summary(sp, "en")
        if not desc_en and inat:
            desc_en = inat.get("wikipedia_summary")
            wiki_en = inat.get("wikipedia_url")

        cur.execute("""
            INSERT INTO taxa (
                inat_id, scientific_name, name_uk, name_en, family, order_name, genus,
                edibility, hymenium, desc_uk, desc_en, wiki_url_uk, wiki_url_en,
                photos_count, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """, (
            inat_id, sp, name_uk, name_en, family, order_name, genus,
            edibility, hymenium, desc_uk, desc_en, wiki_uk, wiki_en,
            0, int(time.time())
        ))
        taxon_id = cur.lastrowid

        # Download up to 6 photos
        photos = inat.get("taxon_photos", []) if inat else []
        saved_photos = 0
        for p_idx, tp in enumerate(photos[:6]):
            p = tp.get("photo", {})
            p_url = p.get("medium_url") or p.get("url") or p.get("original_url")
            if not p_url:
                continue
            try:
                img_res = requests.get(p_url, timeout=10)
                if img_res.status_code == 200:
                    conv = optimize_webp(img_res.content)
                    if conv:
                        w_bytes, w, h = conv
                        cur.execute("""
                            INSERT INTO photos (taxon_id, photo_index, original_url, attribution, license_code, width, height, image_data)
                            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        """, (taxon_id, saved_photos, p_url, p.get("attribution", ""), p.get("license_code", ""), w, h, w_bytes))
                        saved_photos += 1
            except Exception as pe:
                print(f"  [!] Фото помилка: {pe}")

        cur.execute("UPDATE taxa SET photos_count = ? WHERE id = ?", (saved_photos, taxon_id))
        conn.commit()
        added += 1
        time.sleep(1.0)

    print(f"[✓] Успішно додано нових видів: {added}")
    conn.close()


def cmd_repack(args):
    print("[*] Оптимізація бази даних (VACUUM)...")
    conn = get_db_connection(args.db)
    conn.execute("VACUUM;")
    conn.close()

    db_size = os.path.getsize(args.db)
    print(f"[✓] База оптимізована. Розмір: {db_size} байт ({db_size / (1024*1024):.2f} MB)")

    downloads_dir = os.path.dirname(args.db)
    print(f"[*] Створення багатотомного архіву в {downloads_dir} (розмір тому: {VOLUME_SIZE_BYTES} байт)...")

    # Remove old volume files
    for fname in os.listdir(downloads_dir):
        if fname.startswith("mushrooms.z") or fname == "mushrooms.zip" or fname.startswith("mushrooms.zip."):
            os.remove(os.path.join(downloads_dir, fname))

    temp_zip = os.path.join(downloads_dir, "mushrooms.zip")
    winrar_candidates = [
        r"C:\Program Files\WinRAR\WinRAR.exe",
        r"C:\Program Files (x86)\WinRAR\WinRAR.exe"
    ]
    winrar_bin = next((p for p in winrar_candidates if os.path.exists(p)), None)

    if winrar_bin:
        print("[*] Пакування через WinRAR (максимальне стиснення -m5, без шляхів -ep)...")
        cmd = [
            winrar_bin, "a", "-ibck", "-afzip", "-m5", "-ep",
            f"-v{VOLUME_SIZE_BYTES}b",
            temp_zip,
            args.db
        ]
        ret = subprocess.run(cmd, capture_output=True, text=True)
        if ret.returncode != 0:
            print(f"[!] Помилка WinRAR: {ret.stderr}")
            return
    else:
        print("[*] WinRAR не знайдено, пакування через 7-Zip...")
        cmd = [
            "7z", "a", "-tzip",
            f"-v{VOLUME_SIZE_BYTES}b",
            "-mx=5",
            "mushrooms.zip",
            os.path.basename(args.db)
        ]
        ret = subprocess.run(cmd, cwd=downloads_dir, capture_output=True, text=True)
        if ret.returncode != 0:
            print(f"[!] Помилка 7-Zip: {ret.stderr}")
            return
        split_files = sorted([f for f in os.listdir(downloads_dir) if f.startswith("mushrooms.zip.")])
        if split_files:
            for i, sf in enumerate(split_files[:-1], 1):
                os.rename(os.path.join(downloads_dir, sf), os.path.join(downloads_dir, f"mushrooms.z{i:02d}"))
            os.rename(os.path.join(downloads_dir, split_files[-1]), temp_zip)

    # Check created volumes
    volumes = sorted([f for f in os.listdir(downloads_dir) if f.startswith("mushrooms.z") or f == "mushrooms.zip"])
    print(f"[✓] Створено томів: {len(volumes)}")
    last_volume_size = 0
    for v in volumes:
        sz = os.path.getsize(os.path.join(downloads_dir, v))
        print(f"  • {v}: {sz} байт")
        if v == "mushrooms.zip":
            last_volume_size = sz

    # Auto-update MushroomDataConfig.kt
    if os.path.exists(CONFIG_KT_PATH):
        with open(CONFIG_KT_PATH, "r", encoding="utf-8") as f:
            cfg = f.read()

        import re
        cfg = re.sub(r"const val DB_FULL_SIZE = \d+L", f"const val DB_FULL_SIZE = {db_size}L", cfg)
        cfg = re.sub(r"DataFileItem\(\"mushrooms\.zip\", \d+L\)", f'DataFileItem("mushrooms.zip", {last_volume_size}L)', cfg)

        with open(CONFIG_KT_PATH, "w", encoding="utf-8") as f:
            f.write(cfg)
        print(f"[✓] MushroomDataConfig.kt оновлено: DB_FULL_SIZE={db_size}L, mushrooms.zip={last_volume_size}L")


def main():
    parser = argparse.ArgumentParser(description="Mushroom Database & Archive Manager")
    parser.add_argument("--db", default=DB_PATH, help="Path to mushrooms.db")
    parser.add_argument("--classes", default=CLASSES_JSON_PATH, help="Path to classes.json")

    sub = parser.add_subparsers(dest="command", required=True)
    sub.add_parser("stats", help="Аналітика бази даних та звірка з classes.json")
    sub.add_parser("sync", help="Синхронізація edibility та hymenium з classes.json")
    sub.add_parser("dedup", help="Очищення дублікатів фото та нормалізація індексів")
    sub.add_parser("fetch_missing", help="Завантаження відсутніх видів з iNaturalist/Wikipedia")
    sub.add_parser("repack", help="VACUUM + створення багатотомного архіву + конфіг")
    sub.add_parser("all", help="Повний цикл: sync + fetch_missing + dedup + repack")

    args = parser.parse_args()

    if args.command == "stats":
        cmd_stats(args)
    elif args.command == "sync":
        cmd_sync(args)
    elif args.command == "dedup":
        cmd_dedup(args)
    elif args.command == "fetch_missing":
        cmd_fetch_missing(args)
    elif args.command == "repack":
        cmd_repack(args)
    elif args.command == "all":
        cmd_sync(args)
        cmd_fetch_missing(args)
        cmd_dedup(args)
        cmd_repack(args)
        cmd_stats(args)


if __name__ == "__main__":
    main()
