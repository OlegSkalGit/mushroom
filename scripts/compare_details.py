import sqlite3
import os
import sys

sys.stdout.reconfigure(encoding='utf-8')

conn_old = sqlite3.connect(r"d:\Projects\Antigravity\projects\mushroom\scripts\data\addresses\addr_old.db")
cur_old = conn_old.cursor()

print("--- Top places in OLD Volyn ---")
cur_old.execute("""
    SELECT ap.name, count(h.lat), avg(h.lat)/100000.0, avg(h.lon)/100000.0
    FROM houses h
    JOIN streets s ON s.id = h.street_id
    JOIN admin_places ap ON ap.id = s.place_id
    JOIN admin_places reg ON reg.id = s.region_id
    WHERE reg.name LIKE '%Волин%'
    GROUP BY ap.name
    ORDER BY count(h.lat) DESC
    LIMIT 15;
""")
for r in cur_old.fetchall():
    print(f"  {r[0]:25}: count={r[1]:,} (coords: {r[2]:.2f}, {r[3]:.2f})")

conn_old.close()
