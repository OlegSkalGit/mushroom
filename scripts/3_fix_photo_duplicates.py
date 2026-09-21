#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Fix duplicate photos in mushroom SQLite database.
Deduplicates photos per taxon, resets photo_index, updates photos_count, and vacuums DB.
"""

import os
import sys
import sqlite3
import hashlib
import argparse

if sys.platform == "win32":
    try:
        sys.stdout.reconfigure(encoding="utf-8")
        sys.stderr.reconfigure(encoding="utf-8")
    except Exception:
        pass


def find_db_path(custom_path: str = None) -> str:
    if custom_path and os.path.exists(custom_path):
        return custom_path

    script_dir = os.path.dirname(os.path.abspath(__file__))
    repo_root = os.path.dirname(script_dir) if os.path.basename(script_dir) == "scripts" else script_dir

    candidates = [
        custom_path,
        os.path.join(repo_root, "downloads", "mushrooms.db"),
        os.path.join(script_dir, "downloads", "mushrooms.db"),
        os.path.join(repo_root, "mushrooms_offline.db"),
        os.path.join(script_dir, "mushrooms_offline.db"),
        os.path.join(repo_root, "mushrooms.db"),
        os.path.join(script_dir, "mushrooms.db"),
        "downloads/mushrooms.db",
        "mushrooms_offline.db",
        "mushrooms.db"
    ]

    for p in candidates:
        if p and os.path.exists(p):
            return p

    return custom_path or "downloads/mushrooms.db"


def main():
    parser = argparse.ArgumentParser(description="Видалення дублікатів фотографій у базі грибів")
    parser.add_argument("--db", default=None, help="Шлях до файлу SQLite бази даних")
    args = parser.parse_args()

    db_path = find_db_path(args.db)
    if not os.path.exists(db_path):
        print(f"[Помилка] Базу даних не знайдено за шляхом: {db_path}")
        sys.exit(1)

    print(f"[*] Підключення до бази: {db_path}")
    initial_size_mb = os.path.getsize(db_path) / (1024 * 1024)

    conn = sqlite3.connect(db_path)
    cur = conn.cursor()

    # Початкова статистика
    cur.execute("SELECT COUNT(*) FROM taxa")
    total_taxa = cur.fetchone()[0]
    cur.execute("SELECT COUNT(*) FROM photos")
    initial_photos = cur.fetchone()[0]

    print(f"[*] Грибів у базі: {total_taxa}")
    print(f"[*] Фотографій до очищення: {initial_photos}")

    # 1. Пошук дублікатів
    print("[*] Сканування фотографій на наявність дублікатів...")
    cur.execute("SELECT id, taxon_id, original_url FROM photos ORDER BY taxon_id, id")
    all_photos = cur.fetchall()

    seen_keys = set()
    ids_to_delete = []

    for photo_id, taxon_id, url in all_photos:
        if url and url.strip():
            key = (taxon_id, "url", url.strip())
        else:
            cur.execute("SELECT image_data FROM photos WHERE id = ?", (photo_id,))
            blob = cur.fetchone()[0]
            blob_hash = hashlib.md5(blob).hexdigest()
            key = (taxon_id, "hash", blob_hash)

        if key in seen_keys:
            ids_to_delete.append(photo_id)
        else:
            seen_keys.add(key)

    dups_count = len(ids_to_delete)
    print(f"[*] Знайдено дублікатів фото: {dups_count}")

    # 2. Видалення дублікатів
    if dups_count > 0:
        batch_size = 900
        for i in range(0, dups_count, batch_size):
            batch = ids_to_delete[i:i + batch_size]
            placeholders = ",".join("?" for _ in batch)
            cur.execute(f"DELETE FROM photos WHERE id IN ({placeholders})", batch)
        conn.commit()
        print(f"[✓] Видалено {dups_count} копій фото.")
    else:
        print("[i] Дублікатів фото не виявлено.")

    # 3. Видалення фото без прив'язки до існуючих грибів
    cur.execute("DELETE FROM photos WHERE taxon_id NOT IN (SELECT id FROM taxa)")
    orphans = cur.rowcount
    if orphans > 0:
        print(f"[✓] Видалено фото-сиріт: {orphans}")
        conn.commit()

    # 4. Нормалізація photo_index (0, 1, 2, ...) для кожного гриба
    print("[*] Перенумерація індексів фотографій...")
    cur.execute("SELECT DISTINCT taxon_id FROM photos")
    taxa_with_photos = [r[0] for r in cur.fetchall()]

    for tid in taxa_with_photos:
        cur.execute("SELECT id FROM photos WHERE taxon_id = ? ORDER BY photo_index ASC, id ASC", (tid,))
        rows = cur.fetchall()
        for idx, (p_id,) in enumerate(rows):
            cur.execute("UPDATE photos SET photo_index = ? WHERE id = ?", (idx, p_id))

    # 5. Оновлення лічильника photos_count у taxa
    print("[*] Оновлення лічильників photos_count у таблиці taxa...")
    cur.execute("""
        UPDATE taxa
        SET photos_count = (
            SELECT COUNT(*) FROM photos WHERE photos.taxon_id = taxa.id
        )
    """)
    conn.commit()

    # 6. Оптимізація бази даних (VACUUM)
    print("[*] Виконання VACUUM для стиснення бази...")
    conn.execute("VACUUM;")
    conn.close()

    final_size_mb = os.path.getsize(db_path) / (1024 * 1024)
    saved_mb = initial_size_mb - final_size_mb

    print("=" * 50)
    print(f"[✓] Готово!")
    print(f"    Залишилося фото: {initial_photos - dups_count - orphans}")
    print(f"    Розмір бази: {initial_size_mb:.2f} MB -> {final_size_mb:.2f} MB (звільнено {saved_mb:.2f} MB)")
    print("=" * 50)


if __name__ == "__main__":
    main()
