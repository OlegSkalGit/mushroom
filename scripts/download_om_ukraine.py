#!/usr/bin/env python3
# -*- coding: utf-8 -*-
import os
import sys
import json
import time
import urllib.request
import urllib.error
import urllib.parse
from concurrent.futures import ThreadPoolExecutor, as_completed

try:
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    if hasattr(sys.stderr, "reconfigure"):
        sys.stderr.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

BASE_DIR = os.path.dirname(os.path.abspath(__file__))
PROJECT_ROOT = os.path.dirname(BASE_DIR)
COUNTRIES_JSON = os.path.join(PROJECT_ROOT, ".BAK", "organicmaps-master", "data", "countries.json")
OUTPUT_DIR = os.path.join(PROJECT_ROOT, "downloads", "maps_om")

CDN_BASE = "https://cdn.organicmaps.app/maps/{version}/{filename}"

def download_region(item, version, output_dir):
    region_id = item["id"]
    expected_size = item["s"]
    filename = f"{region_id}.mwm"
    target_path = os.path.join(output_dir, filename)

    if os.path.exists(target_path):
        actual_size = os.path.getsize(target_path)
        if actual_size == expected_size:
            return region_id, True, "Already downloaded", actual_size

    quoted_filename = urllib.parse.quote(filename)
    url = CDN_BASE.format(version=version, filename=quoted_filename)
    tmp_path = target_path + ".downloading"

    req = urllib.request.Request(url, headers={"User-Agent": "OrganicMaps/Mushroom-Downloader"})
    
    start_time = time.time()
    try:
        with urllib.request.urlopen(req, timeout=30) as response, open(tmp_path, "wb") as out_file:
            downloaded = 0
            while True:
                chunk = response.read(65536)
                if not chunk:
                    break
                out_file.write(chunk)
                downloaded += len(chunk)

        if downloaded != expected_size:
            if os.path.exists(tmp_path):
                os.remove(tmp_path)
            return region_id, False, f"Size mismatch: got {downloaded}, expected {expected_size}", downloaded

        if os.path.exists(target_path):
            os.remove(target_path)
        os.rename(tmp_path, target_path)

        duration = time.time() - start_time
        speed_mb = (downloaded / (1024 * 1024)) / max(duration, 0.001)
        return region_id, True, f"OK ({duration:.1f}s, {speed_mb:.1f} MB/s)", downloaded
    except Exception as e:
        if os.path.exists(tmp_path):
            try:
                os.remove(tmp_path)
            except Exception:
                pass
        return region_id, False, f"Error: {e}", 0

def main():
    os.makedirs(OUTPUT_DIR, exist_ok=True)

    with open(COUNTRIES_JSON, "r", encoding="utf-8") as f:
        data = json.load(f)

    version = data.get("v")
    ukr_group = [g for g in data.get("g", []) if g.get("id") == "Ukraine"]
    if not ukr_group:
        print("Ukraine not found in countries.json")
        sys.exit(1)

    items = ukr_group[0].get("g", [])

    # Also include World and WorldCoasts for base overview map
    base_items = [g for g in data.get("g", []) if g.get("id") in ("World", "WorldCoasts")]
    all_download_items = base_items + items

    total_size = sum(it["s"] for it in all_download_items)
    total_size_mb = total_size / (1024 * 1024)

    print(f"Starting download for {len(all_download_items)} maps (Ukraine + World basemaps, version {version}, total {total_size_mb:.1f} MB)...")
    print(f"Target folder: {OUTPUT_DIR}")

    completed = 0
    total_downloaded = 0
    overall_start = time.time()

    # Use 4 parallel download threads
    with ThreadPoolExecutor(max_workers=4) as executor:
        futures = {executor.submit(download_region, item, version, OUTPUT_DIR): item for item in all_download_items}
        for future in as_completed(futures):
            region_id, success, msg, size = future.result()
            completed += 1
            total_downloaded += size
            status = "[OK]" if success else "[FAIL]"
            print(f"[{completed}/{len(all_download_items)}] {status} {region_id}: {msg}")

    overall_duration = time.time() - overall_start
    avg_speed = (total_downloaded / (1024 * 1024)) / max(overall_duration, 0.001)
    print(f"\nFinished in {overall_duration:.1f}s. Average speed: {avg_speed:.1f} MB/s.")

if __name__ == "__main__":
    main()
