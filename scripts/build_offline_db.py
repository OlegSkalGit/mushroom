#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Mushroom Offline Database Generator
Builds a compressed SQLite database containing all mushroom species from
classes.json and labels.txt, enriched with:
- Taxonomy, localized names (UA/EN) and all available photos from iNaturalist
- Detailed species summaries from Ukrainian and English Wikipedia
- Edibility, hymenophore classification, traits, and lookalike safety alerts
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

# Setup standard output encoding for Windows consoles
if sys.platform == "win32":
    try:
        sys.stdout.reconfigure(encoding="utf-8")
        sys.stderr.reconfigure(encoding="utf-8")
    except Exception:
        pass

# Optional tqdm progress bar
try:
    from tqdm import tqdm
except ImportError:
    def tqdm(iterable, **kwargs):
        return iterable


# =====================================================================
# Curated MycoKnowledge Dictionary & Classification Rules
# =====================================================================

TUBES_GENERA = {
    "boletus", "leccinum", "suillus", "imleria", "xerocomus", "neoboletus",
    "rubroboletus", "tylopilus", "fomes", "trametes", "phellinus", "ganoderma",
    "laetiporus", "polyporus", "fomitopsis", "daedaleopsis", "trichaptum",
    "caloboletus", "buthyriboletus", "hemileccinum", "pseudoboletus", "gyroporus"
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
    "suillus granulatus": {"edibility": "edible", "hymenium": "tubes"},
    "leccinum scabrum": {"edibility": "edible", "hymenium": "tubes"},
    "imleria badia": {"edibility": "edible", "hymenium": "tubes"},
    "armillaria mellea": {"edibility": "edible", "hymenium": "gills"},
    "macrolepiota procera": {"edibility": "edible", "hymenium": "gills"},
    "agaricus arvensis": {"edibility": "edible", "hymenium": "gills"},
    "agaricus campestris": {"edibility": "edible", "hymenium": "gills"},
    "coprinus comatus": {"edibility": "edible", "hymenium": "gills"},
    "pleurotus ostreatus": {"edibility": "edible", "hymenium": "gills"},
    "laetiporus sulphureus": {"edibility": "cond-edible", "hymenium": "tubes"},
    "morchella esculenta": {"edibility": "cond-edible", "hymenium": "tubes"},
    "lactarius deliciosus": {"edibility": "edible", "hymenium": "gills"},
    "lactarius torminosus": {"edibility": "cond-edible", "hymenium": "gills"},
    "russula virescens": {"edibility": "edible", "hymenium": "gills"},
    "russula aeruginea": {"edibility": "edible", "hymenium": "gills"},
    "fomes fomentarius": {"edibility": "inedible", "hymenium": "tubes"},
    "trametes versicolor": {"edibility": "inedible", "hymenium": "tubes"},
    "schizophyllum commune": {"edibility": "inedible", "hymenium": "gills"}
}


# =====================================================================
# Database Setup
# =====================================================================

def init_db(db_path: str) -> sqlite3.Connection:
    conn = sqlite3.connect(db_path)
    conn.execute("PRAGMA journal_mode = WAL;")
    conn.execute("PRAGMA synchronous = NORMAL;")
    conn.execute("PRAGMA foreign_keys = ON;")

    conn.execute("""
        CREATE TABLE IF NOT EXISTS taxa (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            inat_id INTEGER UNIQUE,
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
    conn.execute("CREATE INDEX IF NOT EXISTS idx_taxa_name_uk ON taxa(name_uk);")
    conn.execute("CREATE INDEX IF NOT EXISTS idx_taxa_name_en ON taxa(name_en);")
    conn.execute("CREATE INDEX IF NOT EXISTS idx_taxa_edibility ON taxa(edibility);")
    conn.execute("CREATE INDEX IF NOT EXISTS idx_taxa_hymenium ON taxa(hymenium);")
    conn.execute("CREATE INDEX IF NOT EXISTS idx_photos_taxon_id ON photos(taxon_id);")

    conn.commit()
    return conn


# =====================================================================
# Input Parsers
# =====================================================================

def load_species_list(classes_path: str, labels_path: str) -> Tuple[List[str], Dict[str, str]]:
    labels_edibility = {}
    species_set = set()

    # Priority mapping for labels edibility
    def priority(e: str) -> int:
        return {"deadly": 5, "toxic": 4, "cond-edible": 3, "edible": 2}.get(e, 1)

    # 1. Parse labels.txt
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

    # 2. Parse classes.json
    if os.path.exists(classes_path):
        with open(classes_path, "r", encoding="utf-8") as f:
            classes_data = json.load(f)
            for item in classes_data:
                sp = item.get("species", "").strip()
                clean_name = " ".join(sp.split())
                if clean_name:
                    species_set.add(clean_name)

    # Sort species alphabetically
    sorted_species = sorted(list(species_set), key=lambda s: s.lower())
    return sorted_species, labels_edibility


# =====================================================================
# Network Helpers
# =====================================================================

SESSION = requests.Session()
SESSION.headers.update({
    "User-Agent": "MushroomOfflineDBBuilder/1.0 (Android; Contact: olegskal)"
})

def http_get(url: str, params: Optional[dict] = None, timeout: int = 12) -> Optional[requests.Response]:
    for attempt in range(3):
        try:
            r = SESSION.get(url, params=params, timeout=timeout)
            if r.status_code == 200:
                return r
            elif r.status_code == 429:
                # Rate limited -> back off
                time.sleep(3.0 * (attempt + 1))
            elif r.status_code == 404:
                return None
            else:
                time.sleep(1.0)
        except Exception:
            time.sleep(1.5 * (attempt + 1))
    return None


def fetch_inat_taxon_details(scientific_name: str) -> Optional[dict]:
    # 1. Search for taxon by scientific name
    search_url = "https://api.inaturalist.org/v1/taxa"
    params = {
        "q": scientific_name,
        "taxon_id": 47170,  # Kingdom Fungi
        "rank": "species",
        "per_page": 5
    }
    res = http_get(search_url, params=params)
    if not res:
        return None

    data = res.json().get("results", [])
    if not data:
        # Fallback: search without rank or taxon_id constraints
        res = http_get(search_url, params={"q": scientific_name, "per_page": 5})
        if not res:
            return None
        data = res.json().get("results", [])

    if not data:
        return None

    # Find exact match
    target_id = None
    target_obj = None
    for item in data:
        name = item.get("name", "").strip().lower()
        if name == scientific_name.lower():
            target_id = item.get("id")
            target_obj = item
            break

    if target_id is None:
        target_id = data[0].get("id")
        target_obj = data[0]

    if target_id is None:
        return None

    # 2. Get full detailed taxon object with all names and taxon_photos
    detail_url = f"https://api.inaturalist.org/v1/taxa/{target_id}"
    d_res = http_get(detail_url, params={"all_names": "true"})
    if d_res:
        d_results = d_res.json().get("results", [])
        if d_results:
            return d_results[0]

    return target_obj


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


def optimize_image_to_webp(image_bytes: bytes, max_size: int = 720, quality: int = 80) -> Optional[Tuple[bytes, int, int]]:
    try:
        img = Image.open(io.BytesIO(image_bytes))
        if img.mode not in ("RGB", "RGBA"):
            img = img.convert("RGB")
        elif img.mode == "RGBA":
            # Flatten RGBA onto dark/neutral background
            bg = Image.new("RGB", img.size, (20, 20, 20))
            bg.paste(img, mask=img.split()[3])
            img = bg

        img.thumbnail((max_size, max_size), Image.Resampling.LANCZOS)
        out_buf = io.BytesIO()
        img.save(out_buf, format="WEBP", quality=quality, method=4)
        webp_bytes = out_buf.getvalue()
        return webp_bytes, img.width, img.height
    except Exception:
        return None


# =====================================================================
# Main Pipeline
# =====================================================================

def process_species(
    conn: sqlite3.Connection,
    species_name: str,
    labels_edibility: Dict[str, str],
    delay: float = 1.0
) -> bool:
    cur = conn.cursor()
    low_name = species_name.lower().strip()

    # 1. Check if already processed
    cur.execute("SELECT id, updated_at FROM taxa WHERE LOWER(scientific_name) = ?", (low_name,))
    row = cur.fetchone()
    if row and row[1] is not None:
        return True  # Already completed

    # 2. Resolve metadata from Curated Knowledge & labels.txt
    genus = species_name.split()[0].lower() if species_name.split() else ""
    curated = CURATED_KNOWLEDGE.get(low_name, {})

    edibility = curated.get("edibility")
    if not edibility:
        edibility = labels_edibility.get(low_name)
    if not edibility:
        # Check binomial
        parts = low_name.split()
        if len(parts) >= 2:
            edibility = labels_edibility.get(f"{parts[0]} {parts[1]}")
    if not edibility:
        edibility = "unknown"

    hymenium = curated.get("hymenium")
    if not hymenium:
        hymenium = "tubes" if genus in TUBES_GENERA else "gills"

    lookalikes_uk = curated.get("lookalikes_uk")
    lookalikes_en = curated.get("lookalikes_en")
    props_cap = curated.get("props_cap")
    props_hymenium = curated.get("props_hymenium")
    props_stem = curated.get("props_stem")
    props_flesh = curated.get("props_flesh")
    props_season = curated.get("props_season")
    props_habitat = curated.get("props_habitat")

    # 3. Fetch iNaturalist Taxon
    inat_taxon = fetch_inat_taxon_details(species_name)
    time.sleep(delay)

    inat_id = None
    name_uk = None
    name_en = None
    family = None
    order_name = None
    wiki_summary_inat = None
    wiki_url_inat = None
    photos_to_download = []

    if inat_taxon:
        inat_id = inat_taxon.get("id")
        wiki_summary_inat = inat_taxon.get("wikipedia_summary")
        wiki_url_inat = inat_taxon.get("wikipedia_url")

        # Ancestry / taxonomy
        for ancestor in inat_taxon.get("ancestors", []):
            rank = ancestor.get("rank")
            if rank == "family":
                family = ancestor.get("name")
            elif rank == "order":
                order_name = ancestor.get("name")

        # Localized names
        all_names = inat_taxon.get("names", [])
        for n_item in all_names:
            loc = n_item.get("locale")
            val = n_item.get("name", "").strip()
            if not val:
                continue
            if loc == "uk" and not name_uk:
                name_uk = val
            elif loc == "en" and not name_en:
                name_en = val

        # Fallback preferred common name
        pref_common = inat_taxon.get("preferred_common_name")
        if pref_common and not name_en:
            name_en = pref_common

        # Photos list
        taxon_photos = inat_taxon.get("taxon_photos", [])
        if not taxon_photos and inat_taxon.get("default_photo"):
            taxon_photos = [{"photo": inat_taxon.get("default_photo")}]

        for idx, tp in enumerate(taxon_photos):
            photo = tp.get("photo", {})
            p_url = photo.get("medium_url") or photo.get("url") or photo.get("original_url")
            if p_url:
                photos_to_download.append({
                    "index": idx,
                    "url": p_url,
                    "attribution": photo.get("attribution") or photo.get("attribution_name") or "",
                    "license_code": photo.get("license_code") or ""
                })

    # 4. Fetch Wikipedia Descriptions (UA & EN)
    desc_uk, wiki_url_uk = None, None
    if name_uk:
        desc_uk, wiki_url_uk = fetch_wikipedia_summary(name_uk, lang="uk")
    if not desc_uk:
        desc_uk, wiki_url_uk = fetch_wikipedia_summary(species_name, lang="uk")

    desc_en, wiki_url_en = fetch_wikipedia_summary(species_name, lang="en")
    if not desc_en and wiki_summary_inat:
        desc_en = wiki_summary_inat
    if not wiki_url_en and wiki_url_inat:
        wiki_url_en = wiki_url_inat

    # 5. Insert / Update Taxon record
    now_ts = int(time.time())
    cur.execute("""
        INSERT INTO taxa (
            inat_id, scientific_name, name_uk, name_en, family, order_name, genus,
            edibility, hymenium, desc_uk, desc_en, lookalikes_uk, lookalikes_en,
            props_cap, props_hymenium, props_stem, props_flesh, props_season, props_habitat,
            wiki_url_uk, wiki_url_en, photos_count, updated_at
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        ON CONFLICT(scientific_name) DO UPDATE SET
            inat_id = excluded.inat_id,
            name_uk = COALESCE(excluded.name_uk, taxa.name_uk),
            name_en = COALESCE(excluded.name_en, taxa.name_en),
            family = COALESCE(excluded.family, taxa.family),
            order_name = COALESCE(excluded.order_name, taxa.order_name),
            genus = COALESCE(excluded.genus, taxa.genus),
            edibility = excluded.edibility,
            hymenium = excluded.hymenium,
            desc_uk = COALESCE(excluded.desc_uk, taxa.desc_uk),
            desc_en = COALESCE(excluded.desc_en, taxa.desc_en),
            lookalikes_uk = COALESCE(excluded.lookalikes_uk, taxa.lookalikes_uk),
            lookalikes_en = COALESCE(excluded.lookalikes_en, taxa.lookalikes_en),
            props_cap = COALESCE(excluded.props_cap, taxa.props_cap),
            props_hymenium = COALESCE(excluded.props_hymenium, taxa.props_hymenium),
            props_stem = COALESCE(excluded.props_stem, taxa.props_stem),
            props_flesh = COALESCE(excluded.props_flesh, taxa.props_flesh),
            props_season = COALESCE(excluded.props_season, taxa.props_season),
            props_habitat = COALESCE(excluded.props_habitat, taxa.props_habitat),
            wiki_url_uk = COALESCE(excluded.wiki_url_uk, taxa.wiki_url_uk),
            wiki_url_en = COALESCE(excluded.wiki_url_en, taxa.wiki_url_en),
            updated_at = excluded.updated_at;
    """, (
        inat_id, species_name, name_uk, name_en, family, order_name, genus,
        edibility, hymenium, desc_uk, desc_en, lookalikes_uk, lookalikes_en,
        props_cap, props_hymenium, props_stem, props_flesh, props_season, props_habitat,
        wiki_url_uk, wiki_url_en, len(photos_to_download), now_ts
    ))

    # Retrieve taxon database ID
    cur.execute("SELECT id FROM taxa WHERE scientific_name = ?", (species_name,))
    taxon_db_id = cur.fetchone()[0]

    # Clear old photos for this taxon if re-running
    cur.execute("DELETE FROM photos WHERE taxon_id = ?", (taxon_db_id,))

    # 6. Download and save all photos
    saved_photos_count = 0
    for p_info in photos_to_download:
        img_res = http_get(p_info["url"], timeout=15)
        if img_res and img_res.status_code == 200:
            converted = optimize_image_to_webp(img_res.content, max_size=720, quality=80)
            if converted:
                webp_data, w, h = converted
                cur.execute("""
                    INSERT INTO photos (taxon_id, photo_index, original_url, attribution, license_code, width, height, image_data)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, (
                    taxon_db_id, p_info["index"], p_info["url"], p_info["attribution"],
                    p_info["license_code"], w, h, webp_data
                ))
                saved_photos_count += 1

    # Update actual saved photos count
    cur.execute("UPDATE taxa SET photos_count = ? WHERE id = ?", (saved_photos_count, taxon_db_id))
    conn.commit()
    return True


def main():
    script_dir = os.path.dirname(os.path.abspath(__file__))
    repo_root = os.path.dirname(script_dir) if os.path.basename(script_dir) == "scripts" else script_dir

    default_classes = "classes.json" if os.path.exists("classes.json") else os.path.join(repo_root, "classes.json")
    default_labels = "app/src/main/assets/labels.txt" if os.path.exists("app/src/main/assets/labels.txt") else os.path.join(repo_root, "app", "src", "main", "assets", "labels.txt")
    default_db = "mushrooms_offline.db" if os.path.exists("classes.json") else os.path.join(repo_root, "mushrooms_offline.db")

    parser = argparse.ArgumentParser(description="Mushroom Offline Database Builder")
    parser.add_argument("--classes", default=default_classes, help="Path to classes.json")
    parser.add_argument("--labels", default=default_labels, help="Path to labels.txt")
    parser.add_argument("--db", default=default_db, help="Path to output SQLite database")
    parser.add_argument("--limit", type=int, default=None, help="Limit number of species to process (for testing)")
    parser.add_argument("--delay", type=float, default=1.0, help="Delay between API calls in seconds (default: 1.0)")
    parser.add_argument("--compress", action="store_true", help="Gzip compress the final database")
    args = parser.parse_args()

    print("=" * 60)
    print("  Mushroom Offline SQLite Database Generator")
    print("=" * 60)

    # 1. Initialize SQLite
    conn = init_db(args.db)
    print(f"[1/4] SQLite database initialized: {args.db}")

    # 2. Load species list
    species_list, labels_edibility = load_species_list(args.classes, args.labels)
    total_found = len(species_list)
    print(f"[2/4] Unique species loaded: {total_found}")

    if args.limit:
        species_list = species_list[:args.limit]
        print(f"      Limit applied: processing first {len(species_list)} species")

    # 3. Check already processed species
    cur = conn.cursor()
    cur.execute("SELECT COUNT(*) FROM taxa WHERE updated_at IS NOT NULL")
    already_done = cur.fetchone()[0]
    print(f"[3/4] Checkpoint check: {already_done} / {len(species_list)} already in database")

    # 4. Process species loop
    print(f"[4/4] Fetching metadata and photos...")
    start_time = time.time()
    processed_count = 0

    pbar = tqdm(species_list, desc="Scraping species", unit="species")
    for sp in pbar:
        try:
            success = process_species(conn, sp, labels_edibility, delay=args.delay)
            if success:
                processed_count += 1
        except KeyboardInterrupt:
            print("\n[!] Process interrupted by user. Progress is safely saved in SQLite.")
            break
        except Exception as e:
            print(f"\n[!] Error processing '{sp}': {e}")

    # Run SQLite optimization
    print("\nOptimizing database (VACUUM)...")
    conn.execute("VACUUM;")
    conn.close()

    elapsed = time.time() - start_time
    db_size_mb = os.path.getsize(args.db) / (1024 * 1024)
    print(f"\nCompleted in {elapsed:.1f}s. Database size: {db_size_mb:.2f} MB")

    # 5. Optional compression
    if args.compress:
        import gzip
        import shutil
        gz_path = f"{args.db}.gz"
        print(f"Compressing database to {gz_path}...")
        with open(args.db, "rb") as f_in, gzip.open(gz_path, "wb", compresslevel=9) as f_out:
            shutil.copyfileobj(f_in, f_out)
        gz_size_mb = os.path.getsize(gz_path) / (1024 * 1024)
        print(f"Compressed size: {gz_size_mb:.2f} MB")

    print("\nAll done!")


if __name__ == "__main__":
    main()
