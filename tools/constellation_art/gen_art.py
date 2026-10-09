#!/usr/bin/env python3
"""Fetches Stellarium's constellation artwork and writes the assets the app bundles.

    python tools/constellation_art/gen_art.py

Downloads (network needed; the app itself has none):
  - skycultures/modern/index.json and the illustrations it names, from the Stellarium repository at
    the pinned commit STELLARIUM_COMMIT. Illustrations by Johan Meuris, Free Art License 1.3;
    the PNGs are copied byte for byte, unmodified.
  - Hipparcos positions of the anchor stars (CDS VizieR, catalogue I/239/hip_main), because the
    anchors are given as HIP numbers and the app's star catalogue is the Bright Star Catalogue.

Writes  app/src/main/assets/constellation_art.txt, app/src/main/assets/constellation_art/*.png
Exits non-zero (and writes nothing) if an image's size disagrees with the index, an anchor star is
missing from Hipparcos, or a constellation of constellations.txt is not covered by exactly one image.
"""
import json
import math
import struct
import sys
import urllib.parse
import urllib.request
from pathlib import Path

HERE = Path(__file__).resolve().parent
ASSETS = HERE.parents[1] / "app" / "src" / "main" / "assets"
ART_DIR = ASSETS / "constellation_art"

STELLARIUM_COMMIT = "daace2add6a1bf886e8ee1934f51e9c69f818d18"
CULTURE_URL = f"https://raw.githubusercontent.com/Stellarium/stellarium/{STELLARIUM_COMMIT}/skycultures/modern/"
VIZIER_URL = "https://vizier.cds.unistra.fr/viz-bin/asu-tsv"

# Constellations drawn by another constellation's image: the Argo Navis ship (Carina's image) is also
# Puppis and Vela, and Ophiuchus' image holds the serpent of Serpens.
ALSO_COVERS = {"Car": ["Pup", "Vel"], "Oph": ["Ser"]}

# Hipparcos positions are for epoch J1991.25; the asset gives J2000 (as deep_sky.txt does).
HIPPARCOS_EPOCH = 1991.25

# An anchor with no Bright Star Catalogue star this close is reported (not an error: some are fainter).
BSC_MATCH_ARCMIN = 2.0


def fetch(url):
    with urllib.request.urlopen(url, timeout=60) as response:
        return response.read()


def png_size(data):
    if data[:8] != b"\x89PNG\r\n\x1a\n" or data[12:16] != b"IHDR":
        raise ValueError("not a PNG")
    return struct.unpack(">II", data[16:24])


def hipparcos(hips):
    """HIP -> (ra J2000 deg, dec J2000 deg, Vmag), propagated from J1991.25 by the proper motion."""
    query = urllib.parse.urlencode({
        "-source": "I/239/hip_main",
        "-out": "HIP,RAICRS,DEICRS,pmRA,pmDE,Vmag",
        "-out.max": "unlimited",
        "HIP": ",".join(str(h) for h in sorted(hips)),
    })
    rows = {}
    for line in fetch(f"{VIZIER_URL}?{query}").decode("ascii").splitlines():
        parts = line.split("\t")
        if len(parts) != 6 or not parts[0].strip().isdigit():
            continue
        hip, ra, dec, pm_ra, pm_dec, vmag = (p.strip() for p in parts)
        ra, dec = float(ra), float(dec)
        years = 2000.0 - HIPPARCOS_EPOCH
        # pmRA in Hipparcos is mu_alpha* (already times cos dec), in mas/yr.
        ra += float(pm_ra) * years / 3.6e6 / math.cos(math.radians(dec))
        dec += float(pm_dec) * years / 3.6e6
        rows[int(hip)] = (ra % 360.0, dec, float(vmag) if vmag else None)
    return rows


def unit(ra_deg, dec_deg):
    ra, dec = math.radians(ra_deg), math.radians(dec_deg)
    return (math.cos(dec) * math.cos(ra), math.cos(dec) * math.sin(ra), math.sin(dec))


def load_bsc():
    """Unit vectors (J2000) of the Bright Star Catalogue stars that have a position."""
    stars = []
    for line in (ASSETS / "bsc5.dat").read_text(encoding="ascii").splitlines():
        line = line.ljust(197)
        try:
            ra = (int(line[75:77]) + int(line[77:79]) / 60 + float(line[79:83]) / 3600) * 15
            dec = int(line[84:86]) + int(line[86:88]) / 60 + int(line[88:90]) / 3600
        except ValueError:
            continue
        stars.append(unit(ra, -dec if line[83] == "-" else dec))
    return stars


def separation_arcmin(a, b):
    dot = max(-1.0, min(1.0, sum(x * y for x, y in zip(a, b))))
    return math.degrees(math.acos(dot)) * 60


def figure_abbreviations():
    abbrs = set()
    for raw in (ASSETS / "constellations.txt").read_text(encoding="utf-8").splitlines():
        if raw.strip() and not raw.startswith("#"):
            abbrs.add(raw.split("|")[0])
    return abbrs


def main():
    index = json.loads(fetch(CULTURE_URL + "index.json"))
    entries = [c for c in index["constellations"] if c.get("image")]
    hips = {a["hip"] for c in entries for a in c["image"]["anchors"]}
    positions = hipparcos(hips)
    bsc = load_bsc()

    errors, warnings, lines, images, covered = [], [], [], {}, {}
    for c in entries:
        abbr = c["id"].split()[-1]
        image = c["image"]
        file_name = image["file"].split("/")[-1]
        width, height = image["size"]
        data = fetch(CULTURE_URL + image["file"])
        if png_size(data) != (width, height):
            errors.append(f"{abbr}: {file_name} is {png_size(data)}, index says {width}x{height}")
        images[file_name] = data

        covers = [abbr] + ALSO_COVERS.get(abbr, [])
        for a in covers:
            covered.setdefault(a, []).append(file_name)

        anchors = []
        for anchor in image["anchors"]:
            hip = anchor["hip"]
            x, y = anchor["pos"]
            if hip not in positions:
                errors.append(f"{abbr}: anchor HIP {hip} not found in Hipparcos")
                continue
            ra, dec, vmag = positions[hip]
            nearest = min(separation_arcmin(unit(ra, dec), s) for s in bsc)
            if nearest > BSC_MATCH_ARCMIN:
                warnings.append(f"{abbr}: anchor HIP {hip} (V {vmag}) has no BSC star within {nearest:.1f}'")
            anchors.append(f"{x},{y},{hip},{ra:.5f},{dec:.5f},{'' if vmag is None else f'{vmag:.2f}'}")
        lines.append(f"{';'.join(covers)}|{file_name}|{width}|{height}|{'|'.join(anchors)}")

    for abbr in sorted(figure_abbreviations()):
        if len(covered.get(abbr, [])) != 1:
            errors.append(f"{abbr}: covered by {covered.get(abbr, [])}, want exactly one image")
    for abbr in sorted(set(covered) - figure_abbreviations()):
        errors.append(f"{abbr}: has an image but no figure in constellations.txt")

    print("\n".join(warnings), file=sys.stderr)
    if errors:
        print("\n".join(errors), file=sys.stderr)
        sys.exit(1)

    ART_DIR.mkdir(exist_ok=True)
    for old in ART_DIR.glob("*.png"):
        if old.name not in images:
            old.unlink()
    for name, data in images.items():
        (ART_DIR / name).write_bytes(data)
    (ASSETS / "constellation_art.txt").write_text(
        "# Constellation artwork: Abbr;Abbr|image|width|height|anchor|anchor|anchor\n"
        "#   Abbr    the constellations the image draws (the Argo ship is Carina, Puppis and Vela)\n"
        "#   image   file in constellation_art/, width and height in pixels\n"
        "#   anchor  x,y,HIP,RA,Dec,Vmag: the pixel (x right, y down from the top-left corner) that\n"
        "#           sits on Hipparcos star HIP, whose J2000 RA and Dec are in degrees (propagated\n"
        "#           from J1991.25 by its proper motion); Vmag is Hipparcos' V, for reference\n"
        "# Generated by tools/constellation_art/gen_art.py; do not edit by hand.\n"
        f"# Images and anchor pixels: Stellarium, skycultures/modern at commit {STELLARIUM_COMMIT}.\n"
        "# Illustrations by Johan Meuris, Free Art License 1.3 (see constellation_art/LICENSE.txt).\n"
        "# Positions: Hipparcos main catalogue (ESA 1997), via CDS VizieR I/239.\n"
        + "\n".join(lines) + "\n",
        encoding="utf-8", newline="\n")
    print(f"constellation_art.txt: {len(lines)} images covering {len(covered)} constellations")


if __name__ == "__main__":
    main()
