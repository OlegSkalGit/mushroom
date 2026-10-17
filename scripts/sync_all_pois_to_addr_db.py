import sqlite3
import time
import os
import shutil

DB_PATH = r'scripts\data\addresses\addr.db'
POI_PATH = r'scripts\data\poi\ukraine.poi'
BAK_PATH = r'scripts\data\addresses\addr.db.bak'

print(f"=== Початок імпорту всіх POI з {POI_PATH} у {DB_PATH} ===")

# 1. Створення резервної копії
if not os.path.exists(BAK_PATH):
    print("Створення бекапу addr.db...")
    shutil.copyfile(DB_PATH, BAK_PATH)
    print("Бекап створено:", BAK_PATH)

t0 = time.time()
addr_db = sqlite3.connect(DB_PATH)
acur = addr_db.cursor()

# 2. Швидкі налаштування SQLite
acur.execute("PRAGMA synchronous = OFF;")
acur.execute("PRAGMA journal_mode = MEMORY;")
acur.execute("PRAGMA cache_size = -100000;") # 100MB cache

# 3. Додаємо нові поширені типи до poi_types
new_types = [
    ('leisure', 'garden', 'Garden', 'Сад', 'UA'),
    ('emergency', 'fire_hydrant', 'Fire Hydrant', 'Пожежний гідрант', 'UA'),
    ('leisure', 'track', 'Track / Running Track', 'Бігова доріжка', 'UA'),
    ('amenity', 'grave_yard', 'Graveyard', 'Кладовище', 'UA'),
    ('shop', 'greengrocer', 'Greengrocer', 'Овочі та фрукти', 'UA'),
    ('highway', 'rest_area', 'Rest Area', 'Зона відпочинку', 'UA'),
    ('amenity', 'water_point', 'Water Point', 'Пункт роздачі води', 'UA'),
    ('highway', 'speed_camera', 'Speed Camera', 'Камера контролю швидкості', 'UA'),
    ('man_made', 'petroleum_well', 'Petroleum Well', 'Свердловина', 'UA'),
]
acur.executemany("""
    INSERT OR IGNORE INTO poi_types (group_category, tag, desc_en, desc_local, country_code)
    VALUES (?, ?, ?, ?, ?);
""", new_types)
addr_db.commit()

# Завантаження типів POI
acur.execute('SELECT tag, id, COALESCE(desc_local, desc_en, tag) FROM poi_types WHERE country_code = "UA"')
type_map = {r[0]: (r[1], r[2]) for r in acur.fetchall()}
print(f"Завантажено {len(type_map)} типів POI з poi_types")

# 4. Завантаження просторової сітки будинків для прив'язки вулиць
print("Завантаження координат будинків у пам'ять...")
acur.execute('SELECT street_id, lat, lon FROM houses WHERE lat != 0 AND lon != 0')
houses = acur.fetchall()
print(f"Завантажено {len(houses):,} будинків за {time.time()-t0:.2f} сек.")

t_grid = time.time()
grid = {}
for sid, lat, lon in houses:
    scale = 10 if abs(lat) < 10000000 else 1
    lat_u = lat * scale
    lon_u = lon * scale
    cell = (lat_u // 2000, lon_u // 2000) # ~200м комірка
    if cell not in grid:
        grid[cell] = []
    grid[cell].append((sid, lat_u, lon_u))
print(f"Побудовано просторову сітку ({len(grid):,} комірок) за {time.time()-t_grid:.2f} сек.")

def find_nearest_street(lat_u, lon_u):
    cell = (lat_u // 2000, lon_u // 2000)
    best_dist = 999999999
    best_sid = None
    # 1. Пошук у радіусі ~200м (3х3)
    for dx in (-1, 0, 1):
        for dy in (-1, 0, 1):
            c = (cell[0] + dx, cell[1] + dy)
            for sid, hlat, hlon in grid.get(c, ()):
                d = (hlat - lat_u)**2 + (hlon - lon_u)**2
                if d < best_dist:
                    best_dist = d
                    best_sid = sid
    if best_dist < 40000000: # ~200м
        return best_sid

    # 2. Якщо в лісі/парку - розширюємо пошук до ~2 км (7х7)
    for dx in range(-3, 4):
        for dy in range(-3, 4):
            if abs(dx) < 2 and abs(dy) < 2:
                continue
            c = (cell[0] + dx, cell[1] + dy)
            for sid, hlat, hlon in grid.get(c, ()):
                d = (hlat - lat_u)**2 + (hlon - lon_u)**2
                if d < best_dist:
                    best_dist = d
                    best_sid = sid
    if best_dist < 4000000000: # ~2 км
        return best_sid

    return None

# 5. Очищення старих таблиць POI для чистого перезавантаження
print("Очищення попередніх POI у addr.db...")
acur.execute("DROP TABLE IF EXISTS poi_fts;")
acur.execute("CREATE VIRTUAL TABLE poi_fts USING fts4(poi_name, type_name, type_tag, city_name, street_name, tokenize=unicode61, content='', matchinfo=fts3);")
acur.execute("DELETE FROM poi_places WHERE country_code = 'UA';")
addr_db.commit()

# Зняття індексів для прискорення вставки
try: acur.execute("DROP INDEX IF EXISTS idx_poi_places_street;")
except Exception: pass
try: acur.execute("DROP INDEX IF EXISTS idx_poi_places_country;")
except Exception: pass

# 6. Читання та імпорт усіх точок з ukraine.poi
poi_db = sqlite3.connect(POI_PATH)
pcur = poi_db.cursor()

print("Початок читання ukraine.poi...")
t_imp = time.time()
pcur.execute("SELECT p.id, i.lat, i.lon, p.data FROM poi_data p JOIN poi_index i ON p.id = i.id")

batch = []
total_count = 0
unnamed_count = 0

insert_sql = """
INSERT INTO poi_places (name, category_id, street_id, house_number, lat, lon, country_code)
VALUES (?, ?, ?, NULL, ?, ?, 'UA');
"""

while True:
    rows = pcur.fetchmany(10000)
    if not rows:
        break

    for pid, lat_deg, lon_deg, data in rows:
        lat_u = int(round(lat_deg * 1000000))
        lon_u = int(round(lon_deg * 1000000))

        tag_map = {}
        for entry in data.split('\r'):
            if '=' in entry:
                k, v = entry.split('=', 1)
                tag_map[k.strip().lower()] = v.strip()

        # Визначаємо головний тег
        amenity = tag_map.get('amenity')
        shop = tag_map.get('shop')
        tourism = tag_map.get('tourism')
        natural = tag_map.get('natural')
        highway = tag_map.get('highway')
        historic = tag_map.get('historic')
        leisure = tag_map.get('leisure')
        healthcare = tag_map.get('healthcare')
        waterway = tag_map.get('waterway')
        emergency = tag_map.get('emergency')
        railway = tag_map.get('railway')
        man_made = tag_map.get('man_made')

        primary_tag = 'other'
        if amenity == 'charging_station' or (tag_map.get('fuel:electricity') == 'yes' and amenity != 'fuel'):
            primary_tag = 'charging_station'
        elif healthcare and healthcare in type_map:
            primary_tag = healthcare
        elif amenity in ('pharmacy', 'hospital', 'clinic', 'dentist', 'doctors', 'veterinary'):
            primary_tag = amenity
        elif amenity == 'fuel':
            primary_tag = 'fuel'
        elif railway in ('tram_stop', 'station', 'halt'):
            primary_tag = railway
        elif highway in ('bus_stop', 'platform') or tag_map.get('public_transport') in ('platform', 'stop_position', 'stop', 'station') or tag_map.get('bus') == 'yes' or tag_map.get('trolleybus') == 'yes' or tag_map.get('share_taxi') == 'yes':
            primary_tag = 'bus_stop'
        elif amenity in ('cafe', 'restaurant', 'fast_food', 'bar', 'pub', 'biergarten', 'food_court', 'ice_cream', 'canteen', 'pizzeria'):
            primary_tag = amenity
        elif shop:
            primary_tag = shop if shop in type_map else 'supermarket'
        elif tourism and tourism in type_map:
            primary_tag = tourism
        elif natural and natural in type_map:
            primary_tag = natural
        elif waterway and waterway in type_map:
            primary_tag = waterway
        elif amenity and amenity in type_map:
            primary_tag = amenity
        elif historic and historic in type_map:
            primary_tag = historic
        elif leisure and leisure in type_map:
            primary_tag = leisure
        elif emergency and emergency in type_map:
            primary_tag = emergency
        elif man_made and man_made in type_map:
            primary_tag = man_made
        elif highway and highway in type_map:
            primary_tag = highway

        type_info = type_map.get(primary_tag) or type_map.get('other') or (1, 'Точка інтересу')
        type_id, desc_local = type_info

        # Назва
        name_uk = tag_map.get('name:uk')
        name_def = tag_map.get('name')
        name_en = tag_map.get('name:en')
        brand = tag_map.get('brand')
        operator = tag_map.get('operator')

        explicit_name = (name_uk or name_def or name_en or '').strip()

        if explicit_name:
            chosen_name = explicit_name
        elif brand and brand.strip():
            chosen_name = brand.strip()
        elif operator and operator.strip():
            chosen_name = f"{desc_local} ({operator.strip()})"
            unnamed_count += 1
        else:
            chosen_name = desc_local
            unnamed_count += 1

        matched_sid = find_nearest_street(lat_u, lon_u)
        batch.append((chosen_name, type_id, matched_sid, lat_u, lon_u))

    acur.executemany(insert_sql, batch)
    total_count += len(batch)
    batch.clear()
    if total_count % 100000 == 0:
        addr_db.commit()
        print(f"  Збережено {total_count:,} POI... ({time.time()-t_imp:.1f} сек.)")

addr_db.commit()
print(f"Всього збережено {total_count:,} POI (з них безіменних: {unnamed_count:,}) за {time.time()-t_imp:.2f} сек.")

# 7. Відновлення індексів
print("Відновлення індексів poi_places...")
acur.execute("CREATE INDEX IF NOT EXISTS idx_poi_places_street ON poi_places(street_id);")
acur.execute("CREATE INDEX IF NOT EXISTS idx_poi_places_country ON poi_places(country_code);")
addr_db.commit()

# 8. Побудова poi_fts
print("Побудова повнотекстового індексу poi_fts (це може зайняти 30-60 сек)...")
t_fts = time.time()
acur.execute("""
INSERT INTO poi_fts (rowid, poi_name, type_name, type_tag, city_name, street_name)
SELECT p.id, p.name, COALESCE(pt.desc_local, pt.desc_en, ''), COALESCE(pt.tag, ''), COALESCE(city.name, ''), COALESCE(sn.name, '')
FROM poi_places p
LEFT JOIN poi_types pt ON pt.id = p.category_id
LEFT JOIN streets s ON s.id = p.street_id
LEFT JOIN street_names sn ON sn.id = s.name_id
LEFT JOIN admin_places city ON city.id = s.place_id
WHERE p.country_code = 'UA';
""")
addr_db.commit()
print(f"poi_fts побудовано за {time.time()-t_fts:.2f} сек.")

print("Оптимізація poi_fts...")
acur.execute("INSERT INTO poi_fts(poi_fts) VALUES('optimize');")
addr_db.commit()

# Перевірка результату
acur.execute("SELECT count(*) FROM poi_places WHERE country_code = 'UA';")
p_cnt = acur.fetchone()[0]
print(f"Фінальна кількість у poi_places: {p_cnt:,}")

addr_db.close()
poi_db.close()
print("=== Імпорт успішно завершено! ===")
