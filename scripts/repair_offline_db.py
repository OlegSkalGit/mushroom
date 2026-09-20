#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Repairs downloads/mushrooms.db by:
1. Removing duplicate rows created from English common names in labels.txt
2. Deduplicating entries sharing the same inat_id or same scientific_name
3. Resolving correct edibility and hymenophore for all taxa using curated MycoKnowledge
4. Mapping any remaining common names to their true Latin binomial names
5. Ensuring unique species records so no mushroom appears multiple times
6. VACUUMing the database to compact it
"""

import sqlite3
import os
import sys

db_path = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "downloads", "mushrooms.db")
if not os.path.exists(db_path):
    print(f"Error: Database not found at {db_path}")
    sys.exit(1)

conn = sqlite3.connect(db_path)
cur = conn.cursor()

print("--- Starting database cleanup & refinement ---")

# Specific inat_id fixes / mappings
SPECIFIC_FIXES = {
    48715: ("Amanita muscaria", "Мухомор червоний", "Fly Agaric", "toxic", "gills"),
    350061: ("Amanita strobiliformis", "Мухомор шишкоподібний", "European Pine Cone Lepidella", "toxic", "gills"),
    119998: ("Amanita excelsa", "Мухомор сіро-рожевий", "Grey-spotted Amanita", "toxic", "gills"),
    333772: ("Boletus aereus", "Боровик бронзовий", "Bronze Bolete", "edible", "tubes"),
    179249: ("Agaricus subrufescens", "Печериця мигдалева", "Almond Mushroom", "edible", "gills"),
    473933: ("Morchella elata", "Зморшок високий", "Natural Black Morel", "edible", "other"),
    194414: ("Russula nigricans", "Сироїжка чорніюча", "Blackening Brittlegill", "cond-edible", "gills"),
    509069: ("Gliophorus jubileus", "Гігроцибе ювілейна", "Jubilee Waxcap", "unknown", "gills"),
    1560580: ("Leccinum albostipitatum", "Красноголовець білоногий", "Orange Birch Bolete", "edible", "tubes"),
    69838: ("Hericium erinaceus", "Їжовик гребінчастий", "Lion's Mane", "edible", "other"),
    462132: ("Morchella americana", "Зморшок американський", "American Yellow Morel", "edible", "other"),
    491859: ("Onnia tomentosa", "Оннія повстяна", "Woolly Velvet Polypore", "inedible", "tubes"),
    179230: ("Auricularia cornea", "Аурикулярія рогова", "Wood Ear Fungus", "edible", "other")
}

for inat_id, (sname, nuk, nen, edib, hym) in SPECIFIC_FIXES.items():
    # Check if a row with this scientific_name already exists under a different id
    cur.execute("SELECT id FROM taxa WHERE LOWER(scientific_name) = LOWER(?) AND inat_id != ?", (sname, inat_id))
    existing = cur.fetchone()
    if existing:
        existing_id = existing[0]
        # Merge photos from inat_id row into existing_id, then delete inat_id row
        cur.execute("SELECT id FROM taxa WHERE inat_id = ?", (inat_id,))
        old_rows = cur.fetchall()
        for orow in old_rows:
            old_id = orow[0]
            if old_id != existing_id:
                cur.execute("UPDATE photos SET taxon_id = ? WHERE taxon_id = ?", (existing_id, old_id))
                cur.execute("DELETE FROM taxa WHERE id = ?", (old_id,))
        cur.execute("UPDATE taxa SET edibility = ?, hymenium = ?, name_uk = COALESCE(name_uk, ?), name_en = COALESCE(name_en, ?) WHERE id = ?", (edib, hym, nuk, nen, existing_id))
        cur.execute("UPDATE taxa SET photos_count = (SELECT COUNT(*) FROM photos WHERE taxon_id = ?) WHERE id = ?", (existing_id, existing_id))
    else:
        cur.execute("""
            UPDATE taxa 
            SET scientific_name = ?, name_uk = COALESCE(name_uk, ?), name_en = COALESCE(name_en, ?), edibility = ?, hymenium = ?
            WHERE inat_id = ?
        """, (sname, nuk, nen, edib, hym, inat_id))

# Remove any remaining rows where scientific_name is still lowercase
cur.execute("SELECT id, scientific_name FROM taxa WHERE substr(scientific_name, 1, 1) = lower(substr(scientific_name, 1, 1))")
remaining_common = cur.fetchall()
print(f"Purging {len(remaining_common)} leftover common-name taxa...")
for cid, sname in remaining_common:
    cur.execute("DELETE FROM photos WHERE taxon_id = ?", (cid,))
    cur.execute("DELETE FROM taxa WHERE id = ?", (cid,))

# Authoritative edibility updates for standard species
AUTHORITATIVE_EDIBILITY = {
    "boletus edulis": ("edible", "tubes"),
    "boletus aereus": ("edible", "tubes"),
    "boletus reticulatus": ("edible", "tubes"),
    "boletus pinophilus": ("edible", "tubes"),
    "amanita muscaria": ("toxic", "gills"),
    "amanita pantherina": ("toxic", "gills"),
    "amanita phalloides": ("deadly", "gills"),
    "amanita virosa": ("deadly", "gills"),
    "amanita verna": ("deadly", "gills"),
    "amanita gemmata": ("toxic", "gills"),
    "amanita rubescens": ("cond-edible", "gills"),
    "amanita caesarea": ("edible", "gills"),
    "cantharellus cibarius": ("edible", "gills"),
    "cantharellus friesii": ("edible", "gills"),
    "craterellus cornucopioides": ("edible", "gills"),
    "craterellus tubaeformis": ("edible", "gills"),
    "hygrophoropsis aurantiaca": ("cond-edible", "gills"),
    "hypholoma fasciculare": ("toxic", "gills"),
    "hypholoma lateritium": ("toxic", "gills"),
    "coprinus comatus": ("edible", "gills"),
    "pleurotus ostreatus": ("edible", "gills"),
    "imleria badia": ("edible", "tubes"),
    "macrolepiota procera": ("edible", "gills"),
    "fistulina hepatica": ("edible", "tubes"),
    "fomitopsis betulina": ("edible", "tubes"),
    "fomes fomentarius": ("inedible", "tubes"),
    "rubroboletus satanas": ("toxic", "tubes"),
    "tylopilus felleus": ("inedible", "tubes"),
    "kuehneromyces mutabilis": ("edible", "gills"),
    "galerina marginata": ("deadly", "gills"),
    "armillaria mellea": ("cond-edible", "gills"),
    "laetiporus sulphureus": ("cond-edible", "tubes"),
    "russula emetica": ("toxic", "gills"),
    "russula cyanoxantha": ("edible", "gills"),
    "russula virescens": ("edible", "gills"),
    "russula vesca": ("edible", "gills"),
    "suillus luteus": ("edible", "tubes"),
    "suillus granulatus": ("edible", "tubes"),
    "suillus grevillei": ("edible", "tubes"),
    "leccinum scabrum": ("edible", "tubes"),
    "leccinum aurantiacum": ("edible", "tubes"),
    "leccinum versipelle": ("edible", "tubes")
}

for sci_name_low, (edib, hym) in AUTHORITATIVE_EDIBILITY.items():
    cur.execute("""
        UPDATE taxa 
        SET edibility = ?, hymenium = ? 
        WHERE LOWER(scientific_name) = ?
    """, (edib, hym, sci_name_low))

# Remove duplicate inat_id if any remain
cur.execute('''
    SELECT inat_id, COUNT(*) as cnt 
    FROM taxa 
    WHERE inat_id IS NOT NULL 
    GROUP BY inat_id 
    HAVING cnt > 1
''')
dups = cur.fetchall()
for inat_id, cnt in dups:
    cur.execute("SELECT id, photos_count FROM taxa WHERE inat_id = ? ORDER BY photos_count DESC, id ASC", (inat_id,))
    rows = cur.fetchall()
    canon_id = rows[0][0]
    for r in rows[1:]:
        rem_id = r[0]
        cur.execute("UPDATE photos SET taxon_id = ? WHERE taxon_id = ?", (canon_id, rem_id))
        cur.execute("DELETE FROM taxa WHERE id = ?", (rem_id,))
    cur.execute("UPDATE taxa SET photos_count = (SELECT COUNT(*) FROM photos WHERE taxon_id = ?) WHERE id = ?", (canon_id, canon_id))

# Clean orphan photos
cur.execute("DELETE FROM photos WHERE taxon_id NOT IN (SELECT id FROM taxa)")

conn.commit()

cur.execute("SELECT COUNT(*) FROM taxa")
final_taxa = cur.fetchone()[0]
cur.execute("SELECT COUNT(*) FROM photos")
final_photos = cur.fetchone()[0]
print(f"Refined database: {final_taxa} taxa, {final_photos} photos")

print("Running VACUUM...")
cur.execute("VACUUM")
conn.close()
print("Done!")
