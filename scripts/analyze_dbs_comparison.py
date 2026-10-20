import sqlite3
import os
import sys

# Ensure UTF-8 output
sys.stdout.reconfigure(encoding='utf-8')

old_db_path = r"d:\Projects\Antigravity\projects\mushroom\scripts\data\addresses\addr_old.db"
new_db_path = r"d:\Projects\Antigravity\projects\mushroom\scripts\data\addresses\addr_new.db"

def analyze_db(name, path):
    print(f"\n{'='*35} {name} ({os.path.basename(path)}) {'='*35}")
    if not os.path.exists(path):
        print(f"Error: File not found {path}")
        return None
    
    size_mb = os.path.getsize(path) / (1024 * 1024)
    print(f"File size: {size_mb:.2f} MB")
    
    conn = sqlite3.connect(path)
    cur = conn.cursor()
    
    # 1. Tables and row counts
    cur.execute("SELECT name FROM sqlite_master WHERE type='table' ORDER BY name;")
    tables = [r[0] for r in cur.fetchall()]
    print(f"\nTables ({len(tables)}): {', '.join(tables)}")
    
    stats = {
        "size_mb": size_mb,
        "tables": tables
    }

    print("\n--- Row counts ---")
    for t in tables:
        if t.startswith("sqlite_"): continue
        try:
            cur.execute(f"SELECT count(*) FROM \"{t}\";")
            count = cur.fetchone()[0]
            print(f"  {t:20}: {count:,}")
            stats[f"count_{t}"] = count
        except Exception as e:
            print(f"  {t:20}: {e}")
            stats[f"count_{t}"] = "error"

    # 2. Integrity check
    try:
        cur.execute("PRAGMA quick_check;")
        qc = cur.fetchall()
        print(f"\nIntegrity check: {qc[:3]}")
        stats["quick_check"] = qc[:3]
    except Exception as e:
        print(f"\nIntegrity check error: {e}")
        stats["quick_check"] = str(e)

    # 3. Houses analysis
    if "houses" in tables:
        print("\n--- HOUSES ANALYSIS ---")
        cur.execute("SELECT count(*) FROM houses;")
        total_houses = cur.fetchone()[0]
        stats["total_houses"] = total_houses

        # Coordinates scale & bbox
        cur.execute("SELECT min(lat), max(lat), min(lon), max(lon) FROM houses;")
        min_lat, max_lat, min_lon, max_lon = cur.fetchone()
        print(f"  Total houses: {total_houses:,}")
        print(f"  Lat range: {min_lat} .. {max_lat}")
        print(f"  Lon range: {min_lon} .. {max_lon}")

        # Empty / whitespace numbers
        cur.execute("SELECT count(*) FROM houses WHERE number IS NULL OR trim(number) = '';")
        empty_numbers = cur.fetchone()[0]
        print(f"  Empty/blank numbers: {empty_numbers:,}")
        stats["empty_numbers"] = empty_numbers

        # Suspicious numbers (too long, strange chars, html/tags)
        cur.execute("SELECT count(*) FROM houses WHERE length(number) > 15;")
        long_numbers = cur.fetchone()[0]
        print(f"  Excessively long numbers (>15 chars): {long_numbers:,}")
        stats["long_numbers"] = long_numbers
        if long_numbers > 0:
            cur.execute("SELECT number, count(*) FROM houses WHERE length(number) > 15 GROUP BY number ORDER BY count(*) DESC LIMIT 5;")
            print("    Sample long numbers:", cur.fetchall())

        cur.execute("SELECT count(*) FROM houses WHERE number LIKE '%=%' OR number LIKE '%:%' OR number LIKE '%\n%' OR number LIKE '%\t%';")
        garbage_numbers = cur.fetchone()[0]
        print(f"  Garbage chars in numbers (=, :, newline, tab): {garbage_numbers:,}")
        stats["garbage_numbers"] = garbage_numbers
        if garbage_numbers > 0:
            cur.execute("SELECT number, count(*) FROM houses WHERE number LIKE '%=%' OR number LIKE '%:%' OR number LIKE '%\n%' OR number LIKE '%\t%' GROUP BY number LIMIT 5;")
            print("    Sample garbage numbers:", cur.fetchall())

        # Zero / invalid coordinates
        cur.execute("SELECT count(*) FROM houses WHERE lat = 0 OR lon = 0;")
        zero_coords = cur.fetchone()[0]
        print(f"  Zero coordinates (0, 0): {zero_coords:,}")
        stats["zero_coords"] = zero_coords

        # Out-of-bounds coordinates (Ukraine: lat 44.0 .. 52.6, lon 22.0 .. 40.5)
        div = 100_000.0 if abs(max_lat or 0) < 10_000_000 else 1_000_000.0
        cur.execute(f"SELECT count(*) FROM houses WHERE (lat / {div} < 44.0 OR lat / {div} > 52.6) OR (lon / {div} < 22.0 OR lon / {div} > 40.5);")
        oob_coords = cur.fetchone()[0]
        print(f"  Out of Ukraine bounding box coords (div={div}): {oob_coords:,}")
        stats["oob_coords"] = oob_coords
        if oob_coords > 0:
            cur.execute(f"SELECT lat/{div}, lon/{div}, number, street_id FROM houses WHERE (lat / {div} < 44.0 OR lat / {div} > 52.6) OR (lon / {div} < 22.0 OR lon / {div} > 40.5) LIMIT 5;")
            print("    Sample OOB coords:", cur.fetchall())

        # Duplicates in houses
        # Exact duplicate: (street_id, number, lat, lon)
        cur.execute("""
            SELECT count(*) FROM (
                SELECT street_id, number, lat, lon, count(*) c 
                FROM houses 
                GROUP BY street_id, number, lat, lon 
                HAVING c > 1
            );
        """)
        exact_dups = cur.fetchone()[0]
        print(f"  Exact duplicates (street_id, number, lat, lon): {exact_dups:,} duplicate groups")
        stats["exact_house_dups"] = exact_dups

        # Duplicate street_id + number with different coords
        cur.execute("""
            SELECT count(*) FROM (
                SELECT street_id, number, count(*) c, count(distinct lat || ',' || lon) cd
                FROM houses 
                GROUP BY street_id, number 
                HAVING cd > 1
            );
        """)
        diff_coord_dups = cur.fetchone()[0]
        print(f"  Houses with same street_id & number at DIFFERENT coordinates: {diff_coord_dups:,} groups")
        stats["diff_coord_house_dups"] = diff_coord_dups
        if diff_coord_dups > 0:
            cur.execute("""
                SELECT s.id, sn.name, h.number, count(*) c
                FROM houses h
                JOIN streets s ON s.id = h.street_id
                JOIN street_names sn ON sn.id = s.name_id
                GROUP BY h.street_id, h.number
                HAVING count(distinct h.lat || ',' || h.lon) > 1
                ORDER BY c DESC LIMIT 5;
            """)
            print("    Sample multi-coord houses:", cur.fetchall())

        # Duplicate coords (different or same houses at exact same coordinate)
        cur.execute("""
            SELECT count(*) FROM (
                SELECT lat, lon, count(*) c 
                FROM houses 
                GROUP BY lat, lon 
                HAVING c > 1
            );
        """)
        same_coord_dups = cur.fetchone()[0]
        print(f"  Identical coordinate collisions (lat, lon): {same_coord_dups:,} points")
        stats["same_coord_house_dups"] = same_coord_dups

    # 4. POI analysis
    if "poi_places" in tables:
        print("\n--- POI ANALYSIS ---")
        cur.execute("SELECT count(*) FROM poi_places;")
        total_pois = cur.fetchone()[0]
        stats["total_pois"] = total_pois
        print(f"  Total POIs: {total_pois:,}")

        cur.execute("SELECT min(lat), max(lat), min(lon), max(lon) FROM poi_places;")
        min_p_lat, max_p_lat, min_p_lon, max_p_lon = cur.fetchone()
        print(f"  Lat range: {min_p_lat} .. {max_p_lat}")
        print(f"  Lon range: {min_p_lon} .. {max_p_lon}")

        # Empty names
        cur.execute("SELECT count(*) FROM poi_places WHERE name IS NULL OR trim(name) = '';")
        empty_poi_names = cur.fetchone()[0]
        print(f"  Empty/blank POI names: {empty_poi_names:,}")
        stats["empty_poi_names"] = empty_poi_names

        # Unlinked street_id
        cur.execute("SELECT count(*) FROM poi_places WHERE street_id IS NULL OR street_id = 0;")
        unlinked_pois = cur.fetchone()[0]
        print(f"  Unlinked POIs (street_id IS NULL or 0): {unlinked_pois:,} ({(unlinked_pois*100.0/max(1, total_pois)):.1f}%)")
        stats["unlinked_pois"] = unlinked_pois

        # POI with house number
        cur.execute("SELECT count(*) FROM poi_places WHERE house_number IS NOT NULL AND trim(house_number) != '';")
        pois_with_house = cur.fetchone()[0]
        print(f"  POIs with explicit house_number: {pois_with_house:,}")
        stats["pois_with_house"] = pois_with_house

        # Exact duplicate POIs (name, category_id, lat, lon)
        cur.execute("""
            SELECT count(*) FROM (
                SELECT name, category_id, lat, lon, count(*) c 
                FROM poi_places 
                GROUP BY name, category_id, lat, lon 
                HAVING c > 1
            );
        """)
        exact_poi_dups = cur.fetchone()[0]
        print(f"  Exact duplicate POIs (name, category, lat, lon): {exact_poi_dups:,} groups")
        stats["exact_poi_dups"] = exact_poi_dups

        # Duplicate POI coords
        cur.execute("""
            SELECT count(*) FROM (
                SELECT lat, lon, count(*) c 
                FROM poi_places 
                GROUP BY lat, lon 
                HAVING c > 1
            );
        """)
        same_coord_pois = cur.fetchone()[0]
        print(f"  POI coordinate collisions (exact lat, lon): {same_coord_pois:,} points")
        stats["same_coord_pois"] = same_coord_pois

    # 5. Rudiments & Orphans (unused records)
    print("\n--- ORPHANS & RUDIMENTS ---")
    if "streets" in tables and "houses" in tables:
        has_pois = "poi_places" in tables
        poi_filter = "AND NOT EXISTS (SELECT 1 FROM poi_places p WHERE p.street_id = s.id)" if has_pois else ""
        cur.execute(f"""
            SELECT count(*) FROM streets s 
            WHERE NOT EXISTS (SELECT 1 FROM houses h WHERE h.street_id = s.id)
            {poi_filter};
        """)
        orphan_streets = cur.fetchone()[0]
        print(f"  Orphaned streets (no houses, no POIs): {orphan_streets:,}")
        stats["orphan_streets"] = orphan_streets

    if "street_names" in tables and "streets" in tables:
        cur.execute("""
            SELECT count(*) FROM street_names sn
            WHERE NOT EXISTS (SELECT 1 FROM streets s WHERE s.name_id = sn.id);
        """)
        orphan_names = cur.fetchone()[0]
        print(f"  Orphaned street_names (never referenced in streets): {orphan_names:,}")
        stats["orphan_names"] = orphan_names

    if "admin_places" in tables and "streets" in tables:
        cur.execute("""
            SELECT count(*) FROM admin_places ap
            WHERE NOT EXISTS (SELECT 1 FROM streets s WHERE s.place_id = ap.id OR s.region_id = ap.id OR s.country_id = ap.id)
              AND NOT EXISTS (SELECT 1 FROM admin_places ch WHERE ch.parent_id = ap.id);
        """)
        orphan_places = cur.fetchone()[0]
        print(f"  Orphaned admin_places (no streets, no children): {orphan_places:,}")
        stats["orphan_places"] = orphan_places

    if "poi_types" in tables and "poi_places" in tables:
        cur.execute("""
            SELECT count(*) FROM poi_types pt
            WHERE NOT EXISTS (SELECT 1 FROM poi_places pp WHERE pp.category_id = pt.id);
        """)
        orphan_types = cur.fetchone()[0]
        print(f"  Unused poi_types (no POI references): {orphan_types:,}")
        stats["orphan_types"] = orphan_types

    # 6. Foreign Key validation
    print("\n--- FOREIGN KEY INTEGRITY ---")
    if "houses" in tables and "streets" in tables:
        cur.execute("SELECT count(*) FROM houses h WHERE NOT EXISTS (SELECT 1 FROM streets s WHERE s.id = h.street_id);")
        broken_house_fk = cur.fetchone()[0]
        print(f"  Broken houses -> street_id FK: {broken_house_fk:,}")
        stats["broken_house_fk"] = broken_house_fk

    if "streets" in tables and "street_names" in tables:
        cur.execute("SELECT count(*) FROM streets s WHERE NOT EXISTS (SELECT 1 FROM street_names sn WHERE sn.id = s.name_id);")
        broken_street_name_fk = cur.fetchone()[0]
        print(f"  Broken streets -> name_id FK: {broken_street_name_fk:,}")
        stats["broken_street_name_fk"] = broken_street_name_fk

    if "streets" in tables and "admin_places" in tables:
        cur.execute("SELECT count(*) FROM streets s WHERE NOT EXISTS (SELECT 1 FROM admin_places ap WHERE ap.id = s.place_id);")
        broken_place_fk = cur.fetchone()[0]
        print(f"  Broken streets -> place_id FK: {broken_place_fk:,}")
        stats["broken_place_fk"] = broken_place_fk

    if "poi_places" in tables and "streets" in tables:
        cur.execute("SELECT count(*) FROM poi_places p WHERE p.street_id IS NOT NULL AND p.street_id != 0 AND NOT EXISTS (SELECT 1 FROM streets s WHERE s.id = p.street_id);")
        broken_poi_street_fk = cur.fetchone()[0]
        print(f"  Broken poi_places -> street_id FK: {broken_poi_street_fk:,}")
        stats["broken_poi_street_fk"] = broken_poi_street_fk

    if "poi_places" in tables and "poi_types" in tables:
        cur.execute("SELECT count(*) FROM poi_places p WHERE NOT EXISTS (SELECT 1 FROM poi_types pt WHERE pt.id = p.category_id);")
        broken_poi_type_fk = cur.fetchone()[0]
        print(f"  Broken poi_places -> category_id FK: {broken_poi_type_fk:,}")
        stats["broken_poi_type_fk"] = broken_poi_type_fk

    # 7. FTS synchronization
    print(f"\n--- FTS SYNC ---")
    if "search_fts" in tables and "streets" in tables:
        try:
            cur.execute("SELECT count(*) FROM search_fts;")
            fts_count = cur.fetchone()[0]
            cur.execute("SELECT count(*) FROM streets;")
            streets_count = cur.fetchone()[0]
            print(f"  search_fts count: {fts_count:,} vs streets count: {streets_count:,} (diff: {streets_count - fts_count:,})")
            stats["fts_diff"] = streets_count - fts_count
        except Exception as e:
            print(f"  search_fts count error: {e}")

    if "poi_fts" in tables and "poi_places" in tables:
        try:
            cur.execute("SELECT count(*) FROM poi_fts;")
            poi_fts_count = cur.fetchone()[0]
            cur.execute("SELECT count(*) FROM poi_places;")
            poi_places_count = cur.fetchone()[0]
            print(f"  poi_fts count: {poi_fts_count:,} vs poi_places count: {poi_places_count:,} (diff: {poi_places_count - poi_fts_count:,})")
            stats["poi_fts_diff"] = poi_places_count - poi_fts_count
        except Exception as e:
            print(f"  poi_fts count error: {e}")

    conn.close()
    return stats

if __name__ == "__main__":
    old_stats = analyze_db("OLD DATABASE", old_db_path)
    new_stats = analyze_db("NEW DATABASE", new_db_path)
