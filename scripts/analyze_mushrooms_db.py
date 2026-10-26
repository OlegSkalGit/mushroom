import sqlite3
import os
import sys
import io

sys.stdout.reconfigure(encoding='utf-8')

db_path = r"d:\Projects\Antigravity\projects\mushroom\downloads\mushrooms.db"
file_size_bytes = os.path.getsize(db_path)

conn = sqlite3.connect(db_path)
cur = conn.cursor()

print("=" * 70)
print("1. ЗАГАЛЬНА ІНФОРМАЦІЯ ПРО БАЗУ ДАНИХ")
print("=" * 70)
print(f"Шлях до файлу: {db_path}")
print(f"Розмір файлу: {file_size_bytes / (1024*1024):.2f} MB ({file_size_bytes:,} байт)")

cur.execute("PRAGMA page_size;")
page_size = cur.fetchone()[0]
cur.execute("PRAGMA page_count;")
page_count = cur.fetchone()[0]
cur.execute("PRAGMA freelist_count;")
freelist = cur.fetchone()[0]
print(f"Розмір сторінки: {page_size} байт")
print(f"Всього сторінок: {page_count:,} ({page_count * page_size / (1024*1024):.2f} MB)")
print(f"Вільних сторінок (freelist): {freelist:,} ({freelist * page_size / (1024*1024):.2f} MB)")

cur.execute("SELECT count(*) FROM taxa;")
total_taxa = cur.fetchone()[0]
cur.execute("SELECT count(*) FROM photos;")
total_photos = cur.fetchone()[0]
print(f"Кількість таксонів (видів грибів): {total_taxa:,}")
print(f"Кількість фотографій: {total_photos:,}")

print("\n" + "=" * 70)
print("2. АНАЛІЗ РОЗМІРІВ У БАЗІ (ЩО ЗАЙМАЄ НАЙБІЛЬШЕ МІСЦЯ)")
print("=" * 70)

# Calculate blob size for photos
cur.execute("""
    SELECT 
        SUM(LENGTH(image_data)),
        AVG(LENGTH(image_data)),
        MIN(LENGTH(image_data)),
        MAX(LENGTH(image_data)),
        SUM(LENGTH(original_url) + LENGTH(attribution) + LENGTH(license_code))
    FROM photos;
""")
blob_sum, blob_avg, blob_min, blob_max, photo_meta_sum = cur.fetchone()

# Calculate text sizes for taxa
taxa_text_cols = [
    'scientific_name', 'name_uk', 'name_en', 'family', 'order_name', 'genus', 
    'phylum', 'taxon_class', 'edibility', 'conservation_status',
    'desc_uk', 'desc_en', 'lookalikes_uk', 'lookalikes_en',
    'ecology_uk', 'ecology_en', 'toxicity_uk', 'toxicity_en',
    'use_uk', 'use_en',
    'props_cap', 'props_hymenium', 'props_stem', 'props_flesh', 'props_season', 'props_habitat',
    'wiki_url_uk', 'wiki_url_en'
]

taxa_col_sizes = {}
for col in taxa_text_cols:
    cur.execute(f"SELECT SUM(LENGTH([{col}])), COUNT([{col}]) FROM taxa WHERE [{col}] IS NOT NULL AND [{col}] != '';")
    s, c = cur.fetchone()
    taxa_col_sizes[col] = (s or 0, c)

total_taxa_text_bytes = sum(s for s, c in taxa_col_sizes.values())

print(f"{'Компонент':<35} | {'Розмір (MB)':<12} | {'Розмір (байт)':<16} | {'% від файлу':<10}")
print("-" * 79)
print(f"{'BLOB фотографій (image_data)':<35} | {blob_sum / (1024*1024):<12.2f} | {blob_sum:<16,} | {blob_sum / file_size_bytes * 100:<9.2f}%")
print(f"{'Текстові описи та статті (taxa)':<35} | {total_taxa_text_bytes / (1024*1024):<12.2f} | {total_taxa_text_bytes:<16,} | {total_taxa_text_bytes / file_size_bytes * 100:<9.2f}%")
if photo_meta_sum:
    print(f"{'Метадані фото (URL, ліцензії)':<35} | {photo_meta_sum / (1024*1024):<12.2f} | {photo_meta_sum:<16,} | {photo_meta_sum / file_size_bytes * 100:<9.2f}%")

sqlite_overhead = file_size_bytes - (blob_sum + total_taxa_text_bytes + (photo_meta_sum or 0))
print(f"{'Службові структури SQLite / B-Tree':<35} | {sqlite_overhead / (1024*1024):<12.2f} | {sqlite_overhead:<16,} | {sqlite_overhead / file_size_bytes * 100:<9.2f}%")
print(f"{'РАЗОМ (файл БД)':<35} | {file_size_bytes / (1024*1024):<12.2f} | {file_size_bytes:<16,} | 100.00%")

print("\n" + "=" * 70)
print("3. ДЕТАЛЬНИЙ АНАЛІЗ ФОТОГРАФІЙ")
print("=" * 70)
print(f"Загальна кількість фото: {total_photos:,}")
print(f"Загальний обсяг зображень: {blob_sum / (1024*1024):.2f} MB")
print(f"Середній розмір одного фото: {blob_avg / 1024:.2f} KB ({blob_avg:,.0f} байт)")
print(f"Мінімальний розмір фото: {blob_min / 1024:.2f} KB ({blob_min:,} байт)")
print(f"Максимальний розмір фото: {blob_max / 1024:.2f} KB ({blob_max:,} байт)")

# Photos distribution per taxon
cur.execute("""
    SELECT photo_count, count(*) as num_taxa
    FROM (
        SELECT t.id, count(p.id) as photo_count
        FROM taxa t
        LEFT JOIN photos p ON t.id = p.taxon_id
        GROUP BY t.id
    )
    GROUP BY photo_count
    ORDER BY photo_count;
""")
print("\nРозподіл кількості фотографій на один вид:")
for cnt, n_taxa in cur.fetchall():
    pct = (n_taxa / total_taxa) * 100
    print(f"  {cnt} фото: {n_taxa:5,} видів ({pct:5.1f}%)")

# Dimensions and formats
cur.execute("""
    SELECT 
        AVG(width), MIN(width), MAX(width),
        AVG(height), MIN(height), MAX(height)
    FROM photos;
""")
w_avg, w_min, w_max, h_avg, h_min, h_max = cur.fetchone()
print(f"\nРоздільна здатність фото (пікселі):")
print(f"  Ширина:  середня = {w_avg:.0f} px, min = {w_min} px, max = {w_max} px")
print(f"  Висота:  середня = {h_avg:.0f} px, min = {h_min} px, max = {h_max} px")

# Most common resolutions
cur.execute("""
    SELECT width || 'x' || height as res, count(*) as c
    FROM photos
    GROUP BY width, height
    ORDER BY c DESC
    LIMIT 10;
""")
print("\nТоп-10 найпопулярніших роздільних здатностей:")
for res, c in cur.fetchall():
    print(f"  {res:<12}: {c:5,} фото ({c/total_photos*100:4.1f}%)")

# Photo formats check (magic bytes from sample)
cur.execute("SELECT id, substr(image_data, 1, 16) FROM photos LIMIT 5000;")
format_counts = {}
for pid, magic in cur.fetchall():
    if magic.startswith(b'\xff\xd8\xff'):
        fmt = "JPEG"
    elif magic.startswith(b'RIFF') and magic[8:12] == b'WEBP':
        fmt = "WebP"
    elif magic.startswith(b'\x89PNG\r\n\x1a\n'):
        fmt = "PNG"
    else:
        fmt = f"Other"
    format_counts[fmt] = format_counts.get(fmt, 0) + 1

print("\nФормати фото (вибірка 5,000):")
for fmt, c in sorted(format_counts.items(), key=lambda x: -x[1]):
    print(f"  {fmt}: {c:,} ({c/5000*100:.1f}%)")

# Top 5 largest photos
cur.execute("""
    SELECT p.id, t.scientific_name, t.name_uk, p.width, p.height, LENGTH(p.image_data) as sz
    FROM photos p
    JOIN taxa t ON p.taxon_id = t.id
    ORDER BY sz DESC
    LIMIT 5;
""")
print("\nТоп-5 найбільших окремих фото:")
for pid, sname, uname, w, h, sz in cur.fetchall():
    uname_str = f" ({uname})" if uname else ""
    print(f"  ID {pid}: {sname}{uname_str} — {w}x{h} px, {sz/1024:.1f} KB ({sz:,} байт)")

# Top 5 smallest photos
cur.execute("""
    SELECT p.id, t.scientific_name, t.name_uk, p.width, p.height, LENGTH(p.image_data) as sz
    FROM photos p
    JOIN taxa t ON p.taxon_id = t.id
    ORDER BY sz ASC
    LIMIT 5;
""")
print("\nТоп-5 найменших фото:")
for pid, sname, uname, w, h, sz in cur.fetchall():
    uname_str = f" ({uname})" if uname else ""
    print(f"  ID {pid}: {sname}{uname_str} — {w}x{h} px, {sz/1024:.1f} KB ({sz:,} байт)")

# Licenses
cur.execute("SELECT license_code, count(*) FROM photos GROUP BY license_code ORDER BY count(*) DESC;")
print("\nРозподіл ліцензій фото:")
for lic, c in cur.fetchall():
    print(f"  {lic or 'Не вказано':<15}: {c:5,} фото ({c/total_photos*100:4.1f}%)")

print("\n" + "=" * 70)
print("4. ДЕТАЛЬНИЙ АНАЛІЗ СТАТЕЙ ТА ОПИСІВ")
print("=" * 70)

# Language coverage
cur.execute("""
    SELECT
        COUNT(CASE WHEN desc_uk IS NOT NULL AND desc_uk != '' THEN 1 END) as has_uk,
        COUNT(CASE WHEN desc_en IS NOT NULL AND desc_en != '' THEN 1 END) as has_en,
        COUNT(CASE WHEN (desc_uk IS NOT NULL AND desc_uk != '') AND (desc_en IS NOT NULL AND desc_en != '') THEN 1 END) as has_both,
        COUNT(CASE WHEN (desc_uk IS NULL OR desc_uk = '') AND (desc_en IS NULL OR desc_en = '') THEN 1 END) as has_none,
        COUNT(CASE WHEN name_uk IS NOT NULL AND name_uk != '' THEN 1 END) as has_name_uk,
        COUNT(CASE WHEN name_en IS NOT NULL AND name_en != '' THEN 1 END) as has_name_en
    FROM taxa;
""")
has_uk, has_en, has_both, has_none, has_name_uk, has_name_en = cur.fetchone()

print(f"Загальна кількість таксонів: {total_taxa:,}")
print(f"Види з українською назвою (name_uk): {has_name_uk:,} ({has_name_uk/total_taxa*100:.1f}%)")
print(f"Види з англійською назвою (name_en): {has_name_en:,} ({has_name_en/total_taxa*100:.1f}%)")
print(f"Види з описом українською (desc_uk): {has_uk:,} ({has_uk/total_taxa*100:.1f}%)")
print(f"Види з описом англійською (desc_en): {has_en:,} ({has_en/total_taxa*100:.1f}%)")
print(f"Види з описами обома мовами:        {has_both:,} ({has_both/total_taxa*100:.1f}%)")
print(f"Види взагалі без основного опису:   {has_none:,} ({has_none/total_taxa*100:.1f}%)")

print("\nРозмір та заповненість тематичних розділів (статей):")
print(f"{'Поле / Розділ':<25} | {'Заповнено':<15} | {'Розмір (KB)':<12} | {'Сер. довжина (симв)'}")
print("-" * 75)

sections = [
    ('desc_uk', 'Опис (UK)'),
    ('desc_en', 'Опис (EN)'),
    ('lookalikes_uk', 'Двійники (UK)'),
    ('lookalikes_en', 'Двійники (EN)'),
    ('ecology_uk', 'Екологія/Біотоп (UK)'),
    ('ecology_en', 'Екологія/Біотоп (EN)'),
    ('toxicity_uk', 'Токсичність (UK)'),
    ('toxicity_en', 'Токсичність (EN)'),
    ('use_uk', 'Кулінарія/Застосування (UK)'),
    ('use_en', 'Кулінарія/Застосування (EN)'),
    ('props_cap', 'Властивості шапки'),
    ('props_hymenium', 'Властивості гіменофора'),
    ('props_stem', 'Властивості ніжки'),
    ('props_flesh', 'Властивості м\'якоті'),
    ('props_season', 'Сезон плодоношення'),
    ('props_habitat', 'Середовище існування'),
    ('wiki_url_uk', 'Посилання Wiki (UK)'),
    ('wiki_url_en', 'Посилання Wiki (EN)'),
]

for col, label in sections:
    s_bytes, cnt = taxa_col_sizes.get(col, (0, 0))
    cur.execute(f"SELECT AVG(LENGTH([{col}])) FROM taxa WHERE [{col}] IS NOT NULL AND [{col}] != '';")
    avg_len = cur.fetchone()[0] or 0
    pct = cnt / total_taxa * 100
    print(f"{label:<25} | {cnt:5,} ({pct:4.1f}%)   | {s_bytes/1024:<12.1f} | {avg_len:,.0f} симв.")

# Sum UK text vs EN text
cur.execute("""
    SELECT 
        SUM(COALESCE(LENGTH(desc_uk), 0) + COALESCE(LENGTH(lookalikes_uk), 0) + 
            COALESCE(LENGTH(ecology_uk), 0) + COALESCE(LENGTH(toxicity_uk), 0) + 
            COALESCE(LENGTH(use_uk), 0) + COALESCE(LENGTH(name_uk), 0)),
        SUM(COALESCE(LENGTH(desc_en), 0) + COALESCE(LENGTH(lookalikes_en), 0) + 
            COALESCE(LENGTH(ecology_en), 0) + COALESCE(LENGTH(toxicity_en), 0) + 
            COALESCE(LENGTH(use_en), 0) + COALESCE(LENGTH(name_en), 0))
    FROM taxa;
""")
total_uk_bytes, total_en_bytes = cur.fetchone()
print(f"\nСумарний обсяг статей за мовами:")
print(f"  Український контент: {total_uk_bytes / 1024:.1f} KB ({total_uk_bytes / (1024*1024):.2f} MB)")
print(f"  Англійський контент: {total_en_bytes / 1024:.1f} KB ({total_en_bytes / (1024*1024):.2f} MB)")

# Top 5 longest articles
cur.execute("""
    SELECT 
        scientific_name, name_uk,
        (COALESCE(LENGTH(desc_uk), 0) + COALESCE(LENGTH(desc_en), 0) +
         COALESCE(LENGTH(lookalikes_uk), 0) + COALESCE(LENGTH(lookalikes_en), 0) +
         COALESCE(LENGTH(ecology_uk), 0) + COALESCE(LENGTH(ecology_en), 0) +
         COALESCE(LENGTH(toxicity_uk), 0) + COALESCE(LENGTH(toxicity_en), 0) +
         COALESCE(LENGTH(use_uk), 0) + COALESCE(LENGTH(use_en), 0)) as total_text_len,
        LENGTH(desc_uk) as d_uk, LENGTH(desc_en) as d_en
    FROM taxa
    ORDER BY total_text_len DESC
    LIMIT 5;
""")
print("\nТоп-5 видів грибів з найдовшими статтями/описами:")
for sname, uname, tlen, duk, den in cur.fetchall():
    uname_str = f" ({uname})" if uname else ""
    print(f"  {sname}{uname_str}: {tlen:,} симв. (UK опис: {duk or 0:,}, EN опис: {den or 0:,})")

# Taxonomy & edibility breakdown
print("\n" + "=" * 70)
print("5. АНАЛІЗ ЇСТІВНОСТІ ТА СТАТУСІВ ГРИБІВ")
print("=" * 70)
cur.execute("SELECT edibility, count(*) FROM taxa GROUP BY edibility ORDER BY count(*) DESC;")
print("Розподіл за категоріями їстівності:")
for ed, c in cur.fetchall():
    print(f"  {ed or 'Не визначено':<25}: {c:5,} видів ({c/total_taxa*100:4.1f}%)")

conn.close()

# Indexes check
conn = sqlite3.connect(db_path)
cur = conn.cursor()
cur.execute("SELECT type, name, tbl_name FROM sqlite_master WHERE type='index';")
print("\n" + "=" * 70)
print("6. ІНДЕКСИ")
print("=" * 70)
for r in cur.fetchall():
    print(r)
conn.close()
