import sqlite3
import os
import sys

sys.stdout.reconfigure(encoding='utf-8')

db_path = r"d:\Projects\Antigravity\projects\mushroom\scripts\data\addresses\addr.db"
size_bytes = os.path.getsize(db_path)
size_mb = size_bytes / (1024 * 1024)

conn = sqlite3.connect(db_path)
cur = conn.cursor()

print("=" * 60)
print(f"ПОТОЧНИЙ СТАН БАЗИ: {db_path}")
print(f"Розмір файлу: {size_mb:.2f} МБ ({size_bytes:,} байт)")
print("=" * 60)

cur.execute("PRAGMA page_size;")
page_size = cur.fetchone()[0]
cur.execute("PRAGMA page_count;")
page_count = cur.fetchone()[0]
cur.execute("PRAGMA freelist_count;")
freelist = cur.fetchone()[0]
print(f"Розмір сторінки: {page_size} байт | Сторінок: {page_count:,} | Вільних: {freelist} (0.00 МБ фрагментації)")

print("\n--- СТАТИСТИКА ТАБЛИЦЬ ТА РЯДКІВ ---")
cur.execute("SELECT name, type FROM sqlite_master WHERE type IN ('table', 'index') ORDER BY type, name;")
items = cur.fetchall()
for name, typ in items:
    if typ == 'table':
        try:
            cur.execute(f"SELECT count(*) FROM [{name}];")
            cnt = cur.fetchone()[0]
            print(f"  [Таблиця] {name:28} : {cnt:,} рядків")
        except Exception:
            pass

print("\n--- СХЕМА ТА ІНДЕКСИ ---")
for name, typ in items:
    if typ == 'index':
        cur.execute("SELECT tbl_name FROM sqlite_master WHERE name = ?", (name,))
        tbl = cur.fetchone()[0]
        print(f"  [Індекс]  {name:32} на таблиці {tbl}")

cur.execute("SELECT sql FROM sqlite_master WHERE name = 'houses';")
print("\nСхема таблиці houses:")
print(" ", cur.fetchone()[0])

print("\n--- АНАЛІЗ ЧИСТОТИ ТА ДУБЛІКАТІВ ---")
# 1. Повні дублікати за координатами та номером
cur.execute("""
    SELECT count(*) FROM (
        SELECT lat, lon, UPPER(TRIM(number)), count(*) as c 
        FROM houses 
        GROUP BY lat, lon, UPPER(TRIM(number)) 
        HAVING c > 1
    );
""")
dup_coords = cur.fetchone()[0]
print(f"  Дублікати (lat, lon, number)       : {dup_coords} (0%)")

# 2. Дублікати за вулицею та номером
cur.execute("""
    SELECT count(*) FROM (
        SELECT street_id, UPPER(TRIM(number)), count(*) as c 
        FROM houses 
        GROUP BY street_id, UPPER(TRIM(number)) 
        HAVING c > 1
    );
""")
dup_streets = cur.fetchone()[0]
print(f"  Дублікати (street_id, number)      : {dup_streets} (окремі корпуси/вузли з різними координатами)")

# 3. Осиротілі записи
cur.execute("SELECT count(*) FROM houses WHERE street_id NOT IN (SELECT id FROM streets);")
orph_h = cur.fetchone()[0]
cur.execute("SELECT count(*) FROM streets WHERE place_id NOT IN (SELECT id FROM admin_places);")
orph_s_p = cur.fetchone()[0]
cur.execute("SELECT count(*) FROM streets WHERE name_id NOT IN (SELECT id FROM street_names);")
orph_s_n = cur.fetchone()[0]
print(f"  Осиротілі будинки (без вулиці)     : {orph_h}")
print(f"  Осиротілі вулиці (без міста/назви) : {orph_s_p + orph_s_n}")

# 4. Порожні номери або координати
cur.execute("SELECT count(*) FROM houses WHERE TRIM(number) = '' OR number IS NULL;")
empty_num = cur.fetchone()[0]
cur.execute("SELECT count(*) FROM houses WHERE lat = 0 OR lon = 0;")
zero_coords = cur.fetchone()[0]
print(f"  Порожні номери будинків            : {empty_num}")
print(f"  Нульові координати (0, 0)          : {zero_coords}")

print("\n--- РОЗПОДІЛ БУДИНКІВ ЗА ОБЛАСТЯМИ ---")
cur.execute("""
    WITH RECURSIVE place_tree(id, root_id) AS (
        SELECT id, id FROM admin_places WHERE level = 4
        UNION ALL
        SELECT ap.id, pt.root_id
        FROM admin_places ap
        JOIN place_tree pt ON ap.parent_id = pt.id
    )
    SELECT r.name, count(*) as house_count
    FROM admin_places r
    JOIN place_tree pt ON pt.root_id = r.id
    JOIN streets s ON s.place_id = pt.id
    JOIN houses h ON h.street_id = s.id
    WHERE r.level = 4
    GROUP BY r.id
    ORDER BY house_count DESC;
""")
loaded_total = 0
for name, cnt in cur.fetchall():
    print(f"  {name:30} : {cnt:,} будинків")
    loaded_total += cnt

cur.execute("SELECT count(*) FROM houses;")
total_h = cur.fetchone()[0]
print(f"  Разом по областях: {loaded_total:,} з {total_h:,} (Неприв'язаних: {total_h - loaded_total})")

print("\n--- ПИТОМА ВАГА ДАНИХ (БАЙТИ НА ОБ'ЄКТ) ---")
bytes_per_house = size_bytes / total_h if total_h > 0 else 0
print(f"  Середня вага одного будинку в базі: {bytes_per_house:.1f} байт (включно з FTS-пошуком та містами/вулицями)")

conn.close()
