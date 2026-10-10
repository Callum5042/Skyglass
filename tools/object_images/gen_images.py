#!/usr/bin/env python3
"""Generate the photos shown on the details cards (app/src/main/assets/object_images/).

Reads sources.src (next to this script; hand-curated, see its header), downloads each image from
NASA (public domain) or from ESA/Hubble, ESA/Webb, ESO or NOIRLab (CC BY 4.0), crops it if the
line asks, scales it so its longer side is at most MAX_SIDE pixels, and writes it as WebP into
app/src/main/assets/object_images/ together with the index app/src/main/assets/object_images.txt.

Downloads are kept in tools/object_images/.cache/ (git-ignored), so a rerun only fetches new URLs.

Needs Pillow with WebP support (pip install pillow). Usage: python gen_images.py

Exits non-zero and writes nothing on any validation error: a host or licence not on the allow
lists, a deep-sky key that is not an id in deep_sky.txt, an unknown body, a moon of something that
is not a planet, a duplicate key, a deep-sky object that is neither imaged nor skipped, a body
without an image, or the images together exceeding the size budget.
"""

import hashlib
import io
import os
import re
import sys
import threading
import urllib.parse
import urllib.request
from concurrent.futures import ThreadPoolExecutor

from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
ASSETS = os.path.join(HERE, "..", "..", "app", "src", "main", "assets")
IMAGE_DIR = os.path.join(ASSETS, "object_images")
INDEX = os.path.join(ASSETS, "object_images.txt")
DEEP_SKY = os.path.join(ASSETS, "deep_sky.txt")
SOURCES = os.path.join(HERE, "sources.src")
CACHE = os.path.join(HERE, ".cache")

MAX_SIDE = 768
WEBP_QUALITY = 80
BUDGET_BYTES = 16 * 1024 * 1024
WARN_FILE_BYTES = 150 * 1024
DOWNLOADS = 6  # at once

# Hosts are matched as the whole name or a subdomain of one of these.
HOSTS = {
    "nasa.gov": "Public domain",
    "esahubble.org": "CC BY 4.0",
    "esawebb.org": "CC BY 4.0",
    "eso.org": "CC BY 4.0",
    "noirlab.edu": "CC BY 4.0",
}
LICENCES = {"Public domain", "CC BY 4.0"}

# com.skyglass.astro.SkyBody; ObjectImagesTest checks the generated index against the real enum.
BODIES = ["SUN", "MERCURY", "VENUS", "MARS", "JUPITER", "SATURN", "URANUS", "NEPTUNE", "MOON"]
PLANETS = set(BODIES) - {"SUN", "MOON"}


def fetch(url):
    os.makedirs(CACHE, exist_ok=True)
    path = os.path.join(CACHE, hashlib.sha1(url.encode()).hexdigest())
    if os.path.exists(path):
        with open(path, "rb") as f:
            return f.read()
    req = urllib.request.Request(url, headers={"User-Agent": "Skyglass gen_images.py"})
    with urllib.request.urlopen(req, timeout=120) as r:
        data = r.read()
    part = f"{path}.{threading.get_ident()}.part"  # two keys may share a URL
    with open(part, "wb") as f:
        f.write(data)
    os.replace(part, path)
    return data


def host_licence(url):
    host = urllib.parse.urlparse(url).hostname or ""
    for allowed, licence in HOSTS.items():
        if host == allowed or host.endswith("." + allowed):
            return licence
    return None


def deep_sky_ids():
    ids = []
    with open(DEEP_SKY, encoding="utf-8") as f:
        for line in f:
            if line.strip() and not line.startswith("#"):
                ids.append(line.split("|")[0].split(";")[0].strip())
    return ids


def slug(key):
    return re.sub(r"[^a-z0-9]+", "_", key.lstrip("@").lower()).strip("_")


def read_sources(errors):
    """Returns (images, skipped): images as dicts in file order, skipped as {key: reason}."""
    images, skipped = [], {}
    with open(SOURCES, encoding="utf-8") as f:
        for n, raw in enumerate(f, 1):
            line = raw.strip()
            if not line or line.startswith("#"):
                continue
            fields = [x.strip() for x in line.split("|")]
            where = f"sources.src:{n}"
            if fields[0] == "skip":
                if len(fields) != 3 or not fields[2]:
                    errors.append(f"{where}: expected skip|key|reason")
                else:
                    skipped[fields[1]] = fields[2]
                continue
            if len(fields) != 6:
                errors.append(f"{where}: expected 6 fields, got {len(fields)}")
                continue
            key, url, page, credit, licence, crop = fields
            box = None
            if crop:
                try:
                    box = tuple(float(x) for x in crop.split(","))
                    assert len(box) == 4 and 0 <= box[0] < box[2] <= 1 and 0 <= box[1] < box[3] <= 1
                except (ValueError, AssertionError):
                    errors.append(f"{where}: crop must be x0,y0,x1,y1 fractions, got {crop}")
                    continue
            images.append({"key": key, "url": url, "page": page, "credit": credit, "licence": licence,
                           "crop": box, "where": where})
    return images, skipped


def validate(images, skipped, errors):
    ids = deep_sky_ids()
    seen = set()
    for im in images:
        key, where = im["key"], im["where"]
        if key in seen:
            errors.append(f"{where}: duplicate key {key}")
        seen.add(key)
        if key.startswith("@"):
            body, _, moon = key[1:].partition("/")
            if body not in BODIES:
                errors.append(f"{where}: unknown body {body}")
            elif "/" in key and (body not in PLANETS or not moon):
                errors.append(f"{where}: a moon needs a planet and a name: {key}")
        elif key not in ids:
            errors.append(f"{where}: {key} is not an id in deep_sky.txt")
        expected = host_licence(im["url"])
        if expected is None:
            errors.append(f"{where}: host not allowed: {im['url']}")
        elif im["licence"] != expected:
            errors.append(f"{where}: {im['url']} is {expected}, not {im['licence']}")
        if host_licence(im["page"]) is None:
            errors.append(f"{where}: source page host not allowed: {im['page']}")
        if im["licence"] not in LICENCES:
            errors.append(f"{where}: licence not allowed: {im['licence']}")
        if not im["credit"]:
            errors.append(f"{where}: no credit")
    for key in skipped:
        if key not in ids:
            errors.append(f"skip of {key}: not an id in deep_sky.txt")
        if key in seen:
            errors.append(f"{key} is both imaged and skipped")
    for key in ids:
        if key not in seen and key not in skipped:
            errors.append(f"{key} is neither imaged nor skipped")
    for body in BODIES:
        if "@" + body not in seen:
            errors.append(f"@{body} has no image")


def convert(data, crop):
    image = Image.open(io.BytesIO(data))
    image.load()
    image = image.convert("RGB")
    if crop:
        w, h = image.size
        image = image.crop((round(crop[0] * w), round(crop[1] * h), round(crop[2] * w), round(crop[3] * h)))
    scale = MAX_SIDE / max(image.size)
    if scale < 1:
        image = image.resize((max(1, round(image.width * scale)), max(1, round(image.height * scale))), Image.LANCZOS)
    out = io.BytesIO()
    image.save(out, "WEBP", quality=WEBP_QUALITY, method=6)
    return out.getvalue(), image.size


def main():
    errors, warnings = [], []
    images, skipped = read_sources(errors)
    validate(images, skipped, errors)
    if errors:
        print("\n".join("error: " + e for e in errors), file=sys.stderr)
        return 1

    def download(im):
        try:
            return fetch(im["url"])
        except Exception as e:  # noqa: BLE001 - report every failure, then stop
            return e

    with ThreadPoolExecutor(DOWNLOADS) as pool:
        downloads = list(pool.map(download, images))
    outputs = []
    for im, data in zip(images, downloads):
        try:
            if isinstance(data, Exception):
                raise data
            webp, (w, h) = convert(data, im["crop"])
        except Exception as e:  # noqa: BLE001
            errors.append(f"{im['where']}: {im['url']}: {e}")
            continue
        if len(webp) > WARN_FILE_BYTES:
            warnings.append(f"{im['key']}: {len(webp) // 1024} KB")
        outputs.append((im, slug(im["key"]) + ".webp", webp, w, h))
    total = sum(len(o[2]) for o in outputs)
    if total > BUDGET_BYTES:
        errors.append(f"images total {total / 1e6:.1f} MB, over the {BUDGET_BYTES / 1e6:.1f} MB budget")
    if len({o[1] for o in outputs}) != len(outputs):
        errors.append("two keys give the same file name")
    for w in warnings:
        print("warning: " + w, file=sys.stderr)
    if errors:
        print("\n".join("error: " + e for e in errors), file=sys.stderr)
        return 1

    os.makedirs(IMAGE_DIR, exist_ok=True)
    keep = {o[1] for o in outputs}
    for name in os.listdir(IMAGE_DIR):
        if name.endswith(".webp") and name not in keep:
            os.remove(os.path.join(IMAGE_DIR, name))
    for _, name, webp, _, _ in outputs:
        with open(os.path.join(IMAGE_DIR, name), "wb") as f:
            f.write(webp)
    with open(INDEX, "w", encoding="utf-8", newline="\n") as f:
        f.write("# Photos for the details cards: key|image|width|height|credit|licence\n")
        f.write("#   key      a deep-sky object's first id (as in deep_sky.txt), @BODY for the Sun, Moon or a\n")
        f.write("#            planet (com.skyglass.astro.SkyBody), or @PLANET/Name for one of its moons\n")
        f.write("#            (listed in gallery order)\n")
        f.write("#   image    WebP file in object_images/, width and height in pixels\n")
        f.write("#   credit   as the source asks; licence is Public domain (NASA) or CC BY 4.0\n")
        f.write("# Generated by tools/object_images/gen_images.py from tools/object_images/sources.src\n")
        f.write("# (which lists each image's source page); do not edit by hand. The images are cropped\n")
        f.write("# and/or scaled down from the originals (see object_images/LICENSE.txt).\n")
        for im, name, _, w, h in outputs:
            f.write(f"{im['key']}|{name}|{w}|{h}|{im['credit']}|{im['licence']}\n")
    ids = deep_sky_ids()
    dso = sum(1 for o in outputs if not o[0]["key"].startswith("@"))
    print(f"{len(outputs)} images ({dso} of {len(ids)} deep-sky objects, {len(skipped)} skipped), "
          f"{total / 1e6:.1f} MB")
    return 0


if __name__ == "__main__":
    sys.exit(main())
