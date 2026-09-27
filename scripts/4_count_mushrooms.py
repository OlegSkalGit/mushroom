#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Display detailed statistics and mushroom count from the SQLite database.
"""

import os
import sys
import sqlite3
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
    parser = argparse.ArgumentParser(description="Аналітика та підрахунок грибів у базі SQLite")
    parser.add_argument("--db", default=None, help="Шлях до файлу SQLite бази даних")
    args = parser.parse_args()

    db_path = find_db_path(args.db)
    if not os.path.exists(db_path):
        print(f"[Помилка] Файл бази не знайдено за шляхом: {db_path}")
        sys.exit(1)

    db_size_mb = os.path.getsize(db_path) / (1024 * 1024)
    conn = sqlite3.connect(db_path)
    cur = conn.cursor()

    # Загальні кількості
    cur.execute("SELECT COUNT(*) FROM taxa")
    total_taxa = cur.fetchone()[0]

    cur.execute("SELECT COUNT(*) FROM photos")
    total_photos = cur.fetchone()[0]

    # Гриби без фото та з фото
    cur.execute("SELECT COUNT(*) FROM taxa WHERE photos_count > 0")
    taxa_with_photos = cur.fetchone()[0]
    taxa_without_photos = total_taxa - taxa_with_photos

    avg_photos = (total_photos / total_taxa) if total_taxa > 0 else 0.0

    # Розбивка за їстівністю
    cur.execute("""
        SELECT COALESCE(edibility, 'unknown'), COUNT(*) 
        FROM taxa 
        GROUP BY edibility 
        ORDER BY COUNT(*) DESC
    """)
    edibility_rows = cur.fetchall()

    edibility_labels = {
        "edible": "Їстівні (edible)",
        "cond-edible": "Умовно-їстівні (cond-edible)",
        "toxic": "Отруйні (toxic)",
        "deadly": "Смертельно отруйні (deadly)",
        "inedible": "Неїстівні (inedible)",
        "unknown": "Не визначено (unknown)"
    }

    # Розбивка за типом гіменофору
    cur.execute("""
        SELECT COALESCE(hymenium, 'other'), COUNT(*) 
        FROM taxa 
        GROUP BY hymenium 
        ORDER BY COUNT(*) DESC
    """)
    hymenium_rows = cur.fetchall()

    hymenium_labels = {
        "gills": "Пластинчасті (gills)",
        "tubes": "Трубчасті (tubes)",
        "other": "Інші (other)"
    }

    # Заповненість локалізацій та описів
    cur.execute("SELECT COUNT(*) FROM taxa WHERE name_uk IS NOT NULL AND TRIM(name_uk) != ''")
    has_name_uk = cur.fetchone()[0]

    cur.execute("SELECT COUNT(*) FROM taxa WHERE desc_uk IS NOT NULL AND TRIM(desc_uk) != ''")
    has_desc_uk = cur.fetchone()[0]

    cur.execute("SELECT COUNT(*) FROM taxa WHERE desc_en IS NOT NULL AND TRIM(desc_en) != ''")
    has_desc_en = cur.fetchone()[0]

    cur.execute("SELECT COUNT(*) FROM taxa WHERE lookalikes_uk IS NOT NULL AND TRIM(lookalikes_uk) != ''")
    has_lookalikes = cur.fetchone()[0]

    # Топ-5 за кількістю фото
    cur.execute("""
        SELECT scientific_name, COALESCE(name_uk, '-'), photos_count 
        FROM taxa 
        ORDER BY photos_count DESC 
        LIMIT 5
    """)
    top_photos = cur.fetchall()

    conn.close()

    # Виведення звіту
    print("\n" + "=" * 62)
    print(f"   СТАТИСТИКА БАЗИ ДАНИХ ГРИБІВ: {os.path.basename(db_path)}")
    print("=" * 62)
    print(f" Файл бази:              {db_path}")
    print(f" Розмір на диску:        {db_size_mb:.2f} MB")
    print(f" Всього видів грибів:    {total_taxa}")
    print(f" Всього фотографій:      {total_photos}")
    print(f" Видів із фото:          {taxa_with_photos} ({taxa_with_photos / total_taxa * 100:.1f}%)" if total_taxa else "")
    print(f" Видів без фото:         {taxa_without_photos}")
    print(f" Середня к-сть фото:     {avg_photos:.2f} на вид")

    print("\n" + "-" * 62)
    print(" Розподіл за їстівністю:")
    print("-" * 62)
    for code, cnt in edibility_rows:
        label = edibility_labels.get(code, code)
        pct = (cnt / total_taxa * 100) if total_taxa > 0 else 0
        print(f"  • {label:<32} {cnt:>5} ({pct:>5.1f}%)")

    print("\n" + "-" * 62)
    print(" Розподіл за типом гіменофору:")
    print("-" * 62)
    for code, cnt in hymenium_rows:
        label = hymenium_labels.get(code, code)
        pct = (cnt / total_taxa * 100) if total_taxa > 0 else 0
        print(f"  • {label:<32} {cnt:>5} ({pct:>5.1f}%)")

    print("\n" + "-" * 62)
    print(" Повнота текстових даних:")
    print("-" * 62)
    print(f"  • Мають назву українською:     {has_name_uk:>5} / {total_taxa}")
    print(f"  • Мають опис українською:      {has_desc_uk:>5} / {total_taxa}")
    print(f"  • Мають опис англійською:      {has_desc_en:>5} / {total_taxa}")
    print(f"  • Мають застереження/двійників: {has_lookalikes:>5} / {total_taxa}")

    if top_photos:
        print("\n" + "-" * 62)
        print(" Топ-5 грибів за кількістю фото:")
        print("-" * 62)
        for sname, nuk, p_count in top_photos:
            print(f"  • {sname} ({nuk}): {p_count} фото")

    print("=" * 62 + "\n")


if __name__ == "__main__":
    main()
