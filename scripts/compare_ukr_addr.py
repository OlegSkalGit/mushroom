import sqlite3
import sys

sys.stdout.reconfigure(encoding='utf-8')

conn1 = sqlite3.connect('scripts/data/addresses/ukraine.db')
cur1 = conn1.cursor()

conn2 = sqlite3.connect('scripts/data/addresses/addr.db')
cur2 = conn2.cursor()

for tbl in ['admin_places', 'street_names', 'streets', 'houses', 'poi_places', 'poi_types']:
    cur1.execute(f"SELECT count(*) FROM {tbl}")
    c1 = cur1.fetchone()[0]
    cur2.execute(f"SELECT count(*) FROM {tbl}")
    c2 = cur2.fetchone()[0]
    print(f"{tbl:<15} | ukraine.db: {c1:10,} | addr.db: {c2:10,} | diff: {c2 - c1}")

print("\nIndexes in ukraine.db:")
cur1.execute("SELECT name, tbl_name FROM sqlite_master WHERE type='index'")
for idx in cur1.fetchall():
    print(f"  {idx[1]:<15} -> {idx[0]}")

print("\nIndexes in addr.db:")
cur2.execute("SELECT name, tbl_name FROM sqlite_master WHERE type='index'")
for idx in cur2.fetchall():
    print(f"  {idx[1]:<15} -> {idx[0]}")

conn1.close()
conn2.close()
