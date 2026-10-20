import sqlite3
import os
import sys

sys.stdout.reconfigure(encoding='utf-8')

path = 'scripts/data/addresses/addr.db'
conn = sqlite3.connect(path)
cur = conn.cursor()

print(f"File size: {os.path.getsize(path) / (1024*1024):.2f} MB")

# Check journal mode
cur.execute("PRAGMA journal_mode")
print("Journal mode:", cur.fetchone()[0])

# Check tables
cur.execute("SELECT name FROM sqlite_master WHERE type='table'")
tables = [r[0] for r in cur.fetchall()]
print("Tables:", tables)

for t in ["admin_places", "street_names", "streets", "houses", "poi_places", "poi_types"]:
    if t in tables:
        cur.execute(f"SELECT count(*) FROM {t}")
        print(f"  {t:<15}: {cur.fetchone()[0]:,}")

# Check Kyiv
cur.execute("SELECT count(*) FROM houses h JOIN streets s ON s.id = h.street_id WHERE s.region_id = 27")
print("Kyiv houses (region_id=27):", cur.fetchone()[0])

# Check indexes
cur.execute("SELECT name, tbl_name FROM sqlite_master WHERE type='index'")
indexes = cur.fetchall()
print(f"Total indexes: {len(indexes)}")
for idx in indexes:
    print(f"  {idx[1]:<15} -> {idx[0]}")

# Check freelist count
cur.execute("PRAGMA freelist_count")
print("Freelist pages:", cur.fetchone()[0])

conn.close()
