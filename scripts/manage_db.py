#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Unified SQLite Database & Archive Manager for Mushroom App.

Combines functionality of:
- iNaturalist & Wikipedia metadata and section enrichment
- Taxonomy hierarchy resolution (phylum, class, order, family, genus)
- Conservation status & global observation counts
- Multi-section descriptions: Ecology/Habitat, Toxicity/Lookalikes, Culinary/Use
- Curated MycoKnowledge properties (cap, hymenophore, stem, flesh, season, habitat)
- Multi-volume 7z/WinRAR archive creation + MushroomDataConfig.kt auto-updater
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
import re
import urllib.parse
import threading
import concurrent.futures
from typing import Dict, List, Optional, Tuple, Any

import requests
from urllib3.util import Retry
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
VOLUME_SIZE_BYTES = 70000000
NEW_ENRICHMENT_EPOCH = 1791100000  # Мітка часу впровадження нового детального парсера (04.10.2026)

HTTP_HEADERS = {
    "User-Agent": "MushroomEncyclopedia/2.0 (Android forest navigator; contact@olegskal.com)"
}

_thread_local = threading.local()


def get_session() -> requests.Session:
    if not hasattr(_thread_local, "session"):
        s = requests.Session()
        retries = Retry(total=3, backoff_factor=0.3, status_forcelist=[429, 500, 502, 503, 504])
        adapter = requests.adapters.HTTPAdapter(pool_connections=20, pool_maxsize=20, max_retries=retries)
        s.mount("https://", adapter)
        s.mount("http://", adapter)
        s.headers.update(HTTP_HEADERS)
        _thread_local.session = s
    return _thread_local.session


def clean_str(val: Optional[str]) -> Optional[str]:
    if val is None:
        return None
    val = val.strip()
    return val if len(val) > 0 else None

KNOWN_SYNONYMS = {
    "lepista nuda": "Clitocybe nuda",
    "agaricus sylvaticus": "Agaricus silvaticus",
    "lactifluus volemus": "Lactarius volemus",
    "picipes badius": "Cerioporus badius",
    "apioperdon pyriforme": "Lycoperdon pyriforme",
    "candolleomyces candolleanus": "Psathyrella candolleana",
    "neoboletus erythropus": "Neoboletus luridiformis",
    "pseudoinonotus dryadeus": "Inonotus dryadeus",
    "bovistella utriformis": "Calvatia utriformis",
    "xanthoporia radiata": "Inonotus radiatus",
    "fomitopsis betulina": "Piptoporus betulinus",
    "cerioporus squamosus": "Polyporus squamosus",
    "cerioporus varius": "Polyporus varius",
    "trametes cinnabarina": "Pycnoporus cinnabarinus",
    "gymnopilus spectabilis": "Gymnopilus junonius",
    "coprinellus micaceus": "Coprinus micaceus",
    "coprinellus disseminatus": "Coprinus disseminatus",
    "coprinopsis atramentaria": "Coprinus atramentarius",
    "chlorophyllum rhacodes": "Macrolepiota rhacodes",
    "leucocoprinus leucothites": "Lepiota naucina",
    "leucoagaricus leucothites": "Lepiota naucina",
    "laetiporus sulphureus": "Polyporus sulphureus",
    "daedaleopsis confragosa": "Daedalea confragosa",
    "flammulina velutipes": "Collybia velutipes",
    "kuehneromyces mutabilis": "Pholiota mutabilis",
    "armillaria mellea": "Clitocybe mellea",
    "imleria badia": "Xerocomus badius",
    "xerocomellus chrysenteron": "Xerocomus chrysenteron",
    "hortiboletus rubellus": "Xerocomus rubellus",
    "suillellus luridus": "Boletus luridus",
    "caloboletus calopus": "Boletus calopus",
    "rubroboletus satanas": "Boletus satanas",
}

CURATED_KNOWLEDGE: Dict[str, Dict[str, Any]] = {
    "amanita phalloides": {
        "edibility": "deadly",
        "hymenium": "gills",
        "lookalikes_uk": "СМЕРТЕЛЬНА НЕБЕЗПЕКА! Часто плутають із Зеленою Сироїжкою (у сироїжки ніколи немає вольви та кільця!) або Печерицею (у зрілих печериць пластинки рожеві чи шоколадно-коричневі, а в поганки — завжди чисто білі).",
        "lookalikes_en": "FATAL DANGER! Often confused with green Russula (Russulas lack a volva and ring) or Field Agaricus (Agaricus have pink-to-brown gills; death cap gills remain pure white).",
        "props_cap": "Оливково-зелена, шовковиста 5-15 см",
        "props_hymenium": "Пластинки завжди чисто БІЛІ",
        "props_stem": "Біла, з візерунком, кільцем та вільною вольвою (мішечком) внизу!",
        "props_flesh": "Білий, не змінює колір",
        "props_season": "Липень — Листопад",
        "props_habitat": "Листяні ліси (дуб, бук, береза)"
    },
    "amanita virosa": {
        "edibility": "deadly",
        "hymenium": "gills",
        "lookalikes_uk": "Мухомор білий смердючий. Смертельно отруйний! Плутають із білими печерицями. Має неприємний запах, білі пластинки та вольву внизу ніжки.",
        "lookalikes_en": "Destroying Angel. Lethally toxic! Confused with white champignon mushrooms. Note pure white gills, persistent ring, and basal volva cup."
    },
    "galerina marginata": {
        "edibility": "deadly",
        "hymenium": "gills",
        "lookalikes_uk": "СМЕРТЕЛЬНИЙ ДВІЙНИК ОПЕНЬКІВ! Росте на пнях. На відміну від опенька, має коричневий споровий порошок, темнішу основу ніжки та борошнистий запах.",
        "lookalikes_en": "DEADLY LOOKALIKE OF HONEY FUNGUS! Contains amatoxins. Note rusty-brown spore print, smooth silky-fibrillose stem, and mealy smell."
    },
    "cortinarius rubellus": {
        "edibility": "deadly",
        "hymenium": "gills",
        "lookalikes_uk": "Павутинник красивіший. Смертельно отруйний (орелланін відмовляє нирки через 1-2 тижні). Плутають із лисичками або опеньками.",
        "lookalikes_en": "Deadly Webcap. Contains orellanine causing delayed renal necrosis. Rusty orange-brown with web-like cortina."
    },
    "amanita muscaria": {
        "edibility": "toxic",
        "hymenium": "gills",
        "lookalikes_uk": "Мухомор Цезаря (Amanita caesarea) — їстівний рідкісний гриб, має помаранчеві пластинки та ніжку без бородавок.",
        "lookalikes_en": "Caesar's Mushroom — rare edible with orange-yellow gills and smooth stem.",
        "props_cap": "Яскраво-червона з білими бородавками",
        "props_hymenium": "Вільні білі пластинки",
        "props_stem": "Біла з кільцем та бульбою",
        "props_flesh": "Білий, під шкіркою жовтуватий",
        "props_season": "Липень — Листопад",
        "props_habitat": "Хвойні та березові ліси"
    },
    "russula emetica": {
        "edibility": "toxic",
        "hymenium": "gills",
        "lookalikes_uk": "Сироїжка блювотна — отруйна, має пекучий гострий смак та яскраву червону шапинку.",
        "lookalikes_en": "The Sickener — poisonous, sharp peppery taste, bright red cap."
    },
    "amanita pantherina": {
        "edibility": "toxic",
        "hymenium": "gills",
        "lookalikes_uk": "Мухомор пантерний — сильно отруйний! Плутають із їстівним мухомором сіро-рожевим (Amanita rubescens). У їстівного м'якуш на зломі червоніє, у пантерного — ні!",
        "lookalikes_en": "Panther Cap — severe neurotoxicity! Easily confused with edible Blusher (Amanita rubescens). Blusher flesh turns pink/red on bruising; Panther cap remains white."
    },
    "hypholoma fasciculare": {
        "edibility": "toxic",
        "hymenium": "gills",
        "lookalikes_uk": "Несправжній опеньок сірчано-жовтий — ОТРУЙНИЙ. Відрізняється від справжнього опенька сірчано-зеленкуватими пластинками, відсутністю кільця та гірким смаком.",
        "lookalikes_en": "Sulphur Tuft — poisonous. Distinguished from edible honey fungus by greenish-yellow gills, lack of distinct ring, and intensely bitter taste."
    },
    "rubroboletus satanas": {
        "edibility": "toxic",
        "hymenium": "tubes",
        "lookalikes_uk": "Сатанинський гриб — шлунково-кишковий токсин. Попелясто-біла шапинка, криваво-червоні пори та синіючий м'якуш.",
        "lookalikes_en": "Devil's Bolete — chalky white cap, blood-red pores, and blue-staining flesh with foul odor."
    },
    "paxillus involutus": {
        "edibility": "toxic",
        "hymenium": "gills",
        "lookalikes_uk": "Свинуха тонка — НЕБЕЗПЕЧНО! Доведено смертельну кумулятивну токсичність (руйнує еритроцити крові).",
        "lookalikes_en": "Brown Roll-rim — TOXIC! Destroys red blood cells through cumulative autoimmune antibodies."
    },
    "tylopilus felleus": {
        "edibility": "inedible",
        "hymenium": "tubes",
        "lookalikes_uk": "Жовчний гриб (гірчак) — головний двійник білого гриба! Не отруйний, але нестерпно гіркий. Має темну опуклу сітку на ніжці та брудно-рожеві пори.",
        "lookalikes_en": "Bitter Bolete — prime lookalike of the Porcini. Not lethal, but intensely bitter. Coarse dark network on stem and pinkish pore mouths."
    },
    "boletus edulis": {
        "edibility": "edible",
        "hymenium": "tubes",
        "props_cap": "Опукла коричнева 8-25 см",
        "props_hymenium": "Трубчастий білий -> оливково-жовтий",
        "props_stem": "Товста клубнеподібна з білою сіточкою",
        "props_flesh": "Білий, ароматний, колір НЕ змінює",
        "props_season": "Червень — Листопад",
        "props_habitat": "Дубові, соснові та ялинові ліси",
        "lookalikes_uk": "Остерігайтеся жовчного гриба (Tylopilus felleus) з рожевими порами та гірким смаком.",
        "lookalikes_en": "Beware of the Bitter Bolete (Tylopilus felleus) with pinkish pore layer and bitter taste."
    },
    "cantharellus cibarius": {
        "edibility": "edible",
        "hymenium": "gills",
        "props_cap": "Лійкоподібна жовто-помаранчева",
        "props_hymenium": "Складки (псевдопластинки), що переходять на ніжку",
        "props_stem": "Зливається з шапинкою",
        "props_flesh": "Білувато-жовтий, запах абрикосів",
        "props_season": "Червень — Жовтень",
        "props_habitat": "Хвойні та листяні ліси",
        "lookalikes_uk": "Несправжня лисичка (Hygrophoropsis aurantiaca) — яскравіша, з тонкими справжніми пластинками; Омфалот (Omphalotus olearius) — отруйний, росте великими пучками на пнях.",
        "lookalikes_en": "False Chanterelle — thin true gills, darker orange; Jack-o'-Lantern (toxic) — clustered on dead hardwood."
    },
    "leccinum aurantiacum": {
        "edibility": "edible",
        "hymenium": "tubes",
        "props_cap": "Яскраво-оранжева або цегляно-червона",
        "props_hymenium": "Дрібні білувато-сірі пори",
        "props_stem": "Висока з темними лусочками",
        "props_flesh": "Щільний, синіє або чорніє на зломі",
        "props_season": "Червень — Жовтень",
        "props_habitat": "Під осиками та березами"
    },
    "suillus luteus": {"edibility": "edible", "hymenium": "tubes"},
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


def ensure_taxa_columns(conn: sqlite3.Connection):
    cur = conn.cursor()
    cur.execute("PRAGMA table_info(taxa)")
    existing_cols = {row[1] for row in cur.fetchall()}

    needed_cols = [
        ("phylum", "TEXT"),
        ("taxon_class", "TEXT"),
        ("conservation_status", "TEXT"),
        ("observations_count", "INTEGER DEFAULT 0"),
        ("ecology_uk", "TEXT"),
        ("ecology_en", "TEXT"),
        ("toxicity_uk", "TEXT"),
        ("toxicity_en", "TEXT"),
        ("use_uk", "TEXT"),
        ("use_en", "TEXT"),
        ("wiki_url_uk", "TEXT"),
        ("wiki_url_en", "TEXT"),
        ("enriched_at", "INTEGER DEFAULT NULL"),
    ]

    added = []
    for col_name, col_type in needed_cols:
        if col_name not in existing_cols:
            cur.execute(f"ALTER TABLE taxa ADD COLUMN {col_name} {col_type}")
            added.append(col_name)

    if added:
        conn.commit()
        print(f"[✓] Додано нові колонки в таблицю taxa: {', '.join(added)}")

    # Якщо колонка enriched_at містить NULL для записів, що вже збагачувалися сьогодні:
    cur.execute("UPDATE taxa SET enriched_at = updated_at WHERE enriched_at IS NULL AND updated_at >= 1790520000")
    conn.commit()


def cmd_stats(args):
    conn = get_db_connection(args.db)
    ensure_taxa_columns(conn)
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

    def count_col(col: str) -> int:
        cur.execute(f"SELECT COUNT(*) FROM taxa WHERE {col} IS NOT NULL AND length(trim({col})) > 0")
        return cur.fetchone()[0]

    stat_cols = [
        ("name_uk", "Назва українською"),
        ("name_en", "Назва англійською"),
        ("desc_uk", "Опис українською (Вікіпедія)"),
        ("desc_en", "Опис англійською (Вікіпедія)"),
        ("wiki_url_uk", "Посилання на Вікіпедію (UK)"),
        ("wiki_url_en", "Посилання на Вікіпедію (EN)"),
        ("phylum", "Відділ (Phylum)"),
        ("taxon_class", "Клас (Class)"),
        ("order_name", "Порядок (Order)"),
        ("family", "Родина (Family)"),
        ("ecology_uk", "Поширення/середовище (UK)"),
        ("ecology_en", "Поширення/середовище (EN)"),
        ("toxicity_uk", "Токсичність/двійники (UK)"),
        ("toxicity_en", "Токсичність/двійники (EN)"),
        ("use_uk", "Практичне використання (UK)"),
        ("use_en", "Практичне використання (EN)"),
        ("lookalikes_uk", "Двійники та небезпека"),
        ("props_cap", "Морфологічні властивості"),
        ("conservation_status", "Охоронний статус"),
    ]

    print("\n" + "=" * 65)
    print(f"   СТАТИСТИКА БАЗИ ДАНИХ: {os.path.basename(args.db)}")
    print("=" * 65)
    print(f" Файл:                 {args.db}")
    print(f" Розмір:               {db_size_mb:.2f} MB")
    print(f" Видів у базі:         {total_taxa}")
    print(f" Видів у classes.json: {len(classes_names)}")
    print(f" Всього фотографій:    {total_photos} ({with_photos}/{total_taxa} видів мають фото)")
    print(f" Відсутні у базі:      {len(missing_in_db)}")

    print("\n Заповненість полів опису та таксономії:")
    for col, label in stat_cols:
        cnt = count_col(col)
        pct = (cnt / total_taxa * 100) if total_taxa else 0
        print(f"  • {label:<32} {cnt:>5} ({pct:>5.1f}%)")

    print("\n Розподіл їстівності:")
    for ed, cnt in edib_stats:
        pct = (cnt / total_taxa * 100) if total_taxa else 0
        print(f"  • {str(ed):<16} {cnt:>5} ({pct:>5.1f}%)")

    print("\n Розподіл гіменофору:")
    for hm, cnt in hym_stats:
        pct = (cnt / total_taxa * 100) if total_taxa else 0
        print(f"  • {str(hm):<16} {cnt:>5} ({pct:>5.1f}%)")

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
    ensure_taxa_columns(conn)
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


def fetch_inat_data(species_name: str, inat_id: Optional[int] = None) -> Optional[Dict[str, Any]]:
    session = get_session()
    if inat_id and inat_id > 0:
        detail_url = f"https://api.inaturalist.org/v1/taxa/{inat_id}"
        try:
            d_res = session.get(detail_url, params={"all_names": "true"}, timeout=10)
            if d_res.status_code == 200 and d_res.json().get("results"):
                return d_res.json()["results"][0]
        except Exception:
            pass

    search_url = "https://api.inaturalist.org/v1/taxa"
    params = {"q": species_name, "taxon_id": 47170, "rank": "species", "per_page": 5}
    try:
        r = session.get(search_url, params=params, timeout=10)
        if r.status_code != 200 or not r.json().get("results"):
            params = {"q": species_name, "per_page": 5}
            r = session.get(search_url, params=params, timeout=10)
            if r.status_code != 200 or not r.json().get("results"):
                return None

        results = r.json()["results"]
        target = next((item for item in results if item.get("name", "").lower() == species_name.lower()), results[0])
        target_id = target.get("id")

        detail_url = f"https://api.inaturalist.org/v1/taxa/{target_id}"
        d_res = session.get(detail_url, params={"all_names": "true"}, timeout=10)
        if d_res.status_code == 200 and d_res.json().get("results"):
            return d_res.json()["results"][0]
        return target
    except Exception as e:
        return None


def fetch_inat_batch(inat_ids: List[int]) -> Dict[int, Dict[str, Any]]:
    if not inat_ids:
        return {}
    session = get_session()
    ids_str = ",".join(str(i) for i in inat_ids)
    url = f"https://api.inaturalist.org/v1/taxa/{ids_str}"
    try:
        r = session.get(url, params={"all_names": "true"}, timeout=12)
        if r.status_code == 200:
            res_dict = {}
            for item in r.json().get("results", []):
                item_id = item.get("id")
                if item_id:
                    res_dict[item_id] = extract_inat_details(item)
            return res_dict
    except Exception:
        pass
    return {}


def fetch_inat_by_name(species_name: str) -> Optional[Dict[str, Any]]:
    raw = fetch_inat_data(species_name)
    return extract_inat_details(raw) if raw else None


def extract_inat_details(inat: Dict[str, Any]) -> Dict[str, Any]:
    phylum = None
    taxon_class = None
    order_name = None
    family = None
    genus = None
    for anc in inat.get("ancestors", []):
        r = anc.get("rank")
        n = anc.get("name")
        if r == "phylum":
            phylum = n
        elif r == "class":
            taxon_class = n
        elif r == "order":
            order_name = n
        elif r == "family":
            family = n
        elif r == "genus":
            genus = n

    obs_count = inat.get("observations_count", 0)

    cons_status = None
    cs_list = inat.get("conservation_statuses", [])
    if cs_list:
        st = cs_list[0].get("status")
        auth = cs_list[0].get("authority")
        if st:
            cons_status = f"{st} ({auth})" if auth else st

    name_uk = None
    name_en = inat.get("preferred_common_name")
    for n in inat.get("names", []):
        loc = n.get("locale")
        val = n.get("name", "").strip()
        if not val:
            continue
        if loc == "uk" and not name_uk:
            name_uk = val
        elif loc == "en" and not name_en:
            name_en = val

    return {
        "inat_id": inat.get("id"),
        "phylum": clean_str(phylum),
        "taxon_class": clean_str(taxon_class),
        "order_name": clean_str(order_name),
        "family": clean_str(family),
        "genus": clean_str(genus),
        "observations_count": obs_count,
        "conservation_status": clean_str(cons_status),
        "name_uk": clean_str(name_uk),
        "name_en": clean_str(name_en),
        "wikipedia_summary": clean_str(inat.get("wikipedia_summary")),
        "wikipedia_url": clean_str(inat.get("wikipedia_url"))
    }


def format_subsections(text: str) -> str:
    if not text:
        return ""
    text = re.sub(r"\n===+\s*(.*?)\s*===+\n", r"\n\n• \1:\n", text)
    text = re.sub(r"\[\d+\]", "", text)
    text = re.sub(r"<[^>]+>", "", text)
    text = re.sub(r"[ \t]+", " ", text)
    text = re.sub(r"\n{3,}", "\n\n", text)
    return text.strip()


def parse_level2_sections(text: str, title: str) -> Dict[str, str]:
    if not text:
        return {}
    parts = re.split(r"\n==\s*([^=\n]+?)\s*==\n", text)
    lead = parts[0].strip()
    sections = {"lead": lead, "title": title}
    for i in range(1, len(parts), 2):
        sec_name = parts[i].strip().lower()
        sec_content = parts[i + 1].strip() if i + 1 < len(parts) else ""
        sections[sec_name] = sec_content
    return sections


def extract_morph_props(text: str) -> Dict[str, str]:
    props = {}
    if not text:
        return props
    m_cap = re.search(r"(?:Шапка|Шапинка)\s*[:—–-]?\s*([^.\n]+(?:\.[^.\n]+)?)", text, re.I)
    if m_cap and len(m_cap.group(1).strip()) > 5:
        props["props_cap"] = m_cap.group(1).strip()[:100]

    m_stem = re.search(r"(?:Ніжка)\s*[:—–-]?\s*([^.\n]+(?:\.[^.\n]+)?)", text, re.I)
    if m_stem and len(m_stem.group(1).strip()) > 5:
        props["props_stem"] = m_stem.group(1).strip()[:100]

    m_hym = re.search(r"(?:Пластинки|Гіменофор|Трубочки|Пори)\s*[:—–-]?\s*([^.\n]+(?:\.[^.\n]+)?)", text, re.I)
    if m_hym and len(m_hym.group(1).strip()) > 5:
        props["props_hymenium"] = m_hym.group(1).strip()[:100]

    m_flesh = re.search(r"(?:М['’]якуш|Мякуш)\s*[:—–-]?\s*([^.\n]+(?:\.[^.\n]+)?)", text, re.I)
    if m_flesh and len(m_flesh.group(1).strip()) > 5:
        props["props_flesh"] = m_flesh.group(1).strip()[:100]

    return props


def fetch_wiki_sections(title: str, lang: str = "uk") -> Dict[str, str]:
    if not title:
        return {}
    session = get_session()
    url = f"https://{lang}.wikipedia.org/w/api.php"
    params = {
        "action": "query",
        "prop": "extracts",
        "explaintext": "1",
        "redirects": "1",
        "titles": title,
        "format": "json"
    }
    try:
        r = session.get(url, params=params, timeout=8)
        if r.status_code != 200:
            return {}
        pages = r.json().get("query", {}).get("pages", {})
        for pid, p in pages.items():
            if pid == "-1":
                continue
            text = p.get("extract", "").strip()
            if not text:
                continue
            return parse_level2_sections(text, p.get("title", title))
    except Exception:
        pass
    return {}


def fetch_wiki_summary(title: str, lang: str = "uk") -> Tuple[Optional[str], Optional[str]]:
    if not title:
        return None, None
    session = get_session()
    encoded = urllib.parse.quote(title)
    url = f"https://{lang}.wikipedia.org/api/rest_v1/page/summary/{encoded}"
    try:
        r = session.get(url, timeout=8)
        if r.status_code == 200:
            data = r.json()
            extract = data.get("extract", "").strip()
            page_url = data.get("content_urls", {}).get("desktop", {}).get("page")
            if extract and not extract.startswith("Redirect"):
                return extract, page_url
    except Exception:
        pass
    return None, None


def enrich_taxon_wiki(sci_name: str, name_uk: Optional[str] = None, name_en: Optional[str] = None) -> Dict[str, Any]:
    session = get_session()
    result = {
        "desc_uk": None, "desc_en": None,
        "ecology_uk": None, "ecology_en": None,
        "toxicity_uk": None, "toxicity_en": None,
        "use_uk": None, "use_en": None,
        "wiki_url_uk": None, "wiki_url_en": None,
        "discovered_name_uk": None, "discovered_name_en": None,
        "props_cap": None, "props_hymenium": None, "props_stem": None, "props_flesh": None
    }

    syn = KNOWN_SYNONYMS.get(sci_name.lower().strip())

    # 1. English Wikipedia & Wikidata QID
    qid = None
    en_title = sci_name
    cand_en_titles = [sci_name]
    if syn:
        cand_en_titles.append(syn)

    for cand in cand_en_titles:
        try:
            r_en = session.get("https://en.wikipedia.org/w/api.php", params={
                "action": "query", "titles": cand, "redirects": 1, "prop": "pageprops|extracts",
                "ppprop": "wikibase_item", "explaintext": 1, "format": "json"
            }, timeout=8).json()
            pages_en = r_en.get("query", {}).get("pages", {})
            pid_en = list(pages_en.keys())[0] if pages_en else "-1"
            if pid_en != "-1":
                p_en = pages_en[pid_en]
                en_title = p_en.get("title", cand)
                qid = p_en.get("pageprops", {}).get("wikibase_item")
                text_en = p_en.get("extract", "")
                if text_en:
                    sections_en = parse_level2_sections(text_en, en_title)
                    lead_en = sections_en.get("lead", "").strip()
                    desc_sec_en = ""
                    for k, v in sections_en.items():
                        if any(t in k for t in ["description", "characteristics", "morphology", "identification"]):
                            if len(v) > len(desc_sec_en):
                                desc_sec_en = v
                        elif any(t in k for t in ["habitat", "distribution", "ecology", "occurrence"]):
                            if not result["ecology_en"] and len(v) > 20:
                                result["ecology_en"] = format_subsections(v)
                        elif any(t in k for t in ["toxicity", "poison", "lookalike", "similar", "danger"]):
                            if not result["toxicity_en"] and len(v) > 20:
                                result["toxicity_en"] = format_subsections(v)
                        elif any(t in k for t in ["edibility", "uses", "culinary", "medicinal", "consumption"]):
                            if not result["use_en"] and len(v) > 20:
                                result["use_en"] = format_subsections(v)

                    if desc_sec_en and len(desc_sec_en) > 30:
                        result["desc_en"] = f"{lead_en}\n\n{format_subsections(desc_sec_en)}".strip()
                    elif lead_en:
                        result["desc_en"] = lead_en
                    result["wiki_url_en"] = f"https://en.wikipedia.org/wiki/{urllib.parse.quote(en_title.replace(' ', '_'))}"
                    break
        except Exception:
            pass

    # Search fallback for EN if not found
    if not result["desc_en"]:
        try:
            r_sr = session.get("https://en.wikipedia.org/w/api.php", params={
                "action": "query", "list": "search", "srsearch": f'"{sci_name}"', "srlimit": 1, "format": "json"
            }, timeout=8).json()
            hits = r_sr.get("query", {}).get("search", [])
            if hits:
                cand_title = hits[0]["title"]
                r_chk = session.get("https://en.wikipedia.org/w/api.php", params={
                    "action": "query", "titles": cand_title, "redirects": 1, "prop": "pageprops|extracts",
                    "ppprop": "wikibase_item", "explaintext": 1, "format": "json"
                }, timeout=8).json()
                p_chk = list(r_chk.get("query", {}).get("pages", {}).values())[0]
                if p_chk.get("pageid"):
                    en_title = p_chk.get("title", cand_title)
                    qid = p_chk.get("pageprops", {}).get("wikibase_item") or qid
                    text_en = p_chk.get("extract", "")
                    if text_en:
                        sections_en = parse_level2_sections(text_en, en_title)
                        lead_en = sections_en.get("lead", "").strip()
                        desc_sec_en = ""
                        for k, v in sections_en.items():
                            if any(t in k for t in ["description", "characteristics", "morphology"]):
                                if len(v) > len(desc_sec_en): desc_sec_en = v
                            elif any(t in k for t in ["habitat", "distribution", "ecology"]):
                                if not result["ecology_en"] and len(v) > 20: result["ecology_en"] = format_subsections(v)
                            elif any(t in k for t in ["toxicity", "poison", "lookalike"]):
                                if not result["toxicity_en"] and len(v) > 20: result["toxicity_en"] = format_subsections(v)
                            elif any(t in k for t in ["edibility", "uses"]):
                                if not result["use_en"] and len(v) > 20: result["use_en"] = format_subsections(v)
                        if desc_sec_en and len(desc_sec_en) > 30:
                            result["desc_en"] = f"{lead_en}\n\n{format_subsections(desc_sec_en)}".strip()
                        elif lead_en:
                            result["desc_en"] = lead_en
                        result["wiki_url_en"] = f"https://en.wikipedia.org/wiki/{urllib.parse.quote(en_title.replace(' ', '_'))}"
        except Exception:
            pass

    # 2. Ukrainian Wikipedia via Wikidata / sitelinks / direct titles
    uk_title = None
    if qid:
        try:
            r_wd = session.get("https://www.wikidata.org/w/api.php", params={
                "action": "wbgetentities", "ids": qid, "props": "sitelinks|labels",
                "sitefilter": "ukwiki", "languages": "uk", "format": "json"
            }, timeout=8).json()
            ent = r_wd.get("entities", {}).get(qid, {})
            uk_title = ent.get("sitelinks", {}).get("ukwiki", {}).get("title")
            uk_lbl = ent.get("labels", {}).get("uk", {}).get("value")
            if uk_lbl and any('\u0400' <= c <= '\u04FF' for c in uk_lbl):
                result["discovered_name_uk"] = uk_lbl
        except Exception:
            pass

    cand_uk_titles = [t for t in [uk_title, name_uk, sci_name, syn] if t]
    sections_uk = {}
    for t in cand_uk_titles:
        try:
            r_uk = session.get("https://uk.wikipedia.org/w/api.php", params={
                "action": "query", "titles": t, "redirects": 1, "prop": "extracts",
                "explaintext": 1, "format": "json"
            }, timeout=8).json()
            pages_uk = r_uk.get("query", {}).get("pages", {})
            p_uk = list(pages_uk.values())[0] if pages_uk else {}
            if p_uk.get("pageid") and p_uk.get("extract"):
                sections_uk = parse_level2_sections(p_uk["extract"], p_uk.get("title", t))
                break
        except Exception:
            pass

    # Search fallback on Ukrainian Wikipedia
    if not sections_uk:
        try:
            r_sr_uk = session.get("https://uk.wikipedia.org/w/api.php", params={
                "action": "query", "list": "search", "srsearch": f'"{sci_name}"', "srlimit": 1, "format": "json"
            }, timeout=8).json()
            hits_uk = r_sr_uk.get("query", {}).get("search", [])
            if hits_uk:
                c_title = hits_uk[0]["title"]
                r_c = session.get("https://uk.wikipedia.org/w/api.php", params={
                    "action": "query", "titles": c_title, "redirects": 1, "prop": "extracts",
                    "explaintext": 1, "format": "json"
                }, timeout=8).json()
                p_c = list(r_c.get("query", {}).get("pages", {}).values())[0]
                ext = p_c.get("extract", "")
                if sci_name.lower() in ext.lower() or (syn and syn.lower() in ext.lower()):
                    sections_uk = parse_level2_sections(ext, p_c.get("title", c_title))
        except Exception:
            pass

    if sections_uk:
        lead_uk = sections_uk.get("lead", "").strip()
        desc_sec_uk = ""
        full_morph_text = ""
        for k, v in sections_uk.items():
            if any(term in k for term in ["опис", "будова", "біологічний опис", "зовнішній вигляд", "морфологія", "характеристика"]):
                if len(v) > len(desc_sec_uk):
                    desc_sec_uk = v
                    full_morph_text = v
            elif any(term in k for term in ["поширення", "середовище", "екологія", "місцезростання", "ареал", "біотоп", "загальні відомості", "місця існування"]):
                if not result["ecology_uk"] and len(v) > 20:
                    result["ecology_uk"] = format_subsections(v)
            elif any(term in k for term in ["токсичн", "отруйн", "двійник", "небезпек", "отруєння", "схожі види"]):
                if not result["toxicity_uk"] and len(v) > 20:
                    result["toxicity_uk"] = format_subsections(v)
            elif any(term in k for term in ["практичне", "використання", "кулінар", "їстівн", "застосування", "значення", "споживання"]):
                if not result["use_uk"] and len(v) > 20:
                    result["use_uk"] = format_subsections(v)

        if desc_sec_uk and len(desc_sec_uk) > 30:
            result["desc_uk"] = f"{lead_uk}\n\n{format_subsections(desc_sec_uk)}".strip()
        elif lead_uk:
            result["desc_uk"] = lead_uk

        real_title = sections_uk.get("title", "")
        result["wiki_url_uk"] = f"https://uk.wikipedia.org/wiki/{urllib.parse.quote(real_title.replace(' ', '_'))}"
        if any('\u0400' <= c <= '\u04FF' for c in real_title) and not any(tag in real_title for tag in ["Список", "Категорія", "("]):
            result["discovered_name_uk"] = result["discovered_name_uk"] or real_title

        props = extract_morph_props(full_morph_text or lead_uk)
        result.update(props)

    return result


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


def process_wiki_for_taxon(row: Tuple[Any, ...]) -> Dict[str, Any]:
    db_id = row[0]
    inat_id = row[1]
    sci_name = row[2]
    cur_name_uk = row[3]
    cur_name_en = row[4]
    cur_desc_uk = row[5]
    cur_desc_en = row[6]
    cur_eco_uk = row[8] if len(row) > 8 else None
    cur_eco_en = row[9] if len(row) > 9 else None
    cur_tox_uk = row[10] if len(row) > 10 else None
    cur_tox_en = row[11] if len(row) > 11 else None
    cur_use_uk = row[12] if len(row) > 12 else None
    cur_use_en = row[13] if len(row) > 13 else None
    cur_url_uk = row[14] if len(row) > 14 else None
    cur_url_en = row[15] if len(row) > 15 else None

    data = enrich_taxon_wiki(sci_name, cur_name_uk, cur_name_en)

    desc_uk = data["desc_uk"] or cur_desc_uk
    desc_en = data["desc_en"] or cur_desc_en
    ecology_uk = data["ecology_uk"] or cur_eco_uk
    ecology_en = data["ecology_en"] or cur_eco_en
    toxicity_uk = data["toxicity_uk"] or cur_tox_uk
    toxicity_en = data["toxicity_en"] or cur_tox_en
    use_uk = data["use_uk"] or cur_use_uk
    use_en = data["use_en"] or cur_use_en
    wiki_url_uk = data["wiki_url_uk"] or cur_url_uk
    wiki_url_en = data["wiki_url_en"] or cur_url_en
    disc_name_uk = data["discovered_name_uk"]

    curated = CURATED_KNOWLEDGE.get(sci_name.lower().strip(), {})

    return {
        "id": db_id,
        "sci_name": sci_name,
        "desc_uk": clean_str(desc_uk),
        "desc_en": clean_str(desc_en),
        "ecology_uk": clean_str(ecology_uk),
        "ecology_en": clean_str(ecology_en),
        "toxicity_uk": clean_str(toxicity_uk),
        "toxicity_en": clean_str(toxicity_en),
        "use_uk": clean_str(use_uk),
        "use_en": clean_str(use_en),
        "wiki_url_uk": clean_str(wiki_url_uk),
        "wiki_url_en": clean_str(wiki_url_en),
        "discovered_name_uk": clean_str(disc_name_uk),
        "discovered_name_en": clean_str(data["discovered_name_en"]),
        "lookalikes_uk": clean_str(curated.get("lookalikes_uk")),
        "lookalikes_en": clean_str(curated.get("lookalikes_en")),
        "props_cap": clean_str(curated.get("props_cap") or data["props_cap"]),
        "props_hymenium": clean_str(curated.get("props_hymenium") or data["props_hymenium"]),
        "props_stem": clean_str(curated.get("props_stem") or data["props_stem"]),
        "props_flesh": clean_str(curated.get("props_flesh") or data["props_flesh"]),
        "props_season": clean_str(curated.get("props_season")),
        "props_habitat": clean_str(curated.get("props_habitat")),
    }


def cmd_update_fields(args):
    """
    Збагачення наявних записів у базі новими полями:
    - ЕТАП 1: Миттєве пакетне завантаження таксономії iNaturalist (до 30 видів на 1 HTTP-запит).
    - ЕТАП 2: Багатопотокове паралельне завантаження розділів Вікіпедії (екіпаж з N воркерів).
    """
    conn = get_db_connection(args.db)
    ensure_taxa_columns(conn)
    cur = conn.cursor()

    popular = getattr(args, "popular", 0)
    missing = getattr(args, "missing", False)
    missing_uk = getattr(args, "missing_uk", False)
    missing_en = getattr(args, "missing_en", False)
    species = getattr(args, "species", None)
    force = getattr(args, "force", False)
    limit = getattr(args, "limit", 0)
    workers = getattr(args, "workers", 10) or 10

    query_conditions = []
    query_params = []
    if species:
        query_conditions.append("LOWER(scientific_name) = LOWER(?)")
        query_params.append(species.strip())
    elif missing_uk:
        query_conditions.append("(desc_uk IS NULL OR length(trim(desc_uk)) = 0)")
    elif missing_en:
        query_conditions.append("(desc_en IS NULL OR length(trim(desc_en)) = 0)")
    elif missing:
        query_conditions.append("(desc_uk IS NULL OR length(trim(desc_uk)) = 0 OR desc_en IS NULL OR length(trim(desc_en)) = 0)")
    elif not force:
        # Стандартний режим: оновлюємо тільки записи, які ще не збагачувалися новим детальним парсером
        query_conditions.append("(enriched_at IS NULL OR enriched_at < ?)")
        query_params.append(NEW_ENRICHMENT_EPOCH)

    where_clause = f"WHERE {' AND '.join(query_conditions)}" if query_conditions else ""
    order_clause = "ORDER BY observations_count DESC, id ASC"

    cur.execute(f"SELECT id, inat_id, scientific_name, name_uk, name_en, desc_uk, desc_en, phylum FROM taxa {where_clause} {order_clause}", query_params)
    rows = cur.fetchall()

    if popular and popular > 0:
        rows = rows[:popular]
    elif limit and limit > 0:
        rows = rows[:limit]

    total = len(rows)
    if total == 0:
        print("\n[✓] Усі обрані таксони в базі вже збагачені та оновлені! Немає нових записів.")
        print("    (Якщо потрібно оновити повторно, запустіть з прапорцем --force або --popular N)\n")
        conn.close()
        return

    print(f"\n[*] Знайдено таксонів для оновлення: {total}")

    # -------------------------------------------------------------------------
    # ЕТАП 1: Швидке пакетне оновлення iNaturalist (по 30 за раз)
    # -------------------------------------------------------------------------
    inat_to_fetch = [r for r in rows if r[1] is not None and r[1] > 0 and (r[7] is None or force)]
    no_inat_rows = [r for r in rows if (r[1] is None or r[1] <= 0) and (r[7] is None or force)]

    inat_results_map: Dict[int, Dict[str, Any]] = {}

    if inat_to_fetch or no_inat_rows:
        chunk_size = 30
        chunks = [inat_to_fetch[i:i + chunk_size] for i in range(0, len(inat_to_fetch), chunk_size)]
        print(f"\n[1/2] Пакетна синхронізація таксономії iNaturalist ({len(chunks)} пакетів по {chunk_size} видів)...")

        def fetch_chunk(chunk):
            ids = [r[1] for r in chunk]
            return fetch_inat_batch(ids)

        if chunks:
            with concurrent.futures.ThreadPoolExecutor(max_workers=min(workers, 4)) as executor:
                futures = [executor.submit(fetch_chunk, ch) for ch in chunks]
                for f in concurrent.futures.as_completed(futures):
                    res = f.result()
                    inat_results_map.update(res)

        if no_inat_rows:
            print(f"  [*] Пошук {len(no_inat_rows)} видів без inat_id за латинською назвою...")
            for r in no_inat_rows:
                s_name = r[2]
                info = fetch_inat_by_name(s_name)
                if info:
                    inat_results_map[r[0]] = info

        # Запис iNaturalist у SQLite одним транзакційним блоком
        cur.execute("BEGIN TRANSACTION")
        updated_inat = 0
        for r in rows:
            db_id, inat_id, s_name = r[0], r[1], r[2]
            info = inat_results_map.get(inat_id) if inat_id else inat_results_map.get(db_id)
            if not info:
                continue
            cur.execute("""
                UPDATE taxa
                SET inat_id = COALESCE(?, inat_id),
                    phylum = COALESCE(?, phylum),
                    taxon_class = COALESCE(?, taxon_class),
                    order_name = COALESCE(?, order_name),
                    family = COALESCE(?, family),
                    genus = COALESCE(?, genus),
                    observations_count = CASE WHEN ? > 0 THEN ? ELSE observations_count END,
                    conservation_status = COALESCE(?, conservation_status),
                    name_uk = COALESCE(name_uk, ?),
                    name_en = COALESCE(name_en, ?),
                    updated_at = ?
                WHERE id = ?
            """, (
                info.get("inat_id") or inat_id,
                info.get("phylum"),
                info.get("taxon_class"),
                info.get("order_name"),
                info.get("family"),
                info.get("genus"),
                info.get("observations_count", 0),
                info.get("observations_count", 0),
                info.get("conservation_status"),
                info.get("name_uk"),
                info.get("name_en"),
                int(time.time()),
                db_id
            ))
            updated_inat += 1

        conn.commit()
        print(f"  [✓] iNaturalist: таксономію оновлено для {updated_inat} видів.")
    else:
        print("\n[1/2] Таксономію iNaturalist вже заповнено для всіх обраних видів.")

    # -------------------------------------------------------------------------
    # ЕТАП 2: Багатопотокове збагачення розділів Вікіпедії
    # -------------------------------------------------------------------------
    print(f"\n[2/2] Паралельне детальне завантаження описів Вікіпедії (потоків: {workers})...")
    cur.execute(f"""
        SELECT id, inat_id, scientific_name, name_uk, name_en, desc_uk, desc_en, phylum,
               ecology_uk, ecology_en, toxicity_uk, toxicity_en, use_uk, use_en, wiki_url_uk, wiki_url_en
        FROM taxa {where_clause} {order_clause}
    """, query_params)
    refreshed_rows = cur.fetchall()
    if popular and popular > 0:
        refreshed_rows = refreshed_rows[:popular]
    elif limit and limit > 0:
        refreshed_rows = refreshed_rows[:limit]

    start_time = time.time()
    completed_count = 0
    total_wiki = len(refreshed_rows)

    cur.execute("BEGIN TRANSACTION")

    with concurrent.futures.ThreadPoolExecutor(max_workers=workers) as executor:
        futures = {executor.submit(process_wiki_for_taxon, r): r[2] for r in refreshed_rows}

        for future in concurrent.futures.as_completed(futures):
            item = future.result()
            completed_count += 1

            cur.execute("""
                UPDATE taxa
                SET desc_uk = CASE WHEN ? IS NOT NULL AND length(?) > length(COALESCE(desc_uk, '')) THEN ? ELSE COALESCE(desc_uk, ?) END,
                    desc_en = CASE WHEN ? IS NOT NULL AND length(?) > length(COALESCE(desc_en, '')) THEN ? ELSE COALESCE(desc_en, ?) END,
                    ecology_uk = COALESCE(?, ecology_uk),
                    ecology_en = COALESCE(?, ecology_en),
                    toxicity_uk = COALESCE(?, toxicity_uk),
                    toxicity_en = COALESCE(?, toxicity_en),
                    use_uk = COALESCE(?, use_uk),
                    use_en = COALESCE(?, use_en),
                    wiki_url_uk = COALESCE(?, wiki_url_uk),
                    wiki_url_en = COALESCE(?, wiki_url_en),
                    name_uk = COALESCE(name_uk, ?),
                    name_en = COALESCE(name_en, ?),
                    lookalikes_uk = COALESCE(?, lookalikes_uk),
                    lookalikes_en = COALESCE(?, lookalikes_en),
                    props_cap = COALESCE(?, props_cap),
                    props_hymenium = COALESCE(?, props_hymenium),
                    props_stem = COALESCE(?, props_stem),
                    props_flesh = COALESCE(?, props_flesh),
                    props_season = COALESCE(?, props_season),
                    props_habitat = COALESCE(?, props_habitat),
                    enriched_at = ?,
                    updated_at = ?
                WHERE id = ?
            """, (
                item["desc_uk"], item["desc_uk"], item["desc_uk"], item["desc_uk"],
                item["desc_en"], item["desc_en"], item["desc_en"], item["desc_en"],
                item["ecology_uk"], item["ecology_en"],
                item["toxicity_uk"], item["toxicity_en"],
                item["use_uk"], item["use_en"],
                item["wiki_url_uk"], item["wiki_url_en"],
                item["discovered_name_uk"], item["discovered_name_en"],
                item["lookalikes_uk"], item["lookalikes_en"],
                item["props_cap"], item["props_hymenium"],
                item["props_stem"], item["props_flesh"],
                item["props_season"], item["props_habitat"],
                int(time.time()), int(time.time()), item["id"]
            ))

            if completed_count % 25 == 0 or completed_count == total_wiki:
                conn.commit()
                cur.execute("BEGIN TRANSACTION")
                elapsed = time.time() - start_time
                rate = completed_count / elapsed if elapsed > 0 else 0
                remaining = (total_wiki - completed_count) / rate if rate > 0 else 0
                rem_m, rem_s = divmod(int(remaining), 60)
                pct = (completed_count / total_wiki * 100) if total_wiki else 100
                print(f"  [{completed_count:>4}/{total_wiki}] ({pct:>5.1f}%) | {rate:>4.1f} вид/с | Залишилось: ~{rem_m:02d}хв {rem_s:02d}с | {item['sci_name']}", flush=True)

    conn.commit()
    total_time = time.time() - start_time
    rate = completed_count / total_time if total_time > 0 else 0
    print(f"\n[✓] Завершено! Оновлено {completed_count} видів за {total_time:.1f} сек ({rate:.1f} вид/сек).", flush=True)
    conn.close()


def cmd_fetch_missing(args):
    classes = load_classes_json(args.classes)
    conn = get_db_connection(args.db)
    ensure_taxa_columns(conn)
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
        inat_info = extract_inat_details(inat) if inat else {}

        inat_id = inat_info.get("inat_id")
        name_uk = inat_info.get("name_uk")
        name_en = inat_info.get("name_en")
        phylum = inat_info.get("phylum")
        taxon_class = inat_info.get("taxon_class")
        family = inat_info.get("family")
        order_name = inat_info.get("order_name")
        genus = inat_info.get("genus") or (sp.split()[0] if " " in sp else sp)
        obs_count = inat_info.get("observations_count") or 0
        cons_status = inat_info.get("conservation_status")

        wiki_data = enrich_taxon_wiki(sp, name_uk, name_en)
        desc_uk = wiki_data["desc_uk"]
        desc_en = wiki_data["desc_en"] or inat_info.get("wikipedia_summary")
        ecology_uk = wiki_data["ecology_uk"]
        ecology_en = wiki_data["ecology_en"]
        toxicity_uk = wiki_data["toxicity_uk"]
        toxicity_en = wiki_data["toxicity_en"]
        use_uk = wiki_data["use_uk"]
        use_en = wiki_data["use_en"]
        wiki_url_uk = wiki_data["wiki_url_uk"]
        wiki_url_en = wiki_data["wiki_url_en"]
        name_uk = name_uk or wiki_data["discovered_name_uk"]
        name_en = name_en or wiki_data["discovered_name_en"]

        curated = CURATED_KNOWLEDGE.get(sp.lower().strip(), {})

        cur.execute("""
            INSERT INTO taxa (
                inat_id, scientific_name, name_uk, name_en, family, order_name, genus,
                edibility, hymenium, desc_uk, desc_en, wiki_url_uk, wiki_url_en,
                phylum, taxon_class, conservation_status, observations_count,
                ecology_uk, ecology_en, toxicity_uk, toxicity_en, use_uk, use_en,
                lookalikes_uk, lookalikes_en,
                props_cap, props_hymenium, props_stem, props_flesh, props_season, props_habitat,
                photos_count, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """, (
            inat_id, sp, name_uk, name_en, family, order_name, genus,
            edibility, hymenium, desc_uk, desc_en, wiki_url_uk, wiki_url_en,
            phylum, taxon_class, cons_status, obs_count,
            ecology_uk, ecology_en, toxicity_uk, toxicity_en, use_uk, use_en,
            curated.get("lookalikes_uk"), curated.get("lookalikes_en"),
            curated.get("props_cap") or wiki_data["props_cap"],
            curated.get("props_hymenium") or wiki_data["props_hymenium"],
            curated.get("props_stem") or wiki_data["props_stem"],
            curated.get("props_flesh") or wiki_data["props_flesh"],
            curated.get("props_season"), curated.get("props_habitat"),
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

        cfg = re.sub(r"const val DB_FULL_SIZE = \d+L", f"const val DB_FULL_SIZE = {db_size}L", cfg)
        for v in volumes:
            sz = os.path.getsize(os.path.join(downloads_dir, v))
            cfg = re.sub(rf'DataFileItem\("{re.escape(v)}", \d+L\)', f'DataFileItem("{v}", {sz}L)', cfg)

        with open(CONFIG_KT_PATH, "w", encoding="utf-8") as f:
            f.write(cfg)
        print(f"[✓] MushroomDataConfig.kt оновлено з актуальними розмірами всіх {len(volumes)} томів (DB_FULL_SIZE={db_size}L).")


def main():
    if len(sys.argv) == 1:
        print("\n" + "=" * 60)
        print("   MUSHROOM DATABASE MANAGER")
        print("=" * 60)
        print("\n 1. Переглянути статистику бази даних (stats)")
        print(" 2. Оновити описи, таксономію та нові поля (update_fields)")
        print(" 3. Дозавантажити відсутні гриби з classes.json (fetch_missing)")
        print(" 4. Синхронізувати їстівність та гіменофор (sync)")
        print(" 5. Очистити дублікати фотографій (dedup)")
        print(" 6. Запакувати базу у багатотомний архів (repack)")
        print(" 7. Повний цикл оновлення (all)")
        print(" 8. Вихід\n")
        try:
            choice = input(" Оберіть дію [1-8]: ").strip()
        except (KeyboardInterrupt, EOFError):
            sys.exit(0)

        mapping = {
            "1": "stats",
            "2": "update_fields",
            "3": "fetch_missing",
            "4": "sync",
            "5": "dedup",
            "6": "repack",
            "7": "all",
        }
        cmd = mapping.get(choice)
        if not cmd:
            print("[*] Вихід.")
            sys.exit(0)
        sys.argv.append(cmd)

    parser = argparse.ArgumentParser(description="Mushroom Database & Archive Manager")
    parser.add_argument("--db", default=DB_PATH, help="Path to mushrooms.db")
    parser.add_argument("--classes", default=CLASSES_JSON_PATH, help="Path to classes.json")

    sub = parser.add_subparsers(dest="command", required=True)
    sub.add_parser("stats", help="Аналітика бази даних, наявності полів та звірка з classes.json")
    sub.add_parser("sync", help="Синхронізація edibility та hymenium з classes.json")
    sub.add_parser("dedup", help="Очищення дублікатів фото та нормалізація індексів")

    p_enrich = sub.add_parser("update_fields", help="Збагачення таксонів детальними описами та розділами Вікіпедії/iNaturalist")
    p_enrich.add_argument("--limit", type=int, default=0, help="Кількість грибів для оновлення (0 = всі необхідні)")
    p_enrich.add_argument("--popular", type=int, default=0, help="Оновити топ N найпопулярніших грибів за спостереженнями")
    p_enrich.add_argument("--missing", action="store_true", help="Оновити лише гриби, де відсутній опис (uk або en)")
    p_enrich.add_argument("--missing-uk", action="store_true", help="Оновити лише гриби, де відсутній опис українською")
    p_enrich.add_argument("--missing-en", action="store_true", help="Оновити лише гриби, де відсутній опис англійською")
    p_enrich.add_argument("--species", type=str, default="", help="Оновити конкретний вид за латинською назвою")
    p_enrich.add_argument("--force", action="store_true", help="Оновлювати навіть якщо поля вже заповнені")
    p_enrich.add_argument("--workers", type=int, default=10, help="Кількість паралельних потоків (за замовчуванням 10)")
    p_enrich.add_argument("--delay", type=float, default=0.0, help="Пауза між запитами до API в секундах (за замовчуванням 0)")

    sub.add_parser("fetch_missing", help="Завантаження відсутніх видів з iNaturalist/Wikipedia")
    sub.add_parser("repack", help="VACUUM + створення багатотомного архіву + оновлення конфігу")
    p_all = sub.add_parser("all", help="Повний цикл: sync + update_fields + fetch_missing + dedup + repack")
    p_all.add_argument("--workers", type=int, default=8, help="Кількість паралельних потоків (за замовчуванням 8)")

    args = parser.parse_args()

    if args.command == "stats":
        cmd_stats(args)
    elif args.command == "sync":
        cmd_sync(args)
    elif args.command == "dedup":
        cmd_dedup(args)
    elif args.command == "update_fields":
        cmd_update_fields(args)
    elif args.command == "fetch_missing":
        cmd_fetch_missing(args)
    elif args.command == "repack":
        cmd_repack(args)
    elif args.command == "all":
        cmd_sync(args)
        cmd_update_fields(args)
        cmd_fetch_missing(args)
        cmd_dedup(args)
        cmd_repack(args)
        cmd_stats(args)


if __name__ == "__main__":
    main()
