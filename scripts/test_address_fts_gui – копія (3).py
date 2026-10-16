#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Mushroom Offline Address Search & Universal Database Tool (OpenStreetData Importer)
Скрипт для викачування 3 адресних файлів OpenStreetData у папку addresses:
  1. {CODE}-streets.tsv.gz
  2. {CODE}-houses.tsv.gz
  3. {CODE}-addresses.tsv.gz
та генерації нормалізованої офлайн-бази адрес SQLite (addr.db) з надшвидким FTS5.
"""

import os
import sys
import re
import csv
import gzip
import time
import struct
import sqlite3
import threading
import urllib.request
import urllib.error
import tkinter as tk
from tkinter import ttk, messagebox

# UTF-8 консоль
try:
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    if hasattr(sys.stderr, "reconfigure"):
        sys.stderr.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

BASE_DIR = os.path.dirname(os.path.abspath(__file__))
DATA_DIR = os.path.join(BASE_DIR, "data")
ADDR_DIR = os.path.join(DATA_DIR, "addresses")
MAPS_DIR = os.path.join(DATA_DIR, "maps")
DB_NAME = "addr.db"
DB_PATH = os.path.join(ADDR_DIR, DB_NAME)

USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
OPENSTREETDATA_BASE_URL = "https://files.openstreetdata.org/addresses"

os.makedirs(ADDR_DIR, exist_ok=True)
os.makedirs(MAPS_DIR, exist_ok=True)


class CountryInfo:
    def __init__(self, code, name, name_uk, continent, slug):
        self.code = code.upper()
        self.name = name
        self.name_uk = name_uk
        self.continent = continent
        self.slug = slug

    def __str__(self):
        return f"[{self.code}] {self.name_uk} ({self.name})"


def load_countries():
    """Зчитує список країн із MapDownloadManager.kt проекту або повертає резервний список."""
    candidate_paths = [
        os.path.join(BASE_DIR, "..", "app", "src", "main", "java", "com", "olegskal", "mushroom", "map", "MapDownloadManager.kt"),
        os.path.join(BASE_DIR, "app", "src", "main", "java", "com", "olegskal", "mushroom", "map", "MapDownloadManager.kt"),
    ]
    kt_path = next((p for p in candidate_paths if os.path.isfile(p)), None)
    countries = []
    if kt_path:
        try:
            with open(kt_path, "r", encoding="utf-8") as f:
                content = f.read()
            pattern = re.compile(
                r'def\(\s*"([^"]+)",\s*"([^"]+)",\s*"([^"]+)",\s*"([^"]+)",\s*"([^"]+)",\s*([-\d.]+),\s*([-\d.]+),\s*([-\d.]+),\s*([-\d.]+)\s*\)'
            )
            for m in pattern.finditer(content):
                code, name, name_uk, continent, slug, _, _, _, _ = m.groups()
                countries.append(CountryInfo(code, name, name_uk, continent, slug))
        except Exception:
            pass

    if not countries:
        fallback = [
            ("UA", "Ukraine", "Україна", "europe", "ukraine"),
            ("PL", "Poland", "Польща", "europe", "poland"),
            ("SK", "Slovakia", "Словаччина", "europe", "slovakia"),
            ("RO", "Romania", "Румунія", "europe", "romania"),
            ("HU", "Hungary", "Угорщина", "europe", "hungary"),
            ("MD", "Moldova", "Молдова", "europe", "moldova"),
            ("DE", "Germany", "Німеччина", "europe", "germany"),
        ]
        countries = [CountryInfo(*x) for x in fallback]
    return countries


def get_map_info_for_country(country_slug):
    """Перевіряє наявність .map файлу карти та зчитує її Bounding Box з бінарного заголовка."""
    candidates = [
        os.path.join(MAPS_DIR, f"{country_slug}.map"),
        os.path.join(BASE_DIR, f"{country_slug}.map"),
    ]
    for p in candidates:
        if os.path.isfile(p) and os.path.getsize(p) > 1024:
            sz_mb = os.path.getsize(p) / (1024 * 1024)
            bbox = None
            try:
                with open(p, "rb") as f:
                    magic = f.read(20)
                    if magic == b"mapsforge binary OSM":
                        f.seek(20 + 4 + 4 + 8 + 8)
                        min_lat, min_lon, max_lat, max_lon = struct.unpack(">iiii", f.read(16))
                        bbox = (min_lat / 1e6, min_lon / 1e6, max_lat / 1e6, max_lon / 1e6)
            except Exception:
                pass
            return p, sz_mb, bbox
    return None, 0, None


# ---------------------------------------------------------
# Універсальна канонізація назв вулиць для будь-якої країни світу
# ---------------------------------------------------------
ABBREV_PATTERNS = [
    # Українські скорочення
    (re.compile(r'\bвул\.(?!\w)|\bвул\b', re.I), 'вулиця'),
    (re.compile(r'\bпров\.(?!\w)|\bпров\b', re.I), 'провулок'),
    (re.compile(r'\bпросп\.(?!\w)|\bпр\.(?!\w)|\bпросп\b', re.I), 'проспект'),
    (re.compile(r'\bбул\.(?!\w)|\bб-р\.(?!\w)|\bб-р(?!\w)|\bбул\b', re.I), 'бульвар'),
    (re.compile(r'\bпл\.(?!\w)', re.I), 'площа'),
    (re.compile(r'\bнаб\.(?!\w)', re.I), 'набережна'),
    (re.compile(r'\bтуп\.(?!\w)', re.I), 'тупик'),
    (re.compile(r'\bузв\.(?!\w)', re.I), 'узвіз'),
    (re.compile(r'\bпр-д\.(?!\w)', re.I), 'проїзд'),
    # English
    (re.compile(r'\bSt\.(?!\w)|\bSt\b', re.I), 'Street'),
    (re.compile(r'\bAve\.(?!\w)|\bAve\b', re.I), 'Avenue'),
    (re.compile(r'\bRd\.(?!\w)|\bRd\b', re.I), 'Road'),
    (re.compile(r'\bBlvd\.(?!\w)|\bBlvd\b', re.I), 'Boulevard'),
    (re.compile(r'\bDr\.(?!\w)|\bDr\b', re.I), 'Drive'),
    (re.compile(r'\bLn\.(?!\w)|\bLn\b', re.I), 'Lane'),
    (re.compile(r'\bCt\.(?!\w)|\bCt\b', re.I), 'Court'),
    (re.compile(r'\bPl\.(?!\w)|\bPl\b', re.I), 'Place'),
    (re.compile(r'\bSq\.(?!\w)|\bSq\b', re.I), 'Square'),
    (re.compile(r'\bHwy\.(?!\w)', re.I), 'Highway'),
    # French
    (re.compile(r'\bR\.(?!\w)', re.I), 'Rue'),
    (re.compile(r'\bAv\.(?!\w)', re.I), 'Avenue'),
    (re.compile(r'\bBd\.(?!\w)|\bBd\b', re.I), 'Boulevard'),
    (re.compile(r'\bAll\.(?!\w)', re.I), 'Allée'),
    (re.compile(r'\bImp\.(?!\w)', re.I), 'Impasse'),
    # German
    (re.compile(r'\bStr\.(?!\w)', re.I), 'Straße'),
    # Polish
    (re.compile(r'\bul\.(?!\w)|\bul\b', re.I), 'ulica'),
    (re.compile(r'\bal\.(?!\w)|\bal\b', re.I), 'aleja'),
]

POST_PREFIXES = {
    'вулиця': 'Вулиця', 'провулок': 'Провулок', 'проспект': 'Проспект',
    'бульвар': 'Бульвар', 'площа': 'Площа', 'набережна': 'Набережна',
    'узвіз': 'Узвіз', 'тупик': 'Тупик', 'алея': 'Алея', 'проїзд': 'Проїзд',
    'тракт': 'Тракт', 'ulica': 'Ulica', 'aleja': 'Aleja', 'plac': 'Plac'
}


def canonical_street_name_universal(name: str) -> str:
    """Стандартизує назву вулиці: розгортає скорочення та переносить тип вулиці на початок."""
    if not name:
        return ""
    n = " ".join(name.strip().split()).strip(",;")
    for pat, repl in ABBREV_PATTERNS:
        n = pat.sub(repl, n)
    words = n.split()
    if len(words) > 1 and words[-1].lower() in POST_PREFIXES:
        pref = POST_PREFIXES[words[-1].lower()]
        n = f"{pref} " + " ".join(words[:-1])
    elif n:
        n = n[0].upper() + n[1:]
    return " ".join(n.split())


def init_db(db_path):
    """Створює чисту нормалізовану схему бази (ідентичну поточній) та компактний FTS5 індекс."""
    conn = sqlite3.connect(db_path)
    cur = conn.cursor()
    cur.execute("PRAGMA journal_mode = WAL;")
    cur.execute("PRAGMA synchronous = NORMAL;")
    cur.execute("PRAGMA foreign_keys = ON;")

    # 1. Дерево адміністративних одиниць
    cur.execute("""
        CREATE TABLE IF NOT EXISTS admin_places (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            osm_id INTEGER,
            parent_id INTEGER,
            level INTEGER NOT NULL,
            name TEXT NOT NULL,
            code TEXT,
            FOREIGN KEY (parent_id) REFERENCES admin_places(id)
        );
    """)
    cur.execute("CREATE INDEX IF NOT EXISTS idx_admin_places_parent ON admin_places(parent_id);")

    # 2. Словник унікальних назв вулиць (дедуплікація)
    cur.execute("""
        CREATE TABLE IF NOT EXISTS street_names (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            name TEXT NOT NULL UNIQUE
        );
    """)

    # 3. Вулиці населеного пункту/громади зі зв'язком на словник street_names
    cur.execute("""
        CREATE TABLE IF NOT EXISTS streets (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            place_id INTEGER NOT NULL,
            region_id INTEGER,
            country_id INTEGER NOT NULL,
            name_id INTEGER NOT NULL,
            FOREIGN KEY (place_id) REFERENCES admin_places(id),
            FOREIGN KEY (region_id) REFERENCES admin_places(id),
            FOREIGN KEY (country_id) REFERENCES admin_places(id),
            FOREIGN KEY (name_id) REFERENCES street_names(id)
        );
    """)
    cur.execute("CREATE INDEX IF NOT EXISTS idx_streets_place_id ON streets(place_id);")

    # 4. Будинки з координатами в мікроградусах INTEGER (WITHOUT ROWID)
    cur.execute("""
        CREATE TABLE IF NOT EXISTS houses (
            street_id INTEGER NOT NULL,
            number TEXT NOT NULL,
            lat INTEGER NOT NULL,
            lon INTEGER NOT NULL,
            PRIMARY KEY (street_id, number),
            FOREIGN KEY (street_id) REFERENCES streets(id)
        ) WITHOUT ROWID;
    """)

    # 5. Ультракомпактний Contentless FTS5 індекс (rowid = streets.id)
    cur.execute("""
        CREATE VIRTUAL TABLE IF NOT EXISTS search_fts USING fts5(
            region_name,
            city_name,
            street_name,
            content='',
            detail=column,
            columnsize=0
        );
    """)
    conn.commit()
    return conn


class AddressIngestor:
    """Високошвидкісний пакетний завантажувач у нормалізовану структуру з кешуванням у пам'яті."""
    def __init__(self, conn, country_code, country_name):
        self.conn = conn
        self.cur = conn.cursor()
        self.country_code = country_code.upper()
        self.country_name = country_name

        # Ініціалізація країни (level = 2)
        self.cur.execute("SELECT id FROM admin_places WHERE level = 2 AND (code = ? OR name = ?);",
                         (self.country_code, self.country_name))
        row = self.cur.fetchone()
        if row:
            self.country_id = row[0]
        else:
            self.cur.execute("INSERT INTO admin_places (osm_id, parent_id, level, name, code) VALUES (0, NULL, 2, ?, ?);",
                             (self.country_name, self.country_code))
            self.conn.commit()
            self.country_id = self.cur.lastrowid

        # In-memory кеші
        self.places_cache = {}          # (parent_id, name.lower()) -> place_id
        self.places_by_name = {}        # name.lower() -> place_id
        self.street_names_cache = {}    # street_name.lower() -> name_id
        self.streets_cache = {}         # (place_id, name_id) -> street_id
        self.houses_batch = []
        self._warmup_cache()

    def _warmup_cache(self):
        """Завантажує існуючі місця та вулиці для O(1) перевірок."""
        self.cur.execute("""
            WITH RECURSIVE places_tree(id, parent_id, name) AS (
                SELECT id, parent_id, name FROM admin_places WHERE id = ?
                UNION ALL
                SELECT p.id, p.parent_id, p.name FROM admin_places p
                JOIN places_tree pt ON p.parent_id = pt.id
            )
            SELECT id, parent_id, name FROM places_tree;
        """, (self.country_id,))
        for pid, parent_id, name in self.cur.fetchall():
            self.places_cache[(parent_id, name.lower())] = pid
            self.places_by_name[name.lower()] = pid

        self.cur.execute("SELECT id, name FROM street_names;")
        for nid, sname in self.cur.fetchall():
            self.street_names_cache[sname.lower()] = nid

        if self.places_cache:
            pids = list({pid for (parent_id, name), pid in self.places_cache.items()})
            for i in range(0, len(pids), 500):
                batch_pids = pids[i:i+500]
                placeholders = ",".join("?" * len(batch_pids))
                self.cur.execute(f"SELECT id, place_id, name_id FROM streets WHERE place_id IN ({placeholders});", batch_pids)
                for sid, place_id, name_id in self.cur.fetchall():
                    self.streets_cache[(place_id, name_id)] = sid

    def find_place_id_by_name(self, name_lower):
        return self.places_by_name.get(name_lower)

    def get_or_create_place(self, parent_id, name, level, osm_id=None):
        if not name:
            return parent_id
        name_clean = name.strip()
        key = (parent_id, name_clean.lower())
        if key in self.places_cache:
            return self.places_cache[key]

        self.cur.execute(
            "INSERT INTO admin_places (osm_id, parent_id, level, name, code) VALUES (?, ?, ?, ?, NULL);",
            (osm_id, parent_id, level, name_clean)
        )
        pid = self.cur.lastrowid
        self.places_cache[key] = pid
        self.places_by_name[name_clean.lower()] = pid
        return pid

    def get_or_create_street_name(self, name):
        name_clean = canonical_street_name_universal(name)
        if not name_clean:
            name_clean = name.strip()
        key = name_clean.lower()
        if key in self.street_names_cache:
            return self.street_names_cache[key]

        self.cur.execute("INSERT OR IGNORE INTO street_names (name) VALUES (?);", (name_clean,))
        self.cur.execute("SELECT id FROM street_names WHERE name = ?;", (name_clean,))
        row = self.cur.fetchone()
        nid = row[0]
        self.street_names_cache[key] = nid
        return nid

    def get_or_create_street(self, place_id, street_name, region_name, city_name, region_id=None):
        street_clean = canonical_street_name_universal(street_name)
        if not street_clean:
            street_clean = street_name.strip()
        name_id = self.get_or_create_street_name(street_clean)
        key = (place_id, name_id)
        if key in self.streets_cache:
            return self.streets_cache[key]

        self.cur.execute(
            "INSERT INTO streets (place_id, region_id, country_id, name_id) VALUES (?, ?, ?, ?);",
            (place_id, region_id, self.country_id, name_id)
        )
        sid = self.cur.lastrowid
        self.streets_cache[key] = sid

        # Одночасне додавання у Contentless FTS5 індекс (rowid = sid)
        self.cur.execute("""
            INSERT INTO search_fts (rowid, region_name, city_name, street_name)
            VALUES (?, ?, ?, ?);
        """, (sid, region_name or "", city_name or "", street_clean))

        return sid

    def add_house(self, street_id, house_number, lat, lon):
        try:
            f_lat = float(lat)
            f_lon = float(lon)
            if abs(f_lat) > 1000:
                lat_i = int(round(f_lat))
                lon_i = int(round(f_lon))
            else:
                lat_i = int(round(f_lat * 1_000_000))
                lon_i = int(round(f_lon * 1_000_000))
        except (ValueError, TypeError):
            return

        self.houses_batch.append((street_id, str(house_number).strip(), lat_i, lon_i))
        if len(self.houses_batch) >= 50000:
            self.flush()

    def flush(self):
        if self.houses_batch:
            self.cur.executemany("INSERT OR REPLACE INTO houses (street_id, number, lat, lon) VALUES (?, ?, ?, ?);", self.houses_batch)
            self.conn.commit()
            self.houses_batch.clear()


# ---------------------------------------------------------
# Завантажувач файлів OpenStreetData
# ---------------------------------------------------------
def download_country_file(url, target_path, log_fn, stop_event=None):
    """Завантажує файл у папку addresses з відображенням прогресу та швидкості."""
    if os.path.isfile(target_path) and os.path.getsize(target_path) > 512:
        sz_mb = os.path.getsize(target_path) / (1024 * 1024)
        log_fn(f"[Файл] {os.path.basename(target_path)} вже є локально ({sz_mb:.2f} МБ).")
        return True

    log_fn(f"[Завантаження] {os.path.basename(target_path)} з openstreetdata.org...")
    req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    try:
        with urllib.request.urlopen(req, timeout=60) as resp:
            total_sz = int(resp.headers.get("Content-Length", 0))
            downloaded = 0
            t0 = time.time()
            last_log = t0
            tmp_path = target_path + ".tmp"
            with open(tmp_path, "wb") as f_out:
                while True:
                    if stop_event and stop_event.is_set():
                        log_fn("[!] Завантаження перервано користувачем.")
                        f_out.close()
                        if os.path.exists(tmp_path):
                            os.remove(tmp_path)
                        return False
                    chunk = resp.read(256 * 1024)
                    if not chunk:
                        break
                    f_out.write(chunk)
                    downloaded += len(chunk)
                    now = time.time()
                    if now - last_log >= 2.0:
                        mb = downloaded / (1024 * 1024)
                        spd = (downloaded / (now - t0)) / (1024 * 1024)
                        prog = f"{mb:.1f} МБ" if not total_sz else f"{mb:.1f}/{total_sz/(1024*1024):.1f} МБ ({downloaded/total_sz*100:.0f}%)"
                        log_fn(f"  Завантажено: {prog} [{spd:.1f} МБ/с]")
                        last_log = now

            if os.path.exists(target_path):
                os.remove(target_path)
            os.rename(tmp_path, target_path)
            sz_mb = os.path.getsize(target_path) / (1024 * 1024)
            log_fn(f"[+] Успішно завантажено {os.path.basename(target_path)} ({sz_mb:.2f} МБ за {time.time()-t0:.1f}с)")
            return True
    except Exception as e:
        log_fn(f"[-] Помилка завантаження {url}: {e}")
        return False


def ingest_openstreetdata(country, ingestor, addr_dir, log_fn, stop_event=None, update_status_fn=None):
    """
    Зчитує 3 файли OpenStreetData:
      - {CODE}-addresses.tsv.gz (Bounding Box'и для прив'язки)
      - {CODE}-streets.tsv.gz (адміністративна ієрархія та вулиці)
      - {CODE}-houses.tsv.gz (координати будинків)
    та пакетно записує в addr.db.
    """
    code = country.code.upper()
    streets_file = os.path.join(addr_dir, f"{code}-streets.tsv.gz")
    houses_file = os.path.join(addr_dir, f"{code}-houses.tsv.gz")
    addresses_file = os.path.join(addr_dir, f"{code}-addresses.tsv.gz")

    # 1. Читання {CODE}-addresses.tsv.gz для створення просторових BBox вулиць
    log_fn(f"[Імпорт] 1/3: Читання географічних меж вулиць ({os.path.basename(addresses_file)})...")
    street_bboxes = {}  # street_clean.lower() -> [(city_name, min_x, max_x, min_y, max_y), ...]
    if os.path.isfile(addresses_file):
        try:
            with gzip.open(addresses_file, "rt", encoding="utf-8", errors="replace") as f:
                reader = csv.DictReader(f, delimiter="\t")
                for row in reader:
                    s_name = row.get("street_", "").strip()
                    if not s_name:
                        continue
                    s_clean = canonical_street_name_universal(s_name).lower()
                    city = row.get("city_", "").strip()
                    try:
                        min_x = float(row.get("x_min") or 0.0)
                        max_x = float(row.get("x_max") or 0.0)
                        min_y = float(row.get("y_min") or 0.0)
                        max_y = float(row.get("y_max") or 0.0)
                    except ValueError:
                        continue
                    street_bboxes.setdefault(s_clean, []).append((city, min_x, max_x, min_y, max_y))
            log_fn(f"  Отримано BBox для {len(street_bboxes):,} унікальних вулиць.")
        except Exception as e:
            log_fn(f"  [-] Помилка читання addresses.tsv.gz: {e}")

    # 2. Читання {CODE}-streets.tsv.gz: створення admin_places та streets
    log_fn(f"[Імпорт] 2/3: Створення дерева регіонів та вулиць ({os.path.basename(streets_file)})...")
    streets_count = 0
    t0 = time.time()
    city_street_to_id = {}  # (place_id, street_clean.lower()) -> street_id
    streets_by_name = {}    # street_clean.lower() -> list of (place_id, street_id)

    if os.path.isfile(streets_file):
        try:
            with gzip.open(streets_file, "rt", encoding="utf-8", errors="replace") as f:
                reader = csv.DictReader(f, delimiter="\t")
                for row in reader:
                    if stop_event and stop_event.is_set():
                        return 0
                    s_name = row.get("street_name", "").strip()
                    if not s_name:
                        continue
                    s_clean = canonical_street_name_universal(s_name)
                    state = row.get("state", "").strip()
                    province = row.get("province", "").strip()
                    district = row.get("district", "").strip()
                    city = row.get("city", "").strip() or row.get("suburb", "").strip() or row.get("place", "").strip()

                    cur_parent = ingestor.country_id
                    region_id = None
                    if state:
                        state_id = ingestor.get_or_create_place(cur_parent, state, level=4)
                        region_id = state_id
                        cur_parent = state_id
                    if province:
                        prov_id = ingestor.get_or_create_place(cur_parent, province, level=6)
                        cur_parent = prov_id
                    if district:
                        dist_id = ingestor.get_or_create_place(cur_parent, district, level=7)
                        cur_parent = dist_id

                    place_id = cur_parent
                    if city:
                        place_id = ingestor.get_or_create_place(cur_parent, city, level=8)

                    sid = ingestor.get_or_create_street(place_id, s_clean, state or country.name_uk, city, region_id)
                    s_key = s_clean.lower()
                    city_street_to_id[(place_id, s_key)] = sid
                    streets_by_name.setdefault(s_key, []).append((place_id, sid))
                    streets_count += 1
                    if streets_count % 50000 == 0:
                        log_fn(f"  Оброблено {streets_count:,} вулиць...")
            log_fn(f"  Імпортовано {streets_count:,} вулиць за {time.time()-t0:.1f}с.")
        except Exception as e:
            log_fn(f"  [-] Помилка читання streets.tsv.gz: {e}")

    # 3. Читання {CODE}-houses.tsv.gz: збереження адресних точок у таблицю houses
    log_fn(f"[Імпорт] 3/3: Завантаження номерів будинків та координат ({os.path.basename(houses_file)})...")
    houses_count = 0
    t0 = time.time()
    if os.path.isfile(houses_file):
        try:
            with gzip.open(houses_file, "rt", encoding="utf-8", errors="replace") as f:
                reader = csv.DictReader(f, delimiter="\t")
                for row in reader:
                    if stop_event and stop_event.is_set():
                        break
                    h_num = row.get("house_number", "").strip()
                    raw_s = row.get("street", "").strip()
                    if not h_num or not raw_s:
                        continue
                    try:
                        lon = float(row["x"])
                        lat = float(row["y"])
                    except (ValueError, KeyError):
                        continue

                    s_clean = canonical_street_name_universal(raw_s)
                    s_key = s_clean.lower()
                    city_raw = row.get("city", "").strip()
                    street_id = None

                    # А. Прямий пошук по місту (якщо місто вказано в рядку)
                    if city_raw:
                        pid = ingestor.find_place_id_by_name(city_raw.lower())
                        if pid:
                            street_id = city_street_to_id.get((pid, s_key))

                    # Б. Якщо місто порожнє — пошук за BBox з addresses.tsv.gz
                    if not street_id and s_key in street_bboxes:
                        for c_city, min_x, max_x, min_y, max_y in street_bboxes[s_key]:
                            # Допуск ~150 метрів (0.002 градуса)
                            if (min_x - 0.002) <= lon <= (max_x + 0.002) and (min_y - 0.002) <= lat <= (max_y + 0.002):
                                if c_city:
                                    pid = ingestor.find_place_id_by_name(c_city.lower())
                                    if pid:
                                        street_id = city_street_to_id.get((pid, s_key))
                                        if street_id:
                                            break

                    # В. Якщо назва вулиці унікальна на всю країну
                    if not street_id:
                        matches = streets_by_name.get(s_key)
                        if matches and len(matches) == 1:
                            street_id = matches[0][1]

                    # Г. Резервний запис під місто або країну, якщо вулиці не було в streets.tsv
                    if not street_id:
                        fallback_city = city_raw or country.name_uk
                        fallback_pid = ingestor.get_or_create_place(ingestor.country_id, fallback_city, level=8)
                        street_id = ingestor.get_or_create_street(fallback_pid, s_clean, country.name_uk, fallback_city)
                        city_street_to_id[(fallback_pid, s_key)] = street_id

                    ingestor.add_house(street_id, h_num, lat, lon)
                    houses_count += 1
                    if houses_count % 100000 == 0:
                        if update_status_fn:
                            update_status_fn(f"Записано {houses_count:,} будинків...")
                        log_fn(f"  Записано {houses_count:,} будинків...")

            ingestor.flush()
            log_fn(f"  Всього збережено {houses_count:,} будинків за {time.time()-t0:.1f}с.")
        except Exception as e:
            log_fn(f"  [-] Помилка читання houses.tsv.gz: {e}")

    return houses_count


# ---------------------------------------------------------
# Головний графічний інтерфейс програми (Tkinter)
# ---------------------------------------------------------
class AddressApp(tk.Tk):
    def __init__(self):
        super().__init__()
        self.title("Mushroom Offline Address Search & OpenStreetData Tool (addr.db)")
        self.geometry("1100x720")
        self.minsize(950, 600)

        self.countries = load_countries()
        self.code_to_country = {c.code: c for c in self.countries}
        self.stop_event = threading.Event()

        self.setup_ui()
        self.refresh_db_list()

    def setup_ui(self):
        # Панель вибору країни та імпорту
        top_frame = ttk.LabelFrame(self, text="Імпорт з OpenStreetData (3 файли -> addresses -> addr.db)", padding=10)
        top_frame.pack(fill="x", padx=10, pady=5)

        row0 = ttk.Frame(top_frame)
        row0.pack(fill="x", pady=2)

        ttk.Label(row0, text="Оберіть країну:").pack(side="left", padx=(0, 5))
        self.country_cb = ttk.Combobox(row0, values=[str(c) for c in self.countries], state="readonly", width=34)
        if self.countries:
            self.country_cb.current(0)
        self.country_cb.pack(side="left", padx=5)
        self.country_cb.bind("<<ComboboxSelected>>", self.on_country_selected)

        self.lbl_map_info = ttk.Label(row0, text="", foreground="#0369a1", font=("TkDefaultFont", 9, "bold"))
        self.lbl_map_info.pack(side="left", padx=10)

        self.btn_import = ttk.Button(row0, text=f"⚡ Завантажити в {DB_NAME}", command=self.start_import)
        self.btn_import.pack(side="right", padx=5)

        # Панель офлайн-пошуку FTS5
        search_frame = ttk.LabelFrame(self, text=f"Офлайн Пошук Адрес (SQLite FTS5 у {DB_NAME})", padding=10)
        search_frame.pack(fill="x", padx=10, pady=5)

        row_s0 = ttk.Frame(search_frame)
        row_s0.pack(fill="x")

        ttk.Label(row_s0, text="База:").pack(side="left", padx=(0, 5))
        self.lbl_db_info = ttk.Label(row_s0, text=DB_NAME, font=("TkDefaultFont", 9, "bold"), foreground="#0f766e")
        self.lbl_db_info.pack(side="left", padx=(0, 15))

        ttk.Label(row_s0, text="Адреса:").pack(side="left", padx=(5, 5))
        self.search_entry = ttk.Entry(row_s0, width=45)
        self.search_entry.pack(side="left", fill="x", expand=True, padx=5)
        self.search_entry.bind("<Return>", lambda e: self.perform_search())

        self.btn_search = ttk.Button(row_s0, text="🔍 Знайти", command=self.perform_search)
        self.btn_search.pack(side="left", padx=5)

        self.status_lbl = ttk.Label(search_frame, text="Готово до пошуку", foreground="#0284c7")
        self.status_lbl.pack(fill="x", pady=(5, 0))

        # Таблиця результатів пошуку
        tree_frame = ttk.Frame(self, padding=5)
        tree_frame.pack(fill="both", expand=True, padx=10, pady=5)

        columns = ("id", "country", "region", "city", "street", "house", "lat", "lon")
        self.tree = ttk.Treeview(tree_frame, columns=columns, show="headings", selectmode="browse")

        self.tree.heading("id", text="#")
        self.tree.heading("country", text="Країна")
        self.tree.heading("region", text="Область/Регіон")
        self.tree.heading("city", text="Місто/Село")
        self.tree.heading("street", text="Вулиця")
        self.tree.heading("house", text="Будинок")
        self.tree.heading("lat", text="Широта (Lat)")
        self.tree.heading("lon", text="Довгота (Lon)")

        self.tree.column("id", width=50, anchor="center")
        self.tree.column("country", width=55, anchor="center")
        self.tree.column("region", width=150, anchor="w")
        self.tree.column("city", width=140, anchor="w")
        self.tree.column("street", width=220, anchor="w")
        self.tree.column("house", width=75, anchor="center")
        self.tree.column("lat", width=95, anchor="center")
        self.tree.column("lon", width=95, anchor="center")

        scrollbar = ttk.Scrollbar(tree_frame, orient="vertical", command=self.tree.yview)
        self.tree.configure(yscrollcommand=scrollbar.set)

        self.tree.pack(side="left", fill="both", expand=True)
        scrollbar.pack(side="right", fill="y")

        # Консоль логів
        log_frame = ttk.LabelFrame(self, text="Лог операцій", padding=5)
        log_frame.pack(fill="x", padx=10, pady=5)

        log_ctrl_frame = ttk.Frame(log_frame)
        log_ctrl_frame.pack(fill="x", pady=(0, 4))

        self.btn_copy_log = ttk.Button(log_ctrl_frame, text="📋 Копіювати лог", command=self.copy_log_to_clipboard)
        self.btn_copy_log.pack(side="left", padx=(0, 5))

        self.btn_clear_log = ttk.Button(log_ctrl_frame, text="🧹 Очистити", command=self.clear_log)
        self.btn_clear_log.pack(side="left", padx=5)

        self.log_txt = tk.Text(log_frame, height=7, wrap="word", bg="#1e1e1e", fg="#e0e0e0", font=("Consolas", 9))
        self.log_txt.pack(fill="both", expand=True)

        self.log_menu = tk.Menu(self, tearoff=0)
        self.log_menu.add_command(label="Копіювати весь лог", command=self.copy_log_to_clipboard)
        self.log_menu.add_separator()
        self.log_menu.add_command(label="Очистити лог", command=self.clear_log)
        self.log_txt.bind("<Button-3>", lambda e: self.log_menu.tk_popup(e.x_root, e.y_root))

    def on_country_selected(self, event):
        sel = self.country_cb.get()
        if not sel:
            return
        code = sel.split("]")[0].replace("[", "").strip().upper()
        country = self.code_to_country.get(code)
        if not country:
            return
        map_path, sz_mb, bbox = get_map_info_for_country(country.slug)
        if map_path:
            bbox_str = f"BBox: {bbox[0]:.2f}, {bbox[1]:.2f}..{bbox[2]:.2f}, {bbox[3]:.2f}" if bbox else ""
            self.lbl_map_info.configure(
                text=f"🗺️ {os.path.basename(map_path)} ({sz_mb:.1f} МБ) {bbox_str}",
                foreground="#047857"
            )
        else:
            self.lbl_map_info.configure(
                text=f"🗺️ Файл {country.slug}.map не знайдено локально",
                foreground="#b45309"
            )

    def log(self, msg):
        def _append():
            self.log_txt.configure(state="normal")
            self.log_txt.insert("end", f"[{time.strftime('%H:%M:%S')}] {msg}\n")
            self.log_txt.see("end")
        self.after(0, _append)

    def copy_log_to_clipboard(self):
        text = self.log_txt.get("1.0", "end-1c")
        if text.strip():
            self.clipboard_clear()
            self.clipboard_append(text)
            self.update()
            self.status_lbl.configure(text="Лог скопійовано у буфер обміну!")
            messagebox.showinfo("Буфер обміну", "Весь лог успішно скопійовано у буфер обміну!")
        else:
            self.status_lbl.configure(text="Лог порожній, нічого копіювати.")

    def clear_log(self):
        self.log_txt.configure(state="normal")
        self.log_txt.delete("1.0", "end")
        self.status_lbl.configure(text="Лог очищено.")

    def refresh_db_list(self):
        if not os.path.exists(DB_PATH):
            self.status_lbl.configure(text=f"База {DB_NAME} ще відсутня. Оберіть країну та натисніть Завантажити.")
            return

        try:
            conn = sqlite3.connect(DB_PATH)
            cur = conn.cursor()

            cur.execute("SELECT count(*) FROM houses;")
            total_houses = cur.fetchone()[0]

            cur.execute("SELECT count(*) FROM streets;")
            total_streets = cur.fetchone()[0]

            cur.execute("SELECT count(*) FROM street_names;")
            total_street_names = cur.fetchone()[0]

            cur.execute("SELECT count(*) FROM admin_places;")
            total_places = cur.fetchone()[0]

            conn.close()

            sz_mb = os.path.getsize(DB_PATH) / (1024 * 1024)
            self.status_lbl.configure(
                text=f"База {DB_NAME}: {total_houses:,} будинків | {total_streets:,} вулиць ({total_street_names:,} назв) | {total_places:,} місць | Розмір: {sz_mb:.2f} МБ"
            )
        except Exception as e:
            self.status_lbl.configure(text=f"Помилка: {e}")

    def start_import(self):
        sel = self.country_cb.get()
        if not sel:
            return
        code = sel.split("]")[0].replace("[", "").strip().upper()
        country = self.code_to_country.get(code)
        if not country:
            return

        self.btn_import.configure(state="disabled")

        def _worker():
            try:
                self.log(f"--- Початок імпорту {country} (OpenStreetData -> {DB_NAME}) ---")
                
                # 1. Завантаження 3 файлів у папку addresses
                files_to_dl = [
                    f"{code}-streets.tsv.gz",
                    f"{code}-houses.tsv.gz",
                    f"{code}-addresses.tsv.gz",
                ]
                for fname in files_to_dl:
                    if self.stop_event.is_set():
                        break
                    url = f"{OPENSTREETDATA_BASE_URL}/{fname}"
                    tgt_path = os.path.join(ADDR_DIR, fname)
                    ok = download_country_file(url, tgt_path, self.log, self.stop_event)
                    if not ok:
                        self.log(f"[-] Не вдалося завантажити {fname}. Зупинка.")
                        messagebox.showerror("Помилка", f"Не вдалося завантажити {fname}")
                        return

                if self.stop_event.is_set():
                    return

                # 2. Створення / підключення до бази та імпорт
                conn = init_db(DB_PATH)
                ingestor = AddressIngestor(conn, country.code, country.name_uk)

                cnt_added = ingest_openstreetdata(
                    country=country,
                    ingestor=ingestor,
                    addr_dir=ADDR_DIR,
                    log_fn=self.log,
                    stop_event=self.stop_event,
                    update_status_fn=lambda txt: self.after(0, lambda: self.status_lbl.configure(text=txt))
                )

                # 3. Оптимізація індексу FTS5 (optimize) та дефрагментація (VACUUM)
                if cnt_added > 0:
                    self.log("Оптимізація індексу FTS5 (optimize) та дефрагментація (VACUUM)...")
                    t_fts = time.time()
                    conn.execute("INSERT INTO search_fts(search_fts) VALUES('optimize');")
                    conn.commit()
                    conn.execute("VACUUM;")
                    conn.commit()
                    sz_mb = os.path.getsize(DB_PATH) / (1024 * 1024)
                    self.log(f"Базу оптимізовано за {time.time()-t_fts:.2f}с. Фінальний розмір {DB_NAME}: {sz_mb:.2f} МБ")

                conn.close()
                self.after(0, self.refresh_db_list)
                msg_txt = f"Країну {country.name_uk} успішно оброблено!\nВсього адрес додано: {cnt_added:,}"
                messagebox.showinfo("Результат", msg_txt)

            except Exception as e:
                self.log(f"[-] Критична помилка імпорту: {e}")
                messagebox.showerror("Помилка", str(e))
            finally:
                self.after(0, lambda: self.btn_import.configure(state="normal"))

        threading.Thread(target=_worker, daemon=True).start()

    def perform_search(self):
        query = self.search_entry.get().strip()
        if not query:
            return

        if not os.path.exists(DB_PATH):
            messagebox.showwarning("Увага", f"База {DB_NAME} ще не існує! Спочатку завантажте адресу хоча б однієї країни.")
            return

        for r in self.tree.get_children():
            self.tree.delete(r)

        # Очищення та розбір токенів запиту
        clean_q = query.replace('"', ' ').replace("'", ' ').replace('`', ' ').replace('’', ' ').replace(',', ' ').replace('.', ' ')
        tokens = clean_q.split()
        house_num = None
        text_tokens = []
        for t in tokens:
            if re.match(r'^\d+[\w\dа-яА-ЯіІїЇєЄґҐ/-]*$', t):
                house_num = t
            else:
                words = re.findall(r'[\w\dа-яА-ЯіІїЇєЄґҐ]+', t)
                for w in words:
                    if len(w) > 0:
                        text_tokens.append(f'"{w}"*')

        fts_query = " ".join(text_tokens) if text_tokens else ""

        t0 = time.time()
        results = []
        try:
            conn = sqlite3.connect(DB_PATH)
            cur = conn.cursor()

            if house_num and fts_query:
                h_up = house_num.upper().strip()
                h_low = house_num.lower().strip()
                h_vars = {
                    h_up, h_low,
                    h_up.replace(" ", ""), h_low.replace(" ", ""),
                    h_up.replace("-", ""), h_low.replace("-", "")
                }
                m_num = re.match(r'^([\d/]+)[-\s]?([^\d\s/]+)$', house_num)
                if m_num:
                    num_p, let_p = m_num.groups()
                    for let in (let_p.upper(), let_p.lower()):
                        h_vars.update([
                            f"{num_p}{let}",
                            f"{num_p}-{let}",
                            f"{num_p} {let}",
                            f"{num_p}/{let}"
                        ])
                vars_list = list(h_vars)
                in_placeholders = ",".join("?" * len(vars_list))

                sql = f"""
                    SELECT COALESCE(country.code, ''), COALESCE(reg.name, ''), city.name, sn.name, h.number, h.lat, h.lon
                    FROM search_fts f
                    JOIN streets s ON s.id = f.rowid
                    JOIN street_names sn ON sn.id = s.name_id
                    JOIN admin_places city ON city.id = s.place_id
                    LEFT JOIN admin_places reg ON reg.id = s.region_id
                    LEFT JOIN admin_places country ON country.id = s.country_id
                    JOIN houses h ON h.street_id = s.id
                    WHERE search_fts MATCH ?
                      AND (h.number IN ({in_placeholders}) OR h.number LIKE ? OR h.number LIKE ?)
                    LIMIT 100
                """
                cur.execute(sql, [fts_query] + vars_list + [f"{h_up}%", f"{h_low}%"])
            elif fts_query:
                sql = """
                    SELECT COALESCE(country.code, ''), COALESCE(reg.name, ''), city.name, sn.name, h.number, h.lat, h.lon
                    FROM search_fts f
                    JOIN streets s ON s.id = f.rowid
                    JOIN street_names sn ON sn.id = s.name_id
                    JOIN admin_places city ON city.id = s.place_id
                    LEFT JOIN admin_places reg ON reg.id = s.region_id
                    LEFT JOIN admin_places country ON country.id = s.country_id
                    JOIN houses h ON h.street_id = s.id
                    WHERE search_fts MATCH ?
                    LIMIT 100
                """
                cur.execute(sql, (fts_query,))
            elif house_num:
                messagebox.showinfo("Підказка", "Вкажіть також назву міста або вулиці для пошуку.")
                conn.close()
                return

            results = cur.fetchall()
            conn.close()
        except Exception as e:
            self.log(f"Помилка пошуку в {DB_NAME}: {e}")

        elapsed_ms = (time.time() - t0) * 1000.0

        for idx, r in enumerate(results, start=1):
            country_code, reg_name, city_name, street_name, house, lat_raw, lon_raw = r
            try:
                lat = f"{lat_raw / 1_000_000.0:.6f}"
                lon = f"{lon_raw / 1_000_000.0:.6f}"
            except Exception:
                lat, lon = str(lat_raw), str(lon_raw)
            self.tree.insert("", "end", values=(idx, country_code, reg_name, city_name, street_name, house, lat, lon))

        self.status_lbl.configure(
            text=f"Знайдено: {len(results)} записів у {DB_NAME} (всі країни) | Час: {elapsed_ms:.1f} мс"
        )


if __name__ == "__main__":
    app = AddressApp()
    app.mainloop()
