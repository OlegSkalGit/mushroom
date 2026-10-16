import urllib.request, urllib.parse, ssl, sys, json

ctx = ssl.create_default_context()
ctx.check_hostname = False
ctx.verify_mode = ssl.CERT_NONE

url = 'https://overpass.openstreetmap.fr/api/interpreter'
for rid, name in [(72380, 'Lviv'), (71248, 'Kyiv'), (71254, 'Kharkiv'), (72634, 'Odesa'), (90726, 'Vinnytsia')]:
    aid = 3600000000 + rid
    q = f'[out:json][timeout:15]; area({aid})->.a; (node["addr:housenumber"](area.a);); out count;'
    data = urllib.parse.urlencode({'data': q}).encode('utf-8')
    req = urllib.request.Request(url, data=data, headers={'User-Agent': 'Mushroom-Test'})
    try:
        resp = urllib.request.urlopen(req, context=ctx, timeout=20)
        res = json.loads(resp.read().decode('utf-8'))
        tags = res.get('elements', [{}])[0].get('tags', {})
        print(f'{name} ({rid}): {tags}')
    except Exception as e:
        print(f'{name} ({rid}): ERROR {e}')
