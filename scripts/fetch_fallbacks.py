"""Download 3 nature photos from Wikimedia Commons featured pictures to bundle as offline fallbacks.

Usage: python3 -I fetch_fallbacks.py <out_dir>
Writes NN.jpg (1920px wide) plus NN.txt credit lines, same format the launcher writes.
"""
import html
import json
import random
import re
import sys
import urllib.parse
import urllib.request

API = "https://commons.wikimedia.org/w/api.php"
UA = "CalmLauncher/1.0 (personal Android TV home screen)"
CATS = ["Featured_pictures_of_mountains", "Featured_pictures_of_lakes", "Featured_pictures_of_forests"]


def get(url):
    req = urllib.request.Request(url, headers={"User-Agent": UA})
    with urllib.request.urlopen(req, timeout=30) as r:
        return r.read()


def strip(s):
    return re.sub(r"\s+", " ", html.unescape(re.sub(r"<[^>]+>", "", s or ""))).strip()


def main(out):
    random.seed(7)
    for n, cat in enumerate(CATS, 1):
        members = json.loads(get(f"{API}?action=query&format=json&list=categorymembers&cmtype=file&cmlimit=500&cmtitle=Category:{cat}"))["query"]["categorymembers"]
        random.shuffle(members)
        for m in members:
            q = (f"{API}?action=query&format=json&prop=imageinfo&iiprop=url|size|extmetadata&iiurlwidth=1920"
                 f"&iiextmetadatafilter=Artist|LicenseShortName|ObjectName&titles={urllib.parse.quote(m['title'])}")
            page = next(iter(json.loads(get(q))["query"]["pages"].values()))
            info = page["imageinfo"][0]
            w, h = info["width"], info["height"]
            if w < 1920 or not 1.3 <= w / h <= 2.1:
                continue
            if not urllib.parse.urlparse(info["thumburl"]).hostname.endswith(".wikimedia.org"):
                continue
            meta = info.get("extmetadata", {})
            name = strip(meta.get("ObjectName", {}).get("value")) or re.sub(r"\.\w+$", "", m["title"][5:])
            credit = f"{name}  ·  {strip(meta.get('Artist', {}).get('value'))}  ·  {strip(meta.get('LicenseShortName', {}).get('value'))} via Wikimedia Commons"
            with open(f"{out}/{n:02d}.jpg", "wb") as f:
                f.write(get(info["thumburl"]))
            with open(f"{out}/{n:02d}.txt", "w") as f:
                f.write(credit)
            print(f"{n:02d}: {credit}")
            break


if __name__ == "__main__":
    main(sys.argv[1])
