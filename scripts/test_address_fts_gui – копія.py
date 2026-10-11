#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Mushroom Offline Address Search & Universal Database Tool (Test GUI)
Скрипт для створення нормалізованої, мінімальної за розміром офлайн-бази адрес SQLite (addr.db)
з підтримкою будь-якої країни світу (універсальна ієрархія admin_places -> streets -> houses) та надшвидким FTS5.

Підтримує:
1. Завантаження адрес через OSM Overpass API з ротацією дзеркал та розбивкою по регіонах.
2. Імпорт з Mapsforge POI файлів (.poi).
3. Імпорт з OpenAddresses (GeoJSON, SHP.ZIP, CSV).
"""

import os
import sys
import re
import csv
import json
import time
import struct
import sqlite3
import threading
import zipfile
import io
import math
import urllib.request
import urllib.parse
import urllib.error
from concurrent.futures import ThreadPoolExecutor
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
POI_DIR = os.path.join(DATA_DIR, "poi")
MAPS_DIR = os.path.join(DATA_DIR, "maps")
DB_NAME = "addr.db"
DB_PATH = os.path.join(ADDR_DIR, DB_NAME)
USER_AGENT = "Mushroom-Address-Tool/2.0 (https://github.com/OlegSkalGit/mushroom)"

# Дзеркала Overpass API (глобальні сервери зі світовою базою)
OVERPASS_MIRRORS = [
    "https://gall.openstreetmap.de/api/interpreter",
    "https://lambert.openstreetmap.de/api/interpreter",
    "https://overpass-api.de/api/interpreter",
    "https://z.overpass-api.de/api/interpreter",
    "https://lz4.overpass-api.de/api/interpreter",
]

os.makedirs(ADDR_DIR, exist_ok=True)
os.makedirs(POI_DIR, exist_ok=True)
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
    """Зчитує список країн із MapDownloadManager.kt проекту."""
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
    """
    Універсальна стандартизація назви вулиці для будь-якої мови світу:
    1. Розгортає скорочення (вул. -> вулиця, St. -> Street, Str. -> Straße, ul. -> ulica).
    2. Переставляє суфікси на початок (Шевченка вулиця -> Вулиця Шевченка, Mickiewicza ulica -> Ulica Mickiewicza).
    3. Приводить перший символ до великої літери та видаляє зайві пробіли.
    """
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
    """Створює чисту нормалізовану схему без дублювання назв та компактний FTS5 індекс."""
    conn = sqlite3.connect(db_path)
    cur = conn.cursor()
    cur.execute("PRAGMA journal_mode = WAL;")
    cur.execute("PRAGMA synchronous = NORMAL;")
    cur.execute("PRAGMA foreign_keys = ON;")

    # 1. Дерево адміністративних одиниць (Країна, Область, Район, Громада, Місто, Село)
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

    # 2. Словник унікальних назв вулиць (Крок 3: глобальна дедуплікація)
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
    cur.execute("DROP INDEX IF EXISTS idx_streets_region_id;")

    # 4. Будинки з координатами (Варіант А: WITHOUT ROWID, Варіант Б: мікроградуси INTEGER)
    # Дедуплікація (Резерв №1): один запис на (street_id, number) без дублювання під'їздів та точок будівлі
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

    # Авто-міграція існуючої таблиці houses, якщо вона містить старий 4-стовпчиковий первинний ключ
    try:
        cur.execute("PRAGMA table_info(houses);")
        pks = [row[1] for row in cur.fetchall() if row[5] > 0]
        if len(pks) > 2:
            cur.execute("""
                CREATE TABLE houses_new (
                    street_id INTEGER NOT NULL,
                    number TEXT NOT NULL,
                    lat INTEGER NOT NULL,
                    lon INTEGER NOT NULL,
                    PRIMARY KEY (street_id, number),
                    FOREIGN KEY (street_id) REFERENCES streets(id)
                ) WITHOUT ROWID;
            """)
            cur.execute("INSERT OR REPLACE INTO houses_new SELECT street_id, number, lat, lon FROM houses;")
            cur.execute("DROP TABLE houses;")
            cur.execute("ALTER TABLE houses_new RENAME TO houses;")
    except Exception:
        pass

    # 5. Ультракомпактний Contentless FTS5 індекс (content='', detail=column, columnsize=0)
    # Зберігає лише пошуковий індекс (без копій тексту та без bm25 ваг, розмір лише ~4-5 МБ)
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

        # In-memory кеші:
        # (parent_id, name.lower()) -> place_id
        self.places_cache = {}
        # street_name.lower() -> name_id
        self.street_names_cache = {}
        # (place_id, name_id) -> street_id
        self.streets_cache = {}
        self.houses_batch = []
        self._warmup_cache()

    def _warmup_cache(self):
        """Завантажує існуючі місця та вулиці цієї країни для миттєвих O(1) пошуків."""
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
                # Вже в мікроградусах (наприклад, з Mapsforge POI)
                lat_i = int(round(f_lat))
                lon_i = int(round(f_lon))
            else:
                # Звичайні градуси -> переводимо в мікроградуси (точність ~10 см)
                lat_i = int(round(f_lat * 1_000_000))
                lon_i = int(round(f_lon * 1_000_000))
        except (ValueError, TypeError):
            return

        self.houses_batch.append((street_id, str(house_number).strip(), lat_i, lon_i))
        if len(self.houses_batch) >= 10000:
            self.flush()

    def flush(self):
        if self.houses_batch:
            self.cur.executemany("INSERT OR REPLACE INTO houses (street_id, number, lat, lon) VALUES (?, ?, ?, ?);", self.houses_batch)
            self.conn.commit()
            self.houses_batch.clear()


class OverpassClient:
    """
    Клієнт Overpass API з паралельним моніторингом /api/status.
    Перед кожним запитом опитує всі сервери за 0.3с, знаходить той, де черга найменша (або 0с),
    і шле запит туди.
    """
    def __init__(self, mirrors=None):
        self.mirrors = list(mirrors or OVERPASS_MIRRORS)
        self.lock = threading.Lock()

    @staticmethod
    def _clean_server_response(content, max_len=180):
        if not content:
            return ""
        if isinstance(content, bytes):
            if content.startswith(b"\x1f\x8b"):
                try:
                    import gzip
                    content = gzip.decompress(content)
                except Exception:
                    pass
            try:
                content = content.decode("utf-8", errors="replace")
            except Exception:
                return ""
        import html
        text = html.unescape(content)
        # Видаляємо HTML-теги для зручності читання логу
        text = re.sub(r"<[^>]+>", " ", text)
        # Прибираємо довгі стандартні OSM-дисклеймери про ліцензії
        for noise in [
            "The data included in this document is from www.openstreetmap.org.",
            "It has there been collected by a large group of volunteer contributors to openstreetmap.org.",
            "The data is made available under ODbL.",
            "It is released under ODbL.",
            "An error occured during the execution of the overpass query! This is what overpass told us:",
            "OSM3S Response",
        ]:
            text = text.replace(noise, "")
        text = " ".join(text.split())
        text = text.replace("Error :", "Error:").replace("error :", "error:")
        if len(text) > max_len:
            text = text[:max_len] + "..."
        return text

    @staticmethod
    def escalate_query_memory(query):
        """
        Підвищує ліміт maxsize у запиті при помилці Out of Memory:
        Якщо maxsize немає -> додає [maxsize:1073741824] (1 ГБ).
        Якщо був 1 ГБ -> піднімає до [maxsize:2147483648] (2 ГБ).
        Повертає (новий_запит, текст_ліміту) або (None, None), якщо вже досягнуто максимуму (2 ГБ).
        """
        m = re.search(r"\[maxsize:(\d+)\]", query)
        if not m:
            new_q = re.sub(r"(\[timeout:\d+\])", r"\1[maxsize:1073741824]", query)
            if new_q == query:
                new_q = "[maxsize:1073741824]" + query
            return new_q, "1 ГБ"
        current_bytes = int(m.group(1))
        if current_bytes < 1073741824:
            new_q = re.sub(r"\[maxsize:\d+\]", "[maxsize:1073741824]", query)
            return new_q, "1 ГБ"
        elif current_bytes < 2147483648:
            new_q = re.sub(r"\[maxsize:\d+\]", "[maxsize:2147483648]", query)
            return new_q, "2 ГБ"
        else:
            return None, None

    def _sleep(self, seconds, stop_event=None):
        step = 0.5
        waited = 0.0
        while waited < seconds:
            if stop_event and stop_event.is_set():
                break
            time.sleep(step)
            waited += step

    def find_best_mirrors(self):
        """
        Паралельно опитує /api/status усіх дзеркал одночасно за ~0.3 секунди.
        Повертає відсортований список доступних дзеркал:
        [(mirror, effective_wait_sec, available_slots), ...]
        де першим завжди йде сервер з вільним слотом (wait=0) або мінімальним часом черги.
        """
        def _check(m):
            url = m.replace("/interpreter", "/status")
            try:
                req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
                with urllib.request.urlopen(req, timeout=3.5) as r:
                    text = r.read().decode("utf-8", "ignore")

                avail = 0
                m_avail = re.search(r"(\d+)\s+slots?\s+available\s+now", text, re.I)
                if m_avail:
                    avail = int(m_avail.group(1))

                wait_sec = 0
                m_wait = re.search(r"in\s+(\d+)\s+seconds", text, re.I)
                if m_wait:
                    wait_sec = int(m_wait.group(1))

                eff_wait = 0 if avail > 0 else (wait_sec if wait_sec > 0 else 60)
                return (m, True, eff_wait, avail)
            except Exception:
                return (m, False, 9999, 0)

        with ThreadPoolExecutor(max_workers=len(self.mirrors)) as ex:
            results = list(ex.map(_check, self.mirrors))

        # Сортуємо: тільки живі сервери, спочатку з мінімальним часом очікування
        alive = [r for r in results if r[1]]
        if alive:
            alive.sort(key=lambda x: x[2])
            return [(r[0], r[2], r[3]) for r in alive]

        # Резервний список якщо перевірка статусів не пройшла
        return [(m, 0, 1) for m in self.mirrors]

    def query(self, ql_query, timeout=90, log_fn=None, stop_event=None, max_cycles=3):
        for cycle in range(1, max_cycles + 1):
            if stop_event and stop_event.is_set():
                return None

            # Миттєвий зріз по всіх серверах одночасно за ~0.3с
            ranked = self.find_best_mirrors()
            if not ranked:
                break

            best_mirror, min_wait, avail = ranked[0]

            # Якщо навіть на найкращому сервері треба зачекати (всі слоти зайняті)
            if avail == 0 and min_wait > 0:
                sleep_time = min(min_wait + 2, 60)
                if log_fn:
                    log_fn(f"  [⏳] Усі сервери зайняті. Найшвидший слот на {best_mirror} (через {min_wait}с) — очікуємо {sleep_time}с...")
                self._sleep(sleep_time, stop_event)
                if stop_event and stop_event.is_set():
                    return None

            # Пробуємо сервери в порядку готовності
            for mirror, wait_sec, free_slots in ranked:
                if stop_event and stop_event.is_set():
                    return None

                try:
                    data = urllib.parse.urlencode({"data": ql_query}).encode("utf-8")
                    req = urllib.request.Request(
                        mirror,
                        data=data,
                        headers={
                            "User-Agent": USER_AGENT,
                            "Accept-Encoding": "gzip, deflate"
                        }
                    )
                    with urllib.request.urlopen(req, timeout=timeout) as resp:
                        raw = resp.read()
                        if resp.headers.get("Content-Encoding") == "gzip" or raw.startswith(b"\x1f\x8b"):
                            import gzip
                            raw = gzip.decompress(raw)
                        text = raw.decode("utf-8", errors="replace")

                        if not text or not text.strip():
                            if log_fn:
                                log_fn(f"  [-] {mirror}: HTTP {resp.status} — порожня відповідь (0 байт). Наступне дзеркало...")
                            self._sleep(1.0, stop_event)
                            continue

                        text_low = text.lower()
                        if "waiting for slot" in text_low or "slot available" in text_low:
                            srv_msg = self._clean_server_response(text)
                            msg_info = f': "{srv_msg}"' if srv_msg else ""
                            if log_fn:
                                log_fn(f"  [⏳] {mirror}: HTTP {resp.status} [Відповідь сервера{msg_info}] — слот перехоплено. Пересканування статусів...")
                            break  # Виходимо з циклу дзеркал на новий паралельний зріз find_best_mirrors

                        if "<html" in text_low or "runtime error" in text_low:
                            srv_msg = self._clean_server_response(text)
                            msg_info = f': "{srv_msg}"' if srv_msg else ""
                            if "out of memory" in text_low or "out of memory" in srv_msg.lower():
                                new_q, mem_str = self.escalate_query_memory(ql_query)
                                if new_q:
                                    if log_fn:
                                        log_fn(f"  [⚠️] {mirror}: Перевищено ліміт пам'яті (Out of memory). Авто-ескалація ліміту до {mem_str} [maxsize] та повтор...")
                                    self._sleep(2.0, stop_event)
                                    return self.query(new_q, timeout=max(timeout, 300), log_fn=log_fn, stop_event=stop_event, max_cycles=max_cycles)
                                else:
                                    if log_fn:
                                        log_fn(f"  [❌] {mirror}: Перевищено абсолютний ліміт 2 ГБ RAM на запит. Регіон надто великий для одного запиту.")
                            if "too busy" in text_low or "too busy" in srv_msg.lower():
                                if log_fn:
                                    log_fn(f"  [⏳] {mirror}: HTTP {resp.status} [Відповідь сервера{msg_info}] — ядро сервера зайняте. Пауза 8с та пересканування...")
                                self._sleep(8.0, stop_event)
                                break

                            if log_fn:
                                log_fn(f"  [-] {mirror}: HTTP {resp.status} [Помилка Overpass{msg_info}]. Наступне дзеркало...")
                            self._sleep(1.0, stop_event)
                            continue

                        if log_fn:
                            lines_cnt = len([l for l in text.splitlines() if l.strip()])
                            raw_size_mb = len(raw) / (1024 * 1024)
                            size_str = f"{raw_size_mb:.2f} МБ" if raw_size_mb >= 0.1 else f"{len(raw)/1024:.1f} КБ"
                            slots_info = f", вільних слотів: {free_slots}" if free_slots > 0 else ""
                            log_fn(f"  [+] {mirror}: HTTP {resp.status} OK — отримано {lines_cnt:,} рядків ({size_str}{slots_info})")
                        return text

                except urllib.error.HTTPError as e:
                    resp_text = ""
                    try:
                        err_body = e.read()
                        resp_text = self._clean_server_response(err_body)
                    except Exception:
                        pass

                    server_info = f': "{resp_text}"' if resp_text else ""
                    err_combined = f"{e} {resp_text}".lower()

                    if "out of memory" in err_combined:
                        new_q, mem_str = self.escalate_query_memory(ql_query)
                        if new_q:
                            if log_fn:
                                log_fn(f"  [⚠️] {mirror}: HTTP {e.code} — перевищено ліміт пам'яті (Out of memory). Авто-ескалація ліміту до {mem_str} [maxsize] та повтор...")
                            self._sleep(2.0, stop_event)
                            return self.query(new_q, timeout=max(timeout, 300), log_fn=log_fn, stop_event=stop_event, max_cycles=max_cycles)
                        else:
                            if log_fn:
                                log_fn(f"  [❌] {mirror}: Перевищено абсолютний ліміт 2 ГБ RAM на запит. Регіон надто великий для одного запиту.")

                    if e.code == 429 or "rate_limited" in err_combined or "slot" in err_combined:
                        if log_fn:
                            log_fn(f"  [⏳] {mirror}: HTTP {e.code} [Відповідь сервера{server_info}] — слот зайнятий. Пересканування статусів...")
                        break  # Виходимо на новий паралельний зріз

                    if e.code == 504:
                        if log_fn:
                            log_fn(f"  [⏳] {mirror}: HTTP 504 [Відповідь сервера{server_info}] — ядро зайняте. Пауза 8с для завершення та пересканування...")
                        self._sleep(8.0, stop_event)
                        break  # Виходимо на новий зріз, бо інші дзеркала теж могли бути зайняті в той самий момент

                    backoff = 0.5 if e.code == 500 else 2.0
                    if log_fn:
                        log_fn(f"  [-] {mirror}: HTTP {e.code} [Відповідь сервера{server_info}] (коло {cycle}/{max_cycles}). Наступне дзеркало...")
                    self._sleep(backoff, stop_event)

                except Exception as e:
                    if log_fn:
                        log_fn(f"  [-] {mirror}: Помилка підключення ({type(e).__name__}: {e}) (коло {cycle}/{max_cycles}). Наступне дзеркало...")
                    self._sleep(1.5, stop_event)

        return None


def fetch_overpass_regions(client, country_code, log_fn, stop_event=None):
    """Отримує універсальний список регіонів 4-го рівня (admin_level=4) для будь-якої країни у форматі чистого CSV."""
    log_fn(f"[Overpass] Швидкий запит списку регіонів (admin_level=4) у чистому CSV для {country_code}...")

    # 1. Швидкий запит за тегом ISO3166-2 (миттєва відповідь за 2с без важких обчислень геометрії)
    q = f"""[out:csv(::id, "name:uk", "name", "name:en"; false; ";")][timeout:30];
relation["admin_level"="4"]["ISO3166-2"~"^{country_code}"];
out tags;
"""
    raw = client.query(q, timeout=35, log_fn=log_fn, stop_event=stop_event, max_cycles=3)

    # 2. Якщо країна не має стандарту ISO3166-2 — резервний запит через межі країни (теж у чистому CSV)
    if not raw or not raw.strip():
        q_fallback = f"""[out:csv(::id, "name:uk", "name", "name:en"; false; ";")][timeout:45];
area["ISO3166-1"="{country_code}"][admin_level=2]->.c;
rel(area.c)["admin_level"="4"];
out tags;
"""
        raw = client.query(q_fallback, timeout=50, log_fn=log_fn, stop_event=stop_event, max_cycles=3)

    regions = []
    if raw:
        seen_names = set()
        for line in raw.splitlines():
            line = line.strip()
            if not line:
                continue
            parts = line.split(";")
            try:
                rel_id = int(parts[0].strip())
            except (ValueError, IndexError):
                continue
            name_uk = parts[1].strip() if len(parts) > 1 else ""
            name_local = parts[2].strip() if len(parts) > 2 else ""
            name_en = parts[3].strip() if len(parts) > 3 else ""
            name = name_uk or name_local or name_en
            if not name:
                continue
            name_clean = name.strip()
            name_low = name_clean.lower()
            if "крим" in name_low and any("крим" in s for s in seen_names):
                continue
            if "севастополь" in name_low and any("севастополь" in s for s in seen_names):
                continue
            if name_low in seen_names:
                continue
            seen_names.add(name_low)
            regions.append((rel_id, name_clean))

    return regions


def get_region_house_count(cur, region_name):
    """Швидко підраховує кількість будинків для вказаного регіону."""
    try:
        cur.execute("""
            SELECT count(*) FROM houses h
            JOIN streets s ON h.street_id = s.id
            WHERE s.region_id = (SELECT id FROM admin_places WHERE level = 4 AND name = ? LIMIT 1);
        """, (region_name,))
        row = cur.fetchone()
        cnt = row[0] if row else 0
        if cnt > 0:
            return cnt

        cur.execute("""
            WITH RECURSIVE subplaces(id) AS (
                SELECT id FROM admin_places WHERE level = 4 AND name = ?
                UNION ALL
                SELECT a.id FROM admin_places a JOIN subplaces sp ON a.parent_id = sp.id
            )
            SELECT count(*) FROM houses h
            JOIN streets s ON h.street_id = s.id
            WHERE s.place_id IN (SELECT id FROM subplaces);
        """, (region_name,))
        row = cur.fetchone()
        return row[0] if row else 0
    except Exception:
        return 0


def fetch_associated_streets(client, area_clause, log_fn, stop_event):
    """
    Завантажує реляції type=associatedStreet для регіону
    та повертає словник: (member_type, member_ref) -> street_name.
    Це відновлює назви вулиць для ~25% будинків, де вулиця прив'язана через реляцію, а не прямий тег.
    """
    q = f"""[out:json][timeout:180];
{area_clause}
relation["type"="associatedStreet"](area.a);
out body;
"""
    raw_json = client.query(q, timeout=190, log_fn=log_fn, stop_event=stop_event, max_cycles=3)
    if not raw_json:
        return {}
    assoc_map = {}
    try:
        data = json.loads(raw_json)
        for el in data.get("elements", []):
            tags = el.get("tags", {})
            s_name = (tags.get("name:uk") or tags.get("name") or tags.get("addr:street") or "").strip()
            if not s_name:
                continue
            for m in el.get("members", []):
                role = m.get("role", "")
                if role in ("house", "address", "building", ""):
                    m_type = m.get("type", "")
                    m_ref = m.get("ref")
                    if m_ref:
                        assoc_map[(m_type, m_ref)] = s_name
                        assoc_map[m_ref] = s_name
    except Exception as e:
        if log_fn:
            log_fn(f"  [-] Помилка парсингу associatedStreet: {e}")
    return assoc_map


def clean_settlement_name_universal(name, short_name=None):
    if short_name:
        return short_name.strip()
    if not name:
        return ""
    n = name.strip()

    # Універсальні адміністративні префікси для будь-якої країни світу
    prefix_patterns = [
        r'^(?:City|Town|Village|Borough|Municipality)\s+of\s+',
        r'^(?:Commune|Ville)\s+de\s+(?:la\s+|l\')?',
        r'^(?:Municipio|Ayuntamiento)\s+de\s+',
        r'^Comune\s+di\s+',
        r'^(?:Stadt|Gemeinde|Markt|Verbandsgemeinde)\s+',
        r'^(?:Miasto\s+i\s+Gmina|Gmina|Miasto)\s+',
        r'^(?:городской\s+округ|муниципальный\s+округ|город|г\.|с\.|пос\.|смт)\s+',
    ]
    for pat in prefix_patterns:
        n = re.sub(pat, '', n, flags=re.I).strip()

    # Універсальні адміністративні суфікси для будь-якої країни світу
    suffix_patterns = [
        r'\s+(?:міська|селищна|сільська)?\s*громада.*$',
        r'\s+(?:городской|муниципальный)\s+округ.*$',
        r'\s+Municipality$',
        r'\s+Borough$',
    ]
    for pat in suffix_patterns:
        n = re.sub(pat, '', n, flags=re.I).strip()

    return n


class SpatialSettlementIndex:
    """
    Високошвидкісний просторовий індекс з кешуванням для миттєвого O(1) визначення
    населеного пункту (місто, селище, село, хутір) за координатами будівлі.
    Працює універсально для будь-якої країни та адміністративного поділу світу.
    """
    def __init__(self, settlements, fallback_reg):
        self.fallback_reg = fallback_reg
        self.grid = {}
        self.cities = []
        self.valid_all = []
        self.cache = {}

        for lat, lon, name, ptype, al in settlements:
            if ptype in ("borough", "district", "subdistrict", "county") or al in ("6", "10"):
                continue

            item = (lat, lon, name, ptype, al)
            if ptype in ("city", "town", "village", "hamlet"):
                self.valid_all.append(item)

            if ptype == "city":
                self.cities.append(item)
            else:
                b_lat = int(round(lat * 10))
                b_lon = int(round(lon * 10))
                for d_lat in (-1, 0, 1):
                    for d_lon in (-1, 0, 1):
                        cell = (b_lat + d_lat, b_lon + d_lon)
                        self.grid.setdefault(cell, []).append(item)

    def resolve(self, pt_lat, pt_lon):
        # Кеш точок із просторовою роздільністю ~100м
        ckey = (round(pt_lat, 3), round(pt_lon, 3))
        if ckey in self.cache:
            return self.cache[ckey]

        scored = []
        # 1. Велике місто (city) у радіусі до 18.0 км
        for s_lat, s_lon, s_name, ptype, al in self.cities:
            dist_km = math.hypot(pt_lat - s_lat, (pt_lon - s_lon) * math.cos(math.radians(pt_lat))) * 111.0
            if dist_km <= 18.0:
                scored.append((0.2 + (dist_km * 0.10), s_name))

        # 2. Локальні населені пункти у сітці
        cell = (int(round(pt_lat * 10)), int(round(pt_lon * 10)))
        candidates = self.grid.get(cell, [])
        for s_lat, s_lon, s_name, ptype, al in candidates:
            dist_km = math.hypot(pt_lat - s_lat, (pt_lon - s_lon) * math.cos(math.radians(pt_lat))) * 111.0
            if ptype == "town" and dist_km <= 7.0:
                scored.append((0.3 + (dist_km * 0.25), s_name))
            elif ptype == "village" and dist_km <= 3.5:
                scored.append((0.4 + (dist_km * 0.35), s_name))
            elif ptype in ("hamlet", "isolated_dwelling") and dist_km <= 1.5:
                scored.append((1.2 + (dist_km * 0.50), s_name))
            elif ptype == "suburb" and dist_km <= 2.5:
                scored.append((2.0 + dist_km, s_name))
            elif (ptype == "municipality" or al in ("7", "8")) and dist_km <= 8.0:
                scored.append((3.0 + (dist_km * 0.4), s_name))

        if scored:
            scored.sort(key=lambda x: x[0])
            res = scored[0][1]
            self.cache[ckey] = res
            return res

        if self.valid_all:
            nearest = min(self.valid_all, key=lambda s: math.hypot(pt_lat - s[0], (pt_lon - s[1]) * math.cos(math.radians(pt_lat))))
            res = nearest[2]
            self.cache[ckey] = res
            return res

        return self.fallback_reg


def fetch_region_settlements(client, area_clause, log_fn, stop_event):
    """
    Універсально завантажує центри всіх населених пунктів (міста, селища, села, хутори)
    та територіальних громад будь-якої країни світу через надшвидкий CSV запит Overpass.
    Гарантує, що жоден будинок не отримає назву області/штату замість реального поселення.
    """
    q = f"""[out:csv(::id, ::type, ::lat, ::lon, "name:uk", "name", "name:en", "place", "admin_level"; false; ";")][timeout:60];
{area_clause}
(
  node["place"~"city|town|village|hamlet|suburb|isolated_dwelling"](area.a);
  relation["place"~"city|town|village|municipality"](area.a);
  relation["boundary"="administrative"]["admin_level"~"7|8|9|10"](area.a);
);
out center;
"""
    raw_csv = client.query(q, timeout=65, log_fn=log_fn, stop_event=stop_event, max_cycles=3)
    settlements = []
    nodes_stems = {}
    if raw_csv:
        for line in raw_csv.splitlines():
            line = line.strip()
            if not line:
                continue
            parts = line.split(";")
            if len(parts) < 8:
                continue
            try:
                lat = float(parts[2])
                lon = float(parts[3])
            except (ValueError, TypeError):
                continue
            name_uk = parts[4].strip()
            name_local = parts[5].strip()
            name_en = parts[6].strip()
            ptype = parts[7].strip()
            al = parts[8].strip() if len(parts) > 8 else ""
            raw_name = name_uk or name_local or name_en
            if not raw_name:
                continue

            clean_name = clean_settlement_name_universal(raw_name)
            if not clean_name:
                continue

            if ptype in ("city", "town", "village"):
                stem = clean_name[:4].lower()
                if stem not in nodes_stems:
                    nodes_stems[stem] = clean_name

            settlements.append((lat, lon, clean_name, ptype, al))

        # Нормалізація прикметникових назв громад до прямої назви поселення
        normalized = []
        for lat, lon, clean_name, ptype, al in settlements:
            if ptype not in ("city", "town", "village") and clean_name[:4].lower() in nodes_stems:
                clean_name = nodes_stems[clean_name[:4].lower()]
            normalized.append((lat, lon, clean_name, ptype, al))
        settlements = normalized

    def make_resolver(lat, lon, fallback_reg):
        nonlocal resolver_instance
        if resolver_instance is None:
            resolver_instance = SpatialSettlementIndex(settlements, fallback_reg)
        return resolver_instance.resolve(lat, lon)

    resolver_instance = None
    return make_resolver


def process_regions_pass(client, country, ingestor, regions_batch, pass_num, max_passes, total_regions, log_fn, stop_event, update_status_fn=None):
    """
    Обробляє пакет регіонів за один прохід.
    Повертає кортеж: (список_необроблених_регіонів, кількість_доданих_адрес).
    """
    failed_regions = []
    pass_added = 0

    for idx, (rel_id, reg_name) in regions_batch:
        if stop_event and stop_event.is_set():
            log_fn("[!] Процес зупинено користувачем.")
            break

        already_cnt = get_region_house_count(ingestor.cur, reg_name)
        if already_cnt > 100:
            if pass_num == 1:
                log_fn(f"[v] Регіон [{idx}/{total_regions}]: {reg_name} — вже є в базі ({already_cnt:,} адрес). Пропуск.")
            continue

        msg = f"Регіон [{idx}/{total_regions}]: {reg_name} (rel {rel_id if rel_id else 'country'})"
        if already_cnt > 0:
            log_fn(f"[+] {msg} — неповний ({already_cnt} адрес). Завантаження...")
        else:
            log_fn(f"[+] {msg} — ВІДСУТНІЙ у базі. Завантаження...")

        if update_status_fn:
            update_status_fn(f"{msg} | Прохід {pass_num}/{max_passes}")

        t0 = time.time()
        if rel_id > 0:
            area_id = 3600000000 + rel_id
            area_clause = f"area({area_id})->.a;"
        else:
            area_clause = f'area["ISO3166-1"="{country.code}"][admin_level=2]->.a;'

        # 1. Завантажуємо зв'язки associatedStreet
        assoc_streets = fetch_associated_streets(client, area_clause, log_fn, stop_event)
        if assoc_streets:
            log_fn(f"  [i] associatedStreet: знайдено {len(assoc_streets):,} зв'язків будинок->вулиця")

        # 2. Завантажуємо контури міст для відновлення назви міста замість області
        resolve_city_fn = fetch_region_settlements(client, area_clause, log_fn, stop_event)

        # 3. Основний запит адресних будівель (з ::id та ::type для точного зіставлення)
        q = f"""[out:csv(::id, ::type, ::lat, ::lon, "addr:city", "addr:town", "addr:village", "addr:hamlet", "addr:suburb", "addr:place", "addr:district", "addr:county", "addr:subdistrict", "addr:municipality", "addr:street", "contact:street", "addr:parentstreet", "is_in:street", "addr:housenumber", "addr:housename", "addr:conscriptionnumber", "is_in:city", "is_in:town", "is_in:village", "is_in:municipality"; false; ";")][timeout:300];
{area_clause}
(
  node["addr:housenumber"](area.a);
  way["addr:housenumber"](area.a);
  relation["addr:housenumber"](area.a);
);
out center;
"""

        tsv_data = client.query(q, timeout=310, log_fn=log_fn, stop_event=stop_event, max_cycles=4)
        if not tsv_data:
            log_fn(f"  [-] Сервери перевантажені для {reg_name} (відкладено на наступний прохід).")
            failed_regions.append((idx, (rel_id, reg_name)))
            continue

        reg_place_id = ingestor.get_or_create_place(ingestor.country_id, reg_name, level=4, osm_id=rel_id)

        count_in_region = 0
        for line in tsv_data.splitlines():
            line = line.strip()
            if not line:
                continue
            parts = line.split(";") if ";" in line else line.split("\t")
            if len(parts) < 6:
                continue
            osm_id_str = parts[0].strip()
            osm_id = int(osm_id_str) if osm_id_str.isdigit() else 0
            osm_type = parts[1].strip()
            try:
                lat = float(parts[2])
                lon = float(parts[3])
            except (ValueError, TypeError):
                continue

            row_city = parts[4].strip() if len(parts) > 4 else ""
            row_town = parts[5].strip() if len(parts) > 5 else ""
            row_village = parts[6].strip() if len(parts) > 6 else ""
            row_hamlet = parts[7].strip() if len(parts) > 7 else ""
            row_suburb = parts[8].strip() if len(parts) > 8 else ""
            row_place = parts[9].strip() if len(parts) > 9 else ""

            # Райони / округи (district / county)
            district = (parts[10].strip() if len(parts) > 10 else "") or (parts[11].strip() if len(parts) > 11 else "")
            # Громади / муніципалітети (subdistrict / municipality)
            subdistrict = (parts[12].strip() if len(parts) > 12 else "") or (parts[13].strip() if len(parts) > 13 else "")

            row_street = parts[14].strip() if len(parts) > 14 else ""
            row_contact_street = parts[15].strip() if len(parts) > 15 else ""
            row_parent_street = parts[16].strip() if len(parts) > 16 else ""
            row_is_in_street = parts[17].strip() if len(parts) > 17 else ""

            housenumber = (parts[18].strip() if len(parts) > 18 else "") or (parts[19].strip() if len(parts) > 19 else "") or (parts[20].strip() if len(parts) > 20 else "")

            is_in_city = parts[21].strip() if len(parts) > 21 else ""
            is_in_town = parts[22].strip() if len(parts) > 22 else ""
            is_in_village = parts[23].strip() if len(parts) > 23 else ""
            is_in_muni = parts[24].strip() if len(parts) > 24 else ""

            # Відновлення вулиці через прямі теги, контактні теги та associatedStreet
            street = row_street or row_contact_street or row_parent_street or row_is_in_street
            if not street and osm_id:
                street = assoc_streets.get((osm_type, osm_id)) or assoc_streets.get(osm_id) or ""

            # У сільських регіонах addr:place часто слугує назвою замість вулиці
            if not street and row_place and housenumber:
                street = row_place

            if not housenumber and not street:
                continue

            # Відновлення поселення: якщо явний тег відсутній, визначаємо реальне місто/село за просторовим індексом
            city = row_city or row_town or row_village or row_hamlet or row_suburb or is_in_city or is_in_town or is_in_village or is_in_muni or row_place
            if not city:
                city = resolve_city_fn(lat, lon, reg_name)

            cur_parent = reg_place_id
            if district:
                cur_parent = ingestor.get_or_create_place(cur_parent, district, level=6)
            if subdistrict:
                cur_parent = ingestor.get_or_create_place(cur_parent, subdistrict, level=7)

            city_place_id = ingestor.get_or_create_place(cur_parent, city, level=8)
            street_id = ingestor.get_or_create_street(city_place_id, street, reg_name, city, reg_place_id)

            ingestor.add_house(street_id, housenumber, lat, lon)
            count_in_region += 1

        ingestor.flush()
        pass_added += count_in_region
        if count_in_region > 0:
            log_fn(f"  + {reg_name}: збережено {count_in_region:,} адрес за {time.time()-t0:.2f}с")
        else:
            log_fn(f"  [i] {reg_name}: адресних точок у OSM не знайдено.")

    return failed_regions, pass_added


def fetch_and_store_overpass_addresses(client, country, ingestor, log_fn, stop_event, update_status_fn=None):
    """
    Почергово завантажує адреси по регіонах країни через Overpass API.
    Невдалі регіони збираються у список і повторно передаються у функцію обробки (до 5 проходів).
    """
    regions = fetch_overpass_regions(client, country.code, log_fn, stop_event)
    if not regions:
        log_fn(f"[Overpass] Окремі регіони admin_level=4 не знайдені. Завантаження країни {country.name_uk} цілком...")
        regions = [(0, country.name_uk)]

    total_regions = len(regions)
    log_fn(f"[Overpass] Знайдено {total_regions} адміністративних регіонів для обробки.")

    max_passes = 5
    total_added = 0
    pending_regions = list(enumerate(regions, start=1))

    for pass_num in range(1, max_passes + 1):
        if not pending_regions:
            break
        if stop_event and stop_event.is_set():
            break

        if pass_num > 1:
            log_fn(f"\n[Overpass] --- Довантаження {len(pending_regions)} пропущених регіонів (прохід {pass_num}/{max_passes}) ---")
            for _ in range(6):
                if stop_event and stop_event.is_set():
                    break
                time.sleep(0.5)

        pending_regions, added = process_regions_pass(
            client=client,
            country=country,
            ingestor=ingestor,
            regions_batch=pending_regions,
            pass_num=pass_num,
            max_passes=max_passes,
            total_regions=total_regions,
            log_fn=log_fn,
            stop_event=stop_event,
            update_status_fn=update_status_fn
        )
        total_added += added
        if update_status_fn:
            update_status_fn(f"Прохід {pass_num}/{max_passes} завершено | Додано: {total_added:,}")

    return total_added


def extract_addresses_from_poi(poi_path, country, ingestor, log_fn):
    """Витягує адреси з Mapsforge POI файлу та імпортує в нормалізовану базу."""
    if not os.path.exists(poi_path) or os.path.getsize(poi_path) < 1024:
        log_fn(f"[POI] Файл {os.path.basename(poi_path)} не знайдено.")
        return 0

    log_fn(f"[POI] Сканування адрес у {os.path.basename(poi_path)}...")
    t0 = time.time()
    conn = sqlite3.connect(poi_path)
    cur = conn.cursor()
    try:
        cur.execute("""
            SELECT p.id, i.lat, i.lon, p.data 
            FROM poi_data p 
            JOIN poi_index i ON p.id = i.id 
            WHERE p.data LIKE '%addr:%' OR p.data LIKE '%street%'
        """)
        rows = cur.fetchall()
    except Exception as e:
        log_fn(f"[POI] Помилка читання: {e}")
        conn.close()
        return 0
    conn.close()

    count = 0
    for pid, lat, lon, data in rows:
        tags = {}
        for line in data.split("\r"):
            if "=" in line:
                k, v = line.split("=", 1)
                tags[k.strip().lower()] = v.strip()

        street = tags.get("addr:street", "")
        house = tags.get("addr:housenumber", "")
        city = tags.get("addr:city") or tags.get("addr:town") or tags.get("addr:village") or tags.get("addr:place", "")
        if not city:
            city = country.name_uk

        if street or house:
            city_id = ingestor.get_or_create_place(ingestor.country_id, city, level=8)
            street_id = ingestor.get_or_create_street(city_id, street, country.name_uk, city)
            ingestor.add_house(street_id, house, lat, lon)
            count += 1

    ingestor.flush()
    log_fn(f"[POI] Збережено {count:,} адрес за {time.time()-t0:.2f} сек.")
    return count


def parse_shapefile_zip(raw_bytes, country, ingestor, default_city, log_fn):
    """Парсить ESRI Shapefile (.shp + .dbf всередині zip) без зовнішніх бібліотек."""
    try:
        zf = zipfile.ZipFile(io.BytesIO(raw_bytes))
        names = zf.namelist()
        dbf_name = next((n for n in names if n.lower().endswith('.dbf')), None)
        shp_name = next((n for n in names if n.lower().endswith('.shp')), None)
        cpg_name = next((n for n in names if n.lower().endswith('.cpg')), None)
        if not dbf_name or not shp_name:
            return 0

        encoding = "utf-8"
        if cpg_name:
            try:
                cpg_raw = zf.read(cpg_name).decode("ascii", errors="ignore").strip().lower()
                if "1251" in cpg_raw:
                    encoding = "cp1251"
            except Exception:
                pass
        if country.code in ("UA", "RU", "BY") and encoding != "cp1251":
            encoding = "cp1251"

        dbf = zf.read(dbf_name)
        shp = zf.read(shp_name)

        num_records, header_len, record_len = struct.unpack_from('<IHH', dbf, 4)

        fields = []
        pos = 32
        while pos < header_len - 1 and dbf[pos] != 0x0D:
            fname = dbf[pos:pos+11].split(b'\x00')[0].decode('ascii', errors='ignore').strip()
            flen = dbf[pos+16]
            fields.append((fname, flen))
            pos += 32

        num_idx = -1
        street_idx = -1
        city_idx = -1

        for i, (fn, _) in enumerate(fields):
            fn_l = fn.lower()
            if fn_l in ("number", "housenumber", "house", "locatordesignator") or "num" in fn_l:
                num_idx = i
            elif fn_l == "addressua":
                street_idx = i
            elif street_idx < 0 and (fn_l in ("street", "thoroughfare", "addr", "address") or "addr" in fn_l or "street" in fn_l):
                street_idx = i
            elif fn_l in ("city", "town", "settlement", "adminunitl2"):
                city_idx = i

        pos_dbf = header_len
        pos_shp = 100
        count = 0

        for _ in range(num_records):
            fvals = []
            fpos = pos_dbf + 1
            for fname, flen in fields:
                val = dbf[fpos:fpos+flen].decode(encoding, errors="replace").strip()
                fvals.append(val)
                fpos += flen
            pos_dbf += record_len

            rec_num, content_len = struct.unpack_from('>ii', shp, pos_shp)
            stype = struct.unpack_from('<i', shp, pos_shp + 8)[0]
            if stype == 5:
                minX, minY, maxX, maxY = struct.unpack_from('<dddd', shp, pos_shp + 12)
                lon = (minX + maxX) / 2.0
                lat = (minY + maxY) / 2.0
            elif stype == 1:
                lon, lat = struct.unpack_from('<dd', shp, pos_shp + 12)
            else:
                lon, lat = 0.0, 0.0
            pos_shp += 8 + content_len * 2

            house = fvals[num_idx] if num_idx >= 0 else ""
            street = fvals[street_idx] if street_idx >= 0 else ""
            city = fvals[city_idx] if city_idx >= 0 and fvals[city_idx] else default_city

            if street or house:
                city_id = ingestor.get_or_create_place(ingestor.country_id, city, level=8)
                street_id = ingestor.get_or_create_street(city_id, street, country.name_uk, city)
                ingestor.add_house(street_id, house, lat, lon)
                count += 1

        ingestor.flush()
        log_fn(f"  Розпарсено та збережено {count:,} об'єктів з Shapefile...")
        return count
    except Exception as e:
        log_fn(f"  [-] Помилка парсингу Shapefile: {e}")
        return 0


def parse_geojson_features(features, country, ingestor, default_city):
    """Парсить список features GeoJSON."""
    count = 0
    for f in features:
        geom = f.get("geometry", {})
        coords = geom.get("coordinates")
        if not coords or len(coords) < 2:
            continue
        lon, lat = float(coords[0]), float(coords[1])
        props = f.get("properties", {})

        street = props.get("street") or props.get("thoroughfare") or props.get("addr:street") or ""
        house = props.get("number") or props.get("locatordesignator") or props.get("addr:housenumber") or ""
        city = props.get("city") or props.get("adminunitl2") or props.get("addr:city") or default_city

        if str(city).lower() == "null": city = default_city
        if str(street).lower() == "null": street = ""
        if str(house).lower() == "null": house = ""

        if street or house:
            city_id = ingestor.get_or_create_place(ingestor.country_id, city, level=8)
            street_id = ingestor.get_or_create_street(city_id, street, country.name_uk, city)
            ingestor.add_house(street_id, house, lat, lon)
            count += 1

    ingestor.flush()
    return count


def parse_address_payload(raw_bytes, url, country, ingestor, source_name, log_fn):
    """Універсальний парсер контенту (ZIP, GeoJSON, CSV)."""
    default_city = ""
    if "dnipropetrovsk" in source_name.lower():
        default_city = "Дніпро"
    elif "lviv" in source_name.lower():
        default_city = "Львів"
    elif "kharkiv" in source_name.lower():
        default_city = "Харків"
    elif "/" in source_name:
        default_city = source_name.split("/")[-1].replace("city_of_", "").replace("_", " ").title()

    if raw_bytes.startswith(b"PK\x03\x04"):
        try:
            zf = zipfile.ZipFile(io.BytesIO(raw_bytes))
            names = zf.namelist()
            if any(n.lower().endswith(".shp") for n in names):
                return parse_shapefile_zip(raw_bytes, country, ingestor, default_city, log_fn)

            gj_name = next((n for n in names if n.lower().endswith((".geojson", ".json"))), None)
            if gj_name:
                text = zf.read(gj_name).decode("utf-8", errors="replace")
                gj = json.loads(text)
                features = gj.get("features", [])
                log_fn(f"  Розпарсено {len(features)} об'єктів з {gj_name} (ZIP)...")
                return parse_geojson_features(features, country, ingestor, default_city)

            csv_name = next((n for n in names if n.lower().endswith(".csv")), None)
            if csv_name:
                text = zf.read(csv_name).decode("utf-8", errors="replace")
                reader = csv.DictReader(io.StringIO(text))
                count = 0
                for row in reader:
                    lon = float(row.get("LON") or row.get("lon") or row.get("x") or 0.0)
                    lat = float(row.get("LAT") or row.get("lat") or row.get("y") or 0.0)
                    street = row.get("STREET") or row.get("street") or ""
                    house = row.get("NUMBER") or row.get("number") or ""
                    city = row.get("CITY") or row.get("city") or default_city
                    if street or house:
                        city_id = ingestor.get_or_create_place(ingestor.country_id, city, level=8)
                        street_id = ingestor.get_or_create_street(city_id, street, country.name_uk, city)
                        ingestor.add_house(street_id, house, lat, lon)
                        count += 1
                ingestor.flush()
                log_fn(f"  Розпарсено та збережено {count:,} об'єктів з {csv_name} (ZIP)...")
                return count
        except Exception as e:
            log_fn(f"  [-] Помилка розпаковки ZIP: {e}")
            return 0

    text = raw_bytes.decode("utf-8", errors="replace").strip()
    if text.startswith("{"):
        try:
            gj = json.loads(text)
            features = gj.get("features", [])
            log_fn(f"  Розпарсено {len(features)} об'єктів (GeoJSON)...")
            return parse_geojson_features(features, country, ingestor, default_city)
        except Exception as e:
            log_fn(f"  [-] Помилка парсингу JSON: {e}")
            return 0

    if "\n" in text and ("," in text or ";" in text):
        try:
            reader = csv.DictReader(io.StringIO(text))
            count = 0
            for row in reader:
                lon = float(row.get("LON") or row.get("lon") or row.get("x") or 0.0)
                lat = float(row.get("LAT") or row.get("lat") or row.get("y") or 0.0)
                street = row.get("STREET") or row.get("street") or ""
                house = row.get("NUMBER") or row.get("number") or ""
                city = row.get("CITY") or row.get("city") or default_city
                if street or house:
                    city_id = ingestor.get_or_create_place(ingestor.country_id, city, level=8)
                    street_id = ingestor.get_or_create_street(city_id, street, country.name_uk, city)
                    ingestor.add_house(street_id, house, lat, lon)
                    count += 1
            ingestor.flush()
            log_fn(f"  Розпарсено та збережено {count:,} об'єктів (CSV)...")
            return count
        except Exception as e:
            log_fn(f"  [-] Помилка парсингу CSV: {e}")
            return 0

    log_fn(f"  [-] Невідомий формат файлу ({url.split('/')[-1]}). Пропущено.")
    return 0


def fetch_openaddresses_datasets(country, ingestor, log_fn):
    """Шукає та завантажує дані з OpenAddresses для вказаної країни."""
    log_fn(f"[OpenAddresses] Пошук джерел для {country.code}...")
    url = "https://batch.openaddresses.io/api/data"
    req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    try:
        with urllib.request.urlopen(req, timeout=15) as resp:
            data = json.loads(resp.read().decode("utf-8"))
    except Exception as e:
        log_fn(f"[OpenAddresses] Помилка зв'язку з batch.openaddresses.io: {e}")
        return 0

    prefix = country.code.lower() + "/"
    datasets = [d for d in data if d.get("source", "").lower().startswith(prefix) and d.get("layer") == "addresses"]

    log_fn(f"[OpenAddresses] Знайдено {len(datasets)} адресних наборів для {country.code}.")
    total_added = 0

    for d in datasets:
        source_name = d.get("source")
        job_id = d.get("job")
        if not job_id:
            continue
        log_fn(f"[OpenAddresses] Обробка {source_name} (Job {job_id})...")

        raw_url = f"https://batch.openaddresses.io/api/job/{job_id}/raw"
        try:
            req_raw = urllib.request.Request(raw_url, headers={"User-Agent": USER_AGENT})
            with urllib.request.urlopen(req_raw, timeout=15) as r_resp:
                raw_json = json.loads(r_resp.read().decode("utf-8"))

            addr_layers = raw_json.get("layers", {}).get("addresses", [])
            for al in addr_layers:
                data_url = al.get("data")
                if not data_url or not data_url.startswith("http"):
                    continue

                if "/arcgis/rest/" in data_url or "/MapServer" in data_url:
                    log_fn(f"  [i] Пропущено ArcGIS REST ({data_url.split('/')[-1]}): потребує пагінації API")
                    continue

                log_fn(f"  Завантаження {data_url.split('/')[-1]}...")
                dl_req = urllib.request.Request(data_url, headers={"User-Agent": USER_AGENT})
                with urllib.request.urlopen(dl_req, timeout=30) as d_resp:
                    payload = d_resp.read()

                cnt = parse_address_payload(payload, data_url, country, ingestor, source_name, log_fn)
                total_added += cnt
        except urllib.error.HTTPError as e:
            err_msg = ""
            try:
                err_msg = OverpassClient._clean_server_response(e.read())
            except Exception:
                pass
            msg_str = f': "{err_msg}"' if err_msg else ""
            log_fn(f"  [-] HTTP {e.code} [Відповідь сервера{msg_str}] під час завантаження {source_name}")
        except Exception as e:
            log_fn(f"  [-] Помилка завантаження джерела ({type(e).__name__}: {e})")

    return total_added


class AddressApp(tk.Tk):
    def __init__(self):
        super().__init__()
        self.title("Mushroom Offline Address Search & Universal Database Tool (addr.db)")
        self.geometry("1100x720")
        self.minsize(950, 600)

        self.countries = load_countries()
        self.code_to_country = {c.code: c for c in self.countries}
        self.stop_event = threading.Event()
        self.overpass_client = OverpassClient()

        self.setup_ui()
        self.refresh_db_list()

    def setup_ui(self):
        # Панель налаштувань завантаження
        top_frame = ttk.LabelFrame(self, text="Завантаження та генерація офлайн-бази адрес (Overpass API + POI)", padding=10)
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

        # Панель пошуку (Глобальний пошук по всіх країнах)
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

        # Лог консоль
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

            cur.execute("SELECT code, name FROM admin_places WHERE level = 2 ORDER BY name;")
            countries = cur.fetchall()

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
                self.log(f"--- Початок обробки для {country} (Overpass + POI -> {DB_NAME}) ---")
                conn = init_db(DB_PATH)
                ingestor = AddressIngestor(conn, country.code, country.name_uk)

                total_added = 0

                # 1. Overpass API (OSM Регіони)
                cnt = fetch_and_store_overpass_addresses(
                    client=self.overpass_client,
                    country=country,
                    ingestor=ingestor,
                    log_fn=self.log,
                    stop_event=self.stop_event,
                    update_status_fn=lambda txt: self.after(0, lambda: self.status_lbl.configure(text=txt))
                )
                total_added += cnt

                # 2. POI (Офлайн .poi)
                if not self.stop_event.is_set():
                    poi_name = f"{country.slug}.poi"
                    poi_path = os.path.join(POI_DIR, poi_name)
                    if not os.path.exists(poi_path):
                        poi_url = f"https://download.mapsforge.org/pois/{country.continent}/{poi_name}"
                        self.log(f"Завантаження {poi_name} з mapsforge...")
                        try:
                            req = urllib.request.Request(poi_url, headers={"User-Agent": USER_AGENT})
                            with urllib.request.urlopen(req, timeout=30) as r, open(poi_path, "wb") as f_out:
                                f_out.write(r.read())
                            self.log(f"Завантажено: {poi_name}")
                        except Exception as e:
                            self.log(f"POI для {country.name_uk} недоступний: {e}")

                    if os.path.exists(poi_path):
                        cnt_poi = extract_addresses_from_poi(poi_path, country, ingestor, self.log)
                        total_added += cnt_poi

                # Оптимізація індексу FTS5 та дефрагментація (VACUUM)
                if total_added > 0:
                    self.log("Оптимізація індексу FTS5 (optimize) та дефрагментація (VACUUM)...")
                    t_fts = time.time()
                    conn.execute("INSERT INTO search_fts(search_fts) VALUES('optimize');")
                    conn.commit()
                    conn.execute("VACUUM;")
                    conn.commit()
                    sz_mb = os.path.getsize(DB_PATH) / (1024 * 1024)
                    self.log(f"Базу оптимізовано за {time.time()-t_fts:.2f}с. Фінальний розмір {DB_NAME}: {sz_mb:.2f} МБ")
                else:
                    self.log("Адрес для запису не знайдено або завантаження було зупинено.")

                conn.close()
                self.after(0, self.refresh_db_list)
                msg_txt = f"Країну {country.name_uk} оброблено!\nВсього адрес додано: {total_added:,}"
                if self.stop_event.is_set():
                    msg_txt = f"Завантаження зупинено.\nЗбережено в {DB_NAME}: {total_added:,} адрес"
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

        # Розбір запиту: очищуємо лапки, коми, крапки та виділяємо номер будинку й слова міста/вулиці
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
                # Регістронезалежний та гнучкий пошук номерів будинків з літерами (68-Б, 68-б, 68Б, 68б, 68 Б)
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
