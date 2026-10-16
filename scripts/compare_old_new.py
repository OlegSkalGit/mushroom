import sqlite3
import sys

sys.stdout.reconfigure(encoding='utf-8')

conn_old = sqlite3.connect(r'd:\Projects\Antigravity\projects\mushroom\scripts\data\addresses\addr_old.db')
cur_old = conn_old.cursor()
conn_new = sqlite3.connect(r'd:\Projects\Antigravity\projects\mushroom\scripts\data\addresses\addr_new.db')
cur_new = conn_new.cursor()

# 1. Compare Kyiv oblast (id/name)
print("=== СХЕМА ТА БУДИНКИ У КИЇВСЬКІЙ ТА ЛУГАНСЬКІЙ ОБЛАСТЯХ ===")

# Kyiv in OLD
cur_old.execute("""
    SELECT count(*) 
    FROM houses h 
    JOIN streets s ON s.id = h.street_id 
    JOIN admin_places ap ON ap.id = s.place_id 
    WHERE ap.name LIKE '%Київ%' OR s.place_id IN (
        WITH RECURSIVE pt(id) AS (
            SELECT id FROM admin_places WHERE name = 'Київська область'
            UNION ALL
            SELECT a.id FROM admin_places a JOIN pt ON a.parent_id = pt.id
        ) SELECT id FROM pt
    );
""")
print("OLD Kyiv houses:", cur_old.fetchone()[0])

# Kyiv in NEW
cur_new.execute("""
    SELECT count(*) 
    FROM houses h 
    JOIN streets s ON s.id = h.street_id 
    JOIN admin_places ap ON ap.id = s.place_id 
    WHERE ap.name LIKE '%Київ%' OR s.place_id IN (
        WITH RECURSIVE pt(id) AS (
            SELECT id FROM admin_places WHERE name = 'Київська область'
            UNION ALL
            SELECT a.id FROM admin_places a JOIN pt ON a.parent_id = pt.id
        ) SELECT id FROM pt
    );
""")
print("NEW Kyiv houses:", cur_new.fetchone()[0])

# Sample houses that are in NEW Kyiv but NOT in OLD Kyiv
cur_new.execute("""
    WITH RECURSIVE pt(id) AS (
        SELECT id FROM admin_places WHERE name = 'Київська область'
        UNION ALL
        SELECT a.id FROM admin_places a JOIN pt ON a.parent_id = pt.id
    )
    SELECT ap.name, sn.name, h.number, h.lat, h.lon
    FROM houses h
    JOIN streets s ON s.id = h.street_id
    JOIN street_names sn ON sn.id = s.name_id
    JOIN admin_places ap ON ap.id = s.place_id
    WHERE s.place_id IN (SELECT id FROM pt)
    LIMIT 25;
""")
print("\nSample 10 houses in NEW Kyiv:")
for r in cur_new.fetchall()[:10]:
    print(f"  {r[0]}, {r[1]}, № {r[2]} ({r[3]/100000.0}, {r[4]/100000.0})")

# Look at numbers format in OLD vs NEW
cur_old.execute("SELECT number, count(*) FROM houses GROUP BY number ORDER BY count(*) DESC LIMIT 15;")
print("\nTop 15 house numbers in OLD:")
for r in cur_old.fetchall():
    print(f"  '{r[0]}': {r[1]:,}")

cur_new.execute("SELECT number, count(*) FROM houses GROUP BY number ORDER BY count(*) DESC LIMIT 15;")
print("\nTop 15 house numbers in NEW:")
for r in cur_new.fetchall():
    print(f"  '{r[0]}': {r[1]:,}")

# Check bounding boxes of houses in OLD vs NEW for Luhansk
print("\n--- Luhansk Coordinates Bounding Box ---")
cur_old.execute("""
    WITH RECURSIVE pt(id) AS (
        SELECT id FROM admin_places WHERE name = 'Луганська область'
        UNION ALL
        SELECT a.id FROM admin_places a JOIN pt ON a.parent_id = pt.id
    )
    SELECT min(lat)/100000.0, max(lat)/100000.0, min(lon)/100000.0, max(lon)/100000.0, count(*)
    FROM houses h JOIN streets s ON s.id = h.street_id WHERE s.place_id IN (SELECT id FROM pt);
""")
print("OLD Luhansk:", cur_old.fetchone())

cur_new.execute("""
    WITH RECURSIVE pt(id) AS (
        SELECT id FROM admin_places WHERE name = 'Луганська область'
        UNION ALL
        SELECT a.id FROM admin_places a JOIN pt ON a.parent_id = pt.id
    )
    SELECT min(lat)/100000.0, max(lat)/100000.0, min(lon)/100000.0, max(lon)/100000.0, count(*)
    FROM houses h JOIN streets s ON s.id = h.street_id WHERE s.place_id IN (SELECT id FROM pt);
""")
print("NEW Luhansk:", cur_new.fetchone())

conn_old.close()
conn_new.close()
