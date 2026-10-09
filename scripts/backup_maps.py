#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Mushroom Offline Map & Navigation Backup Utility
Завантаження та оновлення карт (.map), точок інтересу (.poi) та навігаційних файлів (.rd5)
у каталог data скрипта:
    data/maps/
    data/poi/
    data/navigation/
"""

import os
import sys
import re
import math
import time
import argparse
from datetime import datetime
from email.utils import parsedate_to_datetime
import urllib.request
import urllib.error
import urllib.parse
from concurrent.futures import ThreadPoolExecutor, as_completed

# Забезпечення коректного кодування виводу у Windows-консолі
try:
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    if hasattr(sys.stderr, "reconfigure"):
        sys.stderr.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

USER_AGENT = "Mushroom-Backup/2.0 (Offline Map Sync Tool; https://github.com/OlegSkalGit/mushroom)"
BROUTER_BASE_URL = "https://brouter.de/brouter/segments4"

BASE_DIR = os.path.dirname(os.path.abspath(__file__))
DATA_DIR = os.path.join(BASE_DIR, "data")
MAPS_DIR = os.path.join(DATA_DIR, "maps")
POI_DIR = os.path.join(DATA_DIR, "poi")
NAV_DIR = os.path.join(DATA_DIR, "navigation")

class MapCountry:
    def __init__(self, code, name, name_uk, continent, slug, min_lat, max_lat, min_lon, max_lon):
        self.code = code
        self.name = name
        self.name_uk = name_uk
        self.continent = continent
        self.slug = slug
        self.map_file = f"{slug}.map"
        self.poi_file = f"{slug}.poi"
        self.map_url = f"https://download.mapsforge.org/maps/v5/{continent}/{slug}.map"
        self.poi_url = f"https://download.mapsforge.org/pois/{continent}/{slug}.poi"
        self.min_lat = float(min_lat)
        self.max_lat = float(max_lat)
        self.min_lon = float(min_lon)
        self.max_lon = float(max_lon)

    def get_rd5_segments(self):
        segments = []
        lat_min = int(math.floor(self.min_lat / 5.0) * 5.0)
        lat_max = int(math.floor(self.max_lat / 5.0) * 5.0)
        lon_min = int(math.floor(self.min_lon / 5.0) * 5.0)
        lon_max = int(math.floor(self.max_lon / 5.0) * 5.0)
        for lat in range(lat_min, lat_max + 1, 5):
            lat_str = f"N{lat}" if lat >= 0 else f"S{abs(lat)}"
            for lon in range(lon_min, lon_max + 1, 5):
                lon_str = f"E{lon}" if lon >= 0 else f"W{abs(lon)}"
                segments.append(f"{lon_str}_{lat_str}.rd5")
        return segments


def load_countries_from_project():
    """Спроба динамічно зчитати список країн з MapDownloadManager.kt проекту."""
    candidate_paths = [
        os.path.join(BASE_DIR, "..", "app", "src", "main", "java", "com", "olegskal", "mushroom", "map", "MapDownloadManager.kt"),
        os.path.join(BASE_DIR, "app", "src", "main", "java", "com", "olegskal", "mushroom", "map", "MapDownloadManager.kt"),
    ]
    kt_path = None
    for p in candidate_paths:
        if os.path.isfile(p):
            kt_path = os.path.abspath(p)
            break

    countries = []
    if kt_path:
        try:
            with open(kt_path, "r", encoding="utf-8") as f:
                content = f.read()
            pattern = re.compile(
                r'def\(\s*"([^"]+)",\s*"([^"]+)",\s*"([^"]+)",\s*"([^"]+)",\s*"([^"]+)",\s*([-\d.]+),\s*([-\d.]+),\s*([-\d.]+),\s*([-\d.]+)\s*\)'
            )
            for m in pattern.finditer(content):
                code, name, name_uk, continent, slug, min_lat, max_lat, min_lon, max_lon = m.groups()
                countries.append(MapCountry(code, name, name_uk, continent, slug, min_lat, max_lat, min_lon, max_lon))
        except Exception as e:
            print(f"[!] Попередження: не вдалося зчитати MapDownloadManager.kt: {e}")

    if not countries:
        # Резервний список основних країн
        fallback = [
            ("UA", "Ukraine", "Україна", "europe", "ukraine", 44.3, 52.4, 22.1, 40.2),
            ("PL", "Poland", "Польща", "europe", "poland", 49.0, 54.9, 14.1, 24.2),
            ("SK", "Slovakia", "Словаччина", "europe", "slovakia", 47.7, 49.7, 16.8, 22.6),
            ("RO", "Romania", "Румунія", "europe", "romania", 43.6, 48.3, 20.2, 29.8),
            ("HU", "Hungary", "Угорщина", "europe", "hungary", 45.7, 48.6, 16.1, 22.9),
            ("MD", "Moldova", "Молдова", "europe", "moldova", 45.4, 48.5, 26.6, 30.2),
            ("CZ", "Czech Republic", "Чехія", "europe", "czech-republic", 48.5, 51.1, 12.0, 18.9),
            ("DE", "Germany", "Німеччина", "europe", "germany", 47.2, 55.1, 5.8, 15.1),
        ]
        for item in fallback:
            countries.append(MapCountry(*item))

    return countries


class SmartRedirectHandler(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        new_req = super().redirect_request(req, fp, code, msg, headers, newurl)
        if new_req:
            new_req.headers["User-agent"] = USER_AGENT
        return new_req


def get_remote_file_info(url):
    """
    Виконує HEAD запит і повертає (size_bytes, last_modified_epoch).
    """
    opener = urllib.request.build_opener(SmartRedirectHandler())
    req = urllib.request.Request(url, method="HEAD")
    req.add_header("User-Agent", USER_AGENT)
    try:
        with opener.open(req, timeout=15) as resp:
            content_length = resp.headers.get("Content-Length")
            last_modified = resp.headers.get("Last-Modified")

            size = int(content_length) if content_length and content_length.isdigit() else None
            mtime = None
            if last_modified:
                try:
                    dt = parsedate_to_datetime(last_modified)
                    mtime = dt.timestamp()
                except Exception:
                    pass
            return size, mtime
    except urllib.error.HTTPError as e:
        if e.code == 404:
            return None, None
        # Деякі сервери блокують HEAD запит, спробуємо GET з Range
        try:
            req_get = urllib.request.Request(url, headers={"User-Agent": USER_AGENT, "Range": "bytes=0-0"})
            with opener.open(req_get, timeout=15) as resp:
                cr = resp.headers.get("Content-Range")
                size = None
                if cr and "/" in cr:
                    total_str = cr.split("/")[-1].strip()
                    if total_str.isdigit():
                        size = int(total_str)
                last_modified = resp.headers.get("Last-Modified")
                mtime = None
                if last_modified:
                    try:
                        mtime = parsedate_to_datetime(last_modified).timestamp()
                    except Exception:
                        pass
                return size, mtime
        except Exception:
            return None, None
    except Exception:
        return None, None


def check_file_status(dest_path, remote_size, remote_mtime):
    """
    Повертає (status_string, needs_download_bool)
    """
    if not os.path.exists(dest_path):
        return "НОВИЙ (відсутній)", True

    local_size = os.path.getsize(dest_path)
    if local_size == 0:
        return "ПОШКОДЖЕНИЙ (0 байт)", True

    if remote_size is not None and local_size != remote_size:
        return f"ОНОВЛЕННЯ (розмір: {local_size / 1024 / 1024:.1f} MB -> {remote_size / 1024 / 1024:.1f} MB)", True

    if remote_mtime is not None:
        local_mtime = os.path.getmtime(dest_path)
        # Якщо серверний файл новіший більш ніж на 60 секунд
        if remote_mtime > local_mtime + 60:
            dt_remote = datetime.fromtimestamp(remote_mtime).strftime("%Y-%m-%d %H:%M")
            return f"ОНОВЛЕННЯ (серверна дата: {dt_remote})", True

    return "АКТУАЛЬНИЙ", False


def format_bytes(bytes_num):
    if bytes_num is None:
        return "?? MB"
    mb = bytes_num / (1024 * 1024)
    if mb >= 1024:
        return f"{mb / 1024:.2f} GB"
    return f"{mb:.1f} MB"


def download_file(url, dest_path, description=""):
    """
    Завантажує файл з відображенням прогресу.
    Завантаження йде у тимчасовий .tmp файл, потім атомарно перейменовується.
    """
    tmp_path = dest_path + ".tmp"
    opener = urllib.request.build_opener(SmartRedirectHandler())
    req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})

    try:
        with opener.open(req, timeout=30) as resp:
            total_size = resp.headers.get("Content-Length")
            total_bytes = int(total_size) if total_size and total_size.isdigit() else None
            last_modified = resp.headers.get("Last-Modified")
            mtime = None
            if last_modified:
                try:
                    mtime = parsedate_to_datetime(last_modified).timestamp()
                except Exception:
                    pass

            downloaded = 0
            start_time = time.time()
            last_print = 0

            with open(tmp_path, "wb") as f_out:
                chunk_size = 128 * 1024
                while True:
                    chunk = resp.read(chunk_size)
                    if not chunk:
                        break
                    f_out.write(chunk)
                    downloaded += len(chunk)

                    now = time.time()
                    if now - last_print > 0.2:
                        last_print = now
                        elapsed = max(0.001, now - start_time)
                        speed = downloaded / elapsed / (1024 * 1024)
                        if total_bytes:
                            pct = (downloaded / total_bytes) * 100
                            mb_cur = downloaded / (1024 * 1024)
                            mb_tot = total_bytes / (1024 * 1024)
                            sys.stdout.write(f"\r  [{description}] {pct:5.1f}% ({mb_cur:.1f}/{mb_tot:.1f} MB) | {speed:.2f} MB/s ")
                        else:
                            mb_cur = downloaded / (1024 * 1024)
                            sys.stdout.write(f"\r  [{description}] {mb_cur:.1f} MB | {speed:.2f} MB/s ")
                        sys.stdout.flush()

        if total_bytes and os.path.getsize(tmp_path) != total_bytes:
            if os.path.exists(tmp_path):
                os.remove(tmp_path)
            return False, "Невідповідність розміру завантаженого файлу"

        if os.path.exists(dest_path):
            os.remove(dest_path)
        os.rename(tmp_path, dest_path)

        if mtime is not None:
            os.utime(dest_path, (mtime, mtime))

        sys.stdout.write("\r" + " " * 90 + "\r")
        sys.stdout.flush()
        return True, "Успішно завантажено"

    except Exception as e:
        if os.path.exists(tmp_path):
            try:
                os.remove(tmp_path)
            except Exception:
                pass
        return False, str(e)


def main():
    parser = argparse.ArgumentParser(
        description="Mushroom Map & Navigation Backup Utility — завантаження та оновлення бекапу карт, POI та BRouter rd5."
    )
    parser.add_argument("-a", "--all", action="store_true", help="Завантажити/оновити всі доступні країни")
    parser.add_argument("-c", "--country", type=str, default=None, help="Код(и) країн через кому (наприклад: UA або UA,PL,SK)")
    parser.add_argument("--check-only", action="store_true", help="Лише перевірити наявність оновлень без скачування")
    parser.add_argument("--skip-maps", action="store_true", help="Пропустити карти (.map)")
    parser.add_argument("--skip-poi", action="store_true", help="Пропустити POI (.poi)")
    parser.add_argument("--skip-nav", action="store_true", help="Пропустити навігацію BRouter (.rd5)")
    args = parser.parse_args()

    # Створюємо директорії бекапу
    os.makedirs(MAPS_DIR, exist_ok=True)
    os.makedirs(POI_DIR, exist_ok=True)
    os.makedirs(NAV_DIR, exist_ok=True)

    countries = load_countries_from_project()
    code_map = {c.code.upper(): c for c in countries}

    print("=" * 80)
    print(" [MUSHROOM] Офлайн-карти, POI та навігаційні файли BRouter")
    print(f" Каталог бекапу: {DATA_DIR}")
    print(f"   * Карти:      {MAPS_DIR}")
    print(f"   * POI:        {POI_DIR}")
    print(f"   * Навігація:  {NAV_DIR}")
    print(f" Всього доступно країн у базі: {len(countries)}")
    print("=" * 80)

    selected_countries = []

    if args.all:
        selected_countries = countries
    elif args.country:
        codes = [x.strip().upper() for x in args.country.split(",") if x.strip()]
        for code in codes:
            if code in code_map:
                selected_countries.append(code_map[code])
            else:
                print(f"[!] Невідомий код країни: {code}")
        if not selected_countries:
            print("[!] Жодної валідної країни не знайдено.")
            return
    else:
        # Інтерактивний вибір якщо запущено без параметрів
        print("\nОберіть режим завантаження бекапу:")
        print(" [1] Україна (UA) — Рекомендовано")
        print(" [2] Україна + сусідні країни (UA, PL, SK, HU, RO, MD)")
        print(f" [3] Усі доступні країни ({len(countries)} країн)")
        print(" [4] Вказати коди країн вручну (наприклад: UA,PL,DE)")
        print(" [0] Вихід")

        choice = input("\nВаш вибір [1]: ").strip()
        if not choice:
            choice = "1"

        if choice == "1":
            selected_countries = [code_map["UA"]] if "UA" in code_map else countries[:1]
        elif choice == "2":
            neighbors = ["UA", "PL", "SK", "HU", "RO", "MD"]
            selected_countries = [code_map[c] for c in neighbors if c in code_map]
        elif choice == "3":
            selected_countries = countries
        elif choice == "4":
            raw = input("Введіть коди через кому: ").strip().upper()
            codes = [x.strip() for x in raw.split(",") if x.strip()]
            selected_countries = [code_map[c] for c in codes if c in code_map]
            if not selected_countries:
                print("[!] Не обрано жодної країни.")
                return
        else:
            print("Вихід.")
            return

    print(f"\n Обрано країн для обробки: {len(selected_countries)}")
    for c in selected_countries:
        print(f"  * [{c.code}] {c.name_uk} ({c.name})")

    # Збираємо унікальний перелік файлів для завантаження
    tasks = []
    rd5_set = set()

    for c in selected_countries:
        if not args.skip_maps:
            tasks.append({
                "type": "MAP",
                "name": c.map_file,
                "url": c.map_url,
                "dest": os.path.join(MAPS_DIR, c.map_file),
                "desc": f"{c.code} Map"
            })
        if not args.skip_poi:
            tasks.append({
                "type": "POI",
                "name": c.poi_file,
                "url": c.poi_url,
                "dest": os.path.join(POI_DIR, c.poi_file),
                "desc": f"{c.code} POI"
            })
        if not args.skip_nav:
            for seg in c.get_rd5_segments():
                if seg not in rd5_set:
                    rd5_set.add(seg)
                    tasks.append({
                        "type": "NAV",
                        "name": seg,
                        "url": f"{BROUTER_BASE_URL}/{seg}",
                        "dest": os.path.join(NAV_DIR, seg),
                        "desc": f"RD5 {seg}"
                    })

    print(f"\nЗагальна черга перевірки: {len(tasks)} файлів "
          f"({sum(1 for t in tasks if t['type'] == 'MAP')} карт, "
          f"{sum(1 for t in tasks if t['type'] == 'POI')} POI, "
          f"{sum(1 for t in tasks if t['type'] == 'NAV')} навігаційних сегментів .rd5)\n")

    # Перевірка статусу та завантаження
    stats = {"up_to_date": 0, "downloaded": 0, "failed": 0, "bytes_downloaded": 0}

    for idx, t in enumerate(tasks, 1):
        filename = t["name"]
        dest = t["dest"]
        url = t["url"]
        desc = f"[{idx}/{len(tasks)}] {t['desc']}"

        print(f"-> {desc}: {filename} ... ", end="", flush=True)

        rem_size, rem_mtime = get_remote_file_info(url)
        if rem_size is None and rem_mtime is None and not os.path.exists(dest):
            print("[404 Не знайдено на сервері]")
            stats["failed"] += 1
            continue

        status_str, needs_dl = check_file_status(dest, rem_size, rem_mtime)

        if not needs_dl:
            print(f"[OK: {status_str}] ({format_bytes(os.path.getsize(dest))})")
            stats["up_to_date"] += 1
            continue

        if args.check_only:
            print(f"[ПОТРІБНЕ ЗАВАНТАЖЕННЯ: {status_str}]")
            continue

        print(f"[{status_str}]")
        print(f"   Завантаження з: {url}")
        ok, msg = download_file(url, dest, description=t["desc"])
        if ok:
            sz = os.path.getsize(dest)
            stats["downloaded"] += 1
            stats["bytes_downloaded"] += sz
            print(f"   [+] Збережено: {dest} ({format_bytes(sz)})")
        else:
            stats["failed"] += 1
            print(f"   [-] Помилка: {msg}")

    print("\n" + "=" * 80)
    print(" ПІДСУМОК СИНХРОНІЗАЦІЇ БЕКАПУ:")
    print(f"   * Актуальних (вже було завантажено): {stats['up_to_date']}")
    print(f"   * Завантажено / оновлено нових:       {stats['downloaded']} ({format_bytes(stats['bytes_downloaded'])})")
    print(f"   * Помилок:                           {stats['failed']}")
    print(f"   * Каталог збереження:                {DATA_DIR}")
    print("=" * 80 + "\n")


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        print("\n\n[!] Перервано користувачем.")
        sys.exit(130)
