import sqlite3
import os
import io
import time
import requests
import concurrent.futures
from PIL import Image
import sys

sys.stdout.reconfigure(encoding='utf-8')

base_dir = r"d:\Projects\Antigravity\projects\mushroom\downloads\largest_photos"
dirs = {
    'orig_db': os.path.join(base_dir, 'original_from_db'),
    'web': os.path.join(base_dir, 'downloaded_web'),
    '350': os.path.join(base_dir, 'size_350px'),
    '250': os.path.join(base_dir, 'size_250px'),
    '150': os.path.join(base_dir, 'size_150px'),
}
for d in dirs.values():
    os.makedirs(d, exist_ok=True)

db_path = r"d:\Projects\Antigravity\projects\mushroom\downloads\mushrooms.db"
conn = sqlite3.connect(db_path)
cur = conn.cursor()

cur.execute("""
    SELECT p.id, p.width, p.height, LENGTH(p.image_data) as sz,
           t.scientific_name, COALESCE(t.name_uk, '-'), p.original_url, p.image_data
    FROM photos p
    JOIN taxa t ON p.taxon_id = t.id
    ORDER BY sz DESC
    LIMIT 20;
""")
rows = cur.fetchall()
conn.close()

def download_web(row):
    pid, w, h, sz, sname, uname, url, img_data = row
    try:
        r = requests.get(url, timeout=12)
        ext = os.path.splitext(url)[1] or '.jpg'
        return pid, r.content, ext
    except Exception as e:
        return pid, None, str(e)

print("Завантаження оригінальних файлів з інтернету (iNaturalist)...")
with concurrent.futures.ThreadPoolExecutor(max_workers=8) as ex:
    web_results = {r[0]: (r[1], r[2]) for r in ex.map(download_web, rows)}
print(f"Завантажено {len([w for w in web_results.values() if w[0]])} з 20 файлів.")

print("Обробка та генерація WebP-копій зі збереженням пропорцій...")
stats = []

for rank, row in enumerate(rows, 1):
    pid, orig_w, orig_h, db_sz, sname, uname, url, img_data = row
    safe_name = sname.replace(' ', '_').replace('/', '_')
    base_name = f"{rank:02d}_{safe_name}_id{pid}"
    
    # 1. Save original from DB
    p_orig_db = os.path.join(dirs['orig_db'], f"{base_name}_500px.webp")
    with open(p_orig_db, 'wb') as f:
        f.write(img_data)
        
    # 2. Save downloaded web file
    web_content, ext = web_results.get(pid, (None, '.jpg'))
    if web_content:
        p_web = os.path.join(dirs['web'], f"{base_name}{ext}")
        with open(p_web, 'wb') as f:
            f.write(web_content)
            
    source_img = Image.open(io.BytesIO(img_data))
    if source_img.mode in ('RGBA', 'P'):
        source_img = source_img.convert('RGB')
        
    row_stat = {
        'rank': rank, 'id': pid, 'name': sname, 'name_uk': uname,
        'orig_dim': f"{orig_w}x{orig_h}", 'orig_sz': db_sz
    }
    
    for target_dim, d_key in [(350, '350'), (250, '250'), (150, '150')]:
        ratio = min(target_dim / orig_w, target_dim / orig_h)
        new_w = max(1, round(orig_w * ratio))
        new_h = max(1, round(orig_h * ratio))
        
        resized = source_img.resize((new_w, new_h), Image.Resampling.LANCZOS)
        out = io.BytesIO()
        resized.save(out, format='WEBP', quality=80, method=6)
        out_bytes = out.getvalue()
        
        fname = f"{base_name}_{new_w}x{new_h}.webp"
        p_out = os.path.join(dirs[d_key], fname)
        with open(p_out, 'wb') as f:
            f.write(out_bytes)
            
        row_stat[f'sz_{target_dim}'] = len(out_bytes)
        row_stat[f'dim_{target_dim}'] = f"{new_w}x{new_h}"
        
    stats.append(row_stat)

print("\n" + "=" * 105)
header = f"{'№':<3} | {'Вид гриба':<28} | {'Оригінал (500px)':<17} | {'350px':<16} | {'250px':<16} | {'150px':<16}"
print(header)
print("-" * 105)
for s in stats:
    line = f"{s['rank']:<3} | {s['name'][:28]:<28} | {s['orig_dim']:<8} {s['orig_sz']/1024:5.1f} KB  | {s['dim_350']:<8} {s['sz_350']/1024:4.1f} KB  | {s['dim_250']:<8} {s['sz_250']/1024:4.1f} KB  | {s['dim_150']:<8} {s['sz_150']/1024:4.1f} KB"
    print(line)

avg_orig = sum(s['orig_sz'] for s in stats) / len(stats)
avg_350 = sum(s['sz_350'] for s in stats) / len(stats)
avg_250 = sum(s['sz_250'] for s in stats) / len(stats)
avg_150 = sum(s['sz_150'] for s in stats) / len(stats)

print("=" * 105)
print(f"СЕРЕДНІ ЗНАЧЕННЯ:                  |          {avg_orig/1024:5.1f} KB  |          {avg_350/1024:4.1f} KB  |          {avg_250/1024:4.1f} KB  |          {avg_150/1024:4.1f} KB")
print(f"ЗМЕНШЕННЯ ВІД ОРИГІНАЛУ:           |             0.0%  |         -{(1 - avg_350/avg_orig)*100:4.1f}%  |         -{(1 - avg_250/avg_orig)*100:4.1f}%  |         -{(1 - avg_150/avg_orig)*100:4.1f}%")
