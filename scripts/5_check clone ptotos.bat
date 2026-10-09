@echo off
cd /d "%~dp0"

python -c "import sqlite3; conn = sqlite3.connect('../downloads/mushrooms.db'); cur = conn.cursor(); cur.execute('SELECT taxon_id, original_url, COUNT(*) FROM photos GROUP BY taxon_id, original_url HAVING COUNT(*) > 1'); print('Found dublicates in db:', len(cur.fetchall()))"
pause
