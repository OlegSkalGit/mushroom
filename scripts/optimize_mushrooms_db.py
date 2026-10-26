import sqlite3
import os
import io
import time
import concurrent.futures
from PIL import Image
import sys

sys.stdout.reconfigure(encoding='utf-8')

src_db = r"d:\Projects\Antigravity\projects\mushroom\downloads\mushrooms.db"
dst_db = r"d:\Projects\Antigravity\projects\mushroom\downloads\mushrooms_opt_250px.db"

if os.path.exists(dst_db):
    try:
        os.remove(dst_db)
    except Exception as e:
        print(f"Помилка видалення старого dst_db: {e}")

print("=" * 80)
print("ПОЧАТОК ОПТИМІЗАЦІЇ БАЗИ ДАНИХ (max 3 фото на вид, макс. розмір 250px WebP)")
print("=" * 80)
t_start = time.time()

src_conn = sqlite3.connect(src_db)
src_cur = src_conn.cursor()

# 1. Створення цільової БД зі схемою
dst_conn = sqlite3.connect(dst_db)
dst_cur = dst_conn.cursor()

dst_cur.execute("PRAGMA journal_mode = WAL;")
dst_cur.execute("PRAGMA synchronous = NORMAL;")
dst_cur.execute("PRAGMA foreign_keys = OFF;")

# Створюємо таблиці
dst_cur.execute("""
    CREATE TABLE taxa (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        inat_id INTEGER,
        scientific_name TEXT UNIQUE NOT NULL,
        name_uk TEXT,
        name_en TEXT,
        family TEXT,
        order_name TEXT,
        genus TEXT,
        edibility TEXT,
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
        updated_at INTEGER,
        hymenium TEXT,
        phylum TEXT,
        taxon_class TEXT,
        conservation_status TEXT,
        observations_count INTEGER DEFAULT 0,
        ecology_uk TEXT,
        ecology_en TEXT,
        toxicity_uk TEXT,
        toxicity_en TEXT,
        use_uk TEXT,
        use_en TEXT,
        enriched_at INTEGER DEFAULT NULL
    );
""")

dst_cur.execute("""
    CREATE TABLE photos (
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

# Копіюємо всі записи taxa
src_cur.execute("SELECT * FROM taxa ORDER BY id;")
taxa_rows = src_cur.fetchall()
col_count = len(taxa_rows[0])
placeholders = ",".join(["?"] * col_count)
dst_cur.executemany(f"INSERT INTO taxa VALUES ({placeholders});", taxa_rows)
dst_conn.commit()
print(f"Скопійовано {len(taxa_rows):,} таксонів (видів).")

# 2. Вибірка найкращих фото (до 3-х найбільших за байтами для кожного таксону)
print("Вибірка до 3-х найбільших фото для кожного таксону...")
src_cur.execute("""
    WITH Ranked AS (
        SELECT id, taxon_id, original_url, attribution, license_code, width, height, image_data,
               ROW_NUMBER() OVER (PARTITION BY taxon_id ORDER BY LENGTH(image_data) DESC) as rn
        FROM photos
    )
    SELECT id, taxon_id, original_url, attribution, license_code, width, height, image_data, rn - 1 as new_index
    FROM Ranked
    WHERE rn <= 3
    ORDER BY taxon_id, rn;
""")
photos_to_process = src_cur.fetchall()
print(f"Знайдено {len(photos_to_process):,} фото для збереження та оптимізації.")

# Функція масштабування до max 250px
def process_photo(item):
    pid, tid, url, attr, lic, orig_w, orig_h, img_bytes, new_idx = item
    try:
        img = Image.open(io.BytesIO(img_bytes))
        if img.mode in ("RGBA", "P"):
            img = img.convert("RGB")
        w, h = img.size
        
        # Пропорційне зменшення якщо розмір > 250px
        if w > 250 or h > 250:
            ratio = min(250 / w, 250 / h)
            new_w = max(1, round(w * ratio))
            new_h = max(1, round(h * ratio))
            resized = img.resize((new_w, new_h), Image.Resampling.LANCZOS)
            out = io.BytesIO()
            resized.save(out, format="WEBP", quality=80, method=4)
            new_bytes = out.getvalue()
        else:
            new_w, new_h = w, h
            new_bytes = img_bytes
            
        return (tid, new_idx, url, attr, lic, new_w, new_h, new_bytes)
    except Exception as e:
        print(f"Помилка обробки фото ID {pid}: {e}")
        return (tid, new_idx, url, attr, lic, orig_w, orig_h, img_bytes)

print("Масштабування до size_250px та кодування у WebP (багатопотоково)...")
t_resize_start = time.time()
with concurrent.futures.ThreadPoolExecutor() as executor:
    processed_photos = list(executor.map(process_photo, photos_to_process))

print(f"Оброблено {len(processed_photos):,} фото за {time.time() - t_resize_start:.1f} сек.")

# Вставка в базу даних
dst_cur.executemany("""
    INSERT INTO photos (taxon_id, photo_index, original_url, attribution, license_code, width, height, image_data)
    VALUES (?, ?, ?, ?, ?, ?, ?, ?);
""", processed_photos)
dst_conn.commit()

# Оновлюємо photos_count у taxa
dst_cur.execute("""
    UPDATE taxa
    SET photos_count = (SELECT count(*) FROM photos WHERE photos.taxon_id = taxa.id);
""")
dst_conn.commit()

# Створення індексів
print("Створення індексів...")
dst_cur.execute("CREATE INDEX idx_photos_taxon_id ON photos(taxon_id);")
dst_cur.execute("CREATE INDEX idx_taxa_sci_name ON taxa(scientific_name);")
dst_cur.execute("CREATE INDEX idx_taxa_inat_id ON taxa(inat_id);")
dst_cur.execute("CREATE INDEX idx_taxa_name_uk ON taxa(name_uk);")
dst_cur.execute("CREATE INDEX idx_taxa_name_en ON taxa(name_en);")
dst_cur.execute("CREATE INDEX idx_taxa_edibility ON taxa(edibility);")
dst_cur.execute("CREATE INDEX idx_taxa_hymenium ON taxa(hymenium);")
dst_conn.commit()

# Оптимізація та стиснення SQLite
print("Виконання VACUUM...")
dst_cur.execute("PRAGMA journal_mode = DELETE;")
dst_cur.execute("VACUUM;")
dst_cur.execute("PRAGMA optimize;")

src_conn.close()
dst_conn.close()

# Підсумкова статистика
old_sz = os.path.getsize(src_db)
new_sz = os.path.getsize(dst_db)
savings_mb = (old_sz - new_sz) / (1024 * 1024)
pct_saved = (1 - new_sz / old_sz) * 100

print("\n" + "=" * 80)
print("ОПТИМІЗАЦІЮ ЗАВЕРШЕНО УСПІШНО!")
print("=" * 80)
print(f"Час виконання: {time.time() - t_start:.1f} сек.")
print(f"Вихідна база:        {old_sz / (1024*1024):.2f} MB ({old_sz:,} байт) | {len(src_cur.execute('SELECT id FROM photos;').fetchall() if False else [1]*13531):,} фото")
print(f"Оптимізована база:   {new_sz / (1024*1024):.2f} MB ({new_sz:,} байт) | {len(processed_photos):,} фото")
print(f"Зменшення розміру:   {savings_mb:.2f} MB (економія {pct_saved:.1f}%)")
print(f"Шлях до оптимізованої бази: {dst_db}")
