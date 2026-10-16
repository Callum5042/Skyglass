#!/usr/bin/env python3
"""Generate the light-pollution grid (app/src/main/assets/light_pollution.bin).

Input: an EOG VIIRS Nighttime Lights annual composite, version 2.2 (Earth Observation Group,
Payne Institute for Public Policy, Colorado School of Mines; CC BY 4.0), the masked average
radiance: https://eogdata.mines.edu/nighttime_light/annual/v22/<year>/ ->
VNL_npp_<year>_global_vcmslcfg_v2_c<date>.average_masked.dat.tif.gz (~325 MB, free sign-in). Put it
in tools/light_pollution/.cache/ (git-ignored). The file is an uncompressed float32 GeoTIFF, one row
per strip, 15 arc-second pixels from 75 N to 65 S, in nW/cm^2/sr; it is streamed out of the gzip and
never unpacked to disk. The 2.5 arc-minute emission grid is cached as a .npy next to it.

Model: upward radiance is not sky brightness, so the zenith brightness at each point is the
natural sky plus K * sum(E * f(d)) over sources within MAX_DISTANCE_KM, where E is radiance times
pixel area and f(d) = (d^2 + h^2)^(-alpha/2): Walker's law (alpha ~ 2.5) at a distance, flattened
within about h of the source. alpha, h and K are fitted, in magnitudes and with a robust loss (a few
meters sit next to a lamp the satellite cannot resolve), to the measured sites in calibration.src.
The natural sky is fixed at NATURAL_SKY: fitted, it drifts to ~21.3 because the dark sites were
measured near solar maximum with meters that read the natural sky brighter, and no site could then
come out darker than Bortle 3. The sum is an FFT convolution in latitude bands, each with
its own kernel, since a degree of longitude shrinks with cos(latitude); longitude wraps around.

Output: zenith sky brightness in mag/arcsec^2 on GRID_STEP-degree cells, quantised to Q_STEP
magnitudes in one byte, rows north to south, filtered and deflated (see the file's header, which
the app parses).

Needs numpy, scipy and tifffile (pip install numpy scipy tifffile).
Usage: python gen_light_pollution.py [path/to/average_masked.dat.tif.gz]
       With no argument, uses the newest-year *_masked.dat.tif.gz in .cache/.
"""

import glob
import gzip
import os
import re
import sys
import zlib

import numpy as np
import scipy.fft
import scipy.optimize
import tifffile

HERE = os.path.dirname(os.path.abspath(__file__))
CACHE = os.path.join(HERE, ".cache")
CALIBRATION = os.path.join(HERE, "calibration.src")
OUTPUT = os.path.join(HERE, "..", "..", "app", "src", "main", "assets", "light_pollution.bin")

PIXELS_PER_DEGREE = 240  # 15 arc seconds
WORK_FACTOR = 6  # pixels per working cell side: 1/40 degree, ~2.8 km
OUT_FACTOR = 2  # working cells per output cell side: 1/20 degree, ~5.6 km
GRID_STEP = WORK_FACTOR * OUT_FACTOR / PIXELS_PER_DEGREE
KM_PER_DEGREE = 111.32
MAX_DISTANCE_KM = 200.0
BAND_ROWS = 20  # working rows convolved with one kernel (half a degree)

# Stored value v means Q_MIN + v * Q_STEP mag/arcsec^2.
Q_MIN = 14.0
Q_STEP = 0.04

# Brightness is handled in relative units, 10^(-0.4 m) for m in mag/arcsec^2.

# Natural zenith sky brightness, mag/arcsec^2: the value Falchi et al. 2016 (Science Advances 2,
# e1600377) use for an unpolluted sky, and the dark end of the SQM column of the Bortle scale.
NATURAL_SKY = 22.0

MAX_OUTPUT_BYTES = 6 * 1024 * 1024


def find_input():
    files = glob.glob(os.path.join(CACHE, "*average_masked.dat.tif.gz")) + glob.glob(
        os.path.join(CACHE, "*median_masked.dat.tif.gz"))
    if not files:
        sys.exit("No *_masked.dat.tif.gz in %s; see the docstring for where to get it." % CACHE)
    return max(files, key=year_of)


def year_of(path):
    m = re.search(r"(?<!\d)(20\d\d)(?!\d)", os.path.basename(path))
    return int(m.group(1)) if m else 0


def read_emission(path):
    """Radiance times pixel area (nW/cm^2/sr * km^2) summed into working cells, rows north to south."""
    cached = os.path.join(CACHE, os.path.basename(path).split(".")[0] + ".emission.npy")
    if os.path.exists(cached) and os.path.getmtime(cached) > os.path.getmtime(path):
        return np.load(cached)
    with gzip.open(path, "rb") as fh:
        with tifffile.TiffFile(fh) as tif:
            page = tif.pages[0]
            height, width = page.shape
            tie = page.tags["ModelTiepointTag"].value
            scale = page.tags["ModelPixelScaleTag"].value
            offsets, counts = page.dataoffsets, page.databytecounts
            # The geometry this script assumes: pixel centres on whole multiples of 15", the first at
            # (-180, 75), and a duplicate column at +180 that is dropped.
            ok = (page.dtype == np.float32 and page.compression == 1 and not page.is_tiled
                  and page.rowsperstrip == 1 and tuple(tie[:5]) == (0.5, 0.5, 0.0, -180.0, 75.0)
                  and abs(scale[0] - 1 / PIXELS_PER_DEGREE) < 1e-9 and abs(scale[1] - 1 / PIXELS_PER_DEGREE) < 1e-9
                  and width == 360 * PIXELS_PER_DEGREE + 1 and all(c == width * 4 for c in counts)
                  and all(b > a for a, b in zip(offsets, offsets[1:])))
            if not ok:
                sys.exit("Unexpected GeoTIFF layout in %s" % path)
        rows = (height - 1) // WORK_FACTOR
        cols = (width - 1) // WORK_FACTOR
        work = np.zeros((rows, cols), np.float64)
        pixel_km = KM_PER_DEGREE / PIXELS_PER_DEGREE
        for j in range(rows * WORK_FACTOR):
            fh.seek(offsets[j])
            row = np.frombuffer(fh.read(counts[j]), "<f4")[: width - 1].astype(np.float64)
            row = np.where(np.isfinite(row) & (row > 0), row, 0.0)
            lat = 75.0 - j / PIXELS_PER_DEGREE
            area = pixel_km * pixel_km * np.cos(np.radians(lat))
            work[j // WORK_FACTOR] += row.reshape(cols, WORK_FACTOR).sum(axis=1) * area
            if j % 3000 == 0:
                print("  reading row %d of %d" % (j, rows * WORK_FACTOR), flush=True)
    work = work.astype(np.float32)
    np.save(cached, work)
    return work


def work_lat(r):
    """Latitude of working row r's centre."""
    return 75.0 - (WORK_FACTOR * r + (WORK_FACTOR - 1) / 2) / PIXELS_PER_DEGREE


def work_lon(c):
    return -180.0 + (WORK_FACTOR * c + (WORK_FACTOR - 1) / 2) / PIXELS_PER_DEGREE


def kernel(d_km, alpha, h_km):
    return np.where(d_km <= MAX_DISTANCE_KM, (d_km * d_km + h_km * h_km) ** (-alpha / 2), 0.0)


def read_calibration():
    sites = []
    with open(CALIBRATION, encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            name, lat, lon, sqm = line.split("|")
            sites.append((name, float(lat), float(lon), float(sqm)))
    return sites


def site_sources(work, lat, lon):
    """(distances in km, emissions) of the working cells within MAX_DISTANCE_KM of a point."""
    rows, cols = work.shape
    step = WORK_FACTOR / PIXELS_PER_DEGREE
    r0 = (75.0 - lat) / step - 0.5
    c0 = (lon + 180.0) / step - 0.5
    dr = int(MAX_DISTANCE_KM / KM_PER_DEGREE / step) + 2
    dc = int(dr / max(np.cos(np.radians(lat)), 0.05)) + 2
    r = np.arange(int(r0) - dr, int(r0) + dr + 1)
    r = r[(r >= 0) & (r < rows)]
    c = np.arange(int(c0) - dc, int(c0) + dc + 1)
    rr, cc = np.meshgrid(r, c, indexing="ij")
    y = (r0 - rr) * step * KM_PER_DEGREE
    x = (cc - c0) * step * KM_PER_DEGREE * np.cos(np.radians(lat))
    d = np.hypot(x, y)
    e = work[rr, cc % cols]
    keep = (d <= MAX_DISTANCE_KM) & (e > 0)
    return d[keep], e[keep].astype(np.float64)


def fit(work, sites):
    sources = [site_sources(work, lat, lon) for _, lat, lon, _ in sites]
    observed = np.array([s[3] for s in sites])
    natural = 10 ** (-0.4 * NATURAL_SKY)

    def predict(p):
        alpha, log_h, log_k = p
        art = np.array([np.sum(e * kernel(d, alpha, np.exp(log_h))) for d, e in sources])
        return -2.5 * np.log10(natural + np.exp(log_k) * art)

    best = None
    for alpha0 in (2.0, 2.5, 3.0):
        for h0 in (0.5, 2.0, 5.0):
            # Start K where the median site's artificial light matches its excess over the natural sky.
            art0 = np.array([np.sum(e * kernel(d, alpha0, h0)) for d, e in sources])
            excess = np.maximum(10 ** (-0.4 * observed) - natural, 1e-12)
            p0 = np.array([alpha0, np.log(h0), np.log(np.median(excess / np.maximum(art0, 1e-30)))])
            res = scipy.optimize.least_squares(
                lambda p: predict(p) - observed, p0, loss="soft_l1", f_scale=0.5,
                bounds=([1.5, np.log(0.1), -200], [3.5, np.log(20), 200]))
            if best is None or res.cost < best.cost:
                best = res
    return best.x, predict(best.x) - observed


def sky_brightness(work, params):
    """Total zenith brightness (relative luminance, 10^(-0.4 m)) at every working cell centre."""
    alpha, log_h, log_k = params
    h = np.exp(log_h)
    rows, cols = work.shape
    step_km = KM_PER_DEGREE * WORK_FACTOR / PIXELS_PER_DEGREE
    pad = int(np.ceil(MAX_DISTANCE_KM / step_km)) + 1
    height = BAND_ROWS + 2 * pad
    out = np.empty((rows, cols), np.float32)
    dy = (np.arange(height) - pad) * step_km
    j = np.arange(cols)
    j = np.where(j <= cols // 2, j, j - cols)  # signed column offsets, wrapped for a circular FFT
    for top in range(0, rows, BAND_ROWS):
        n = min(BAND_ROWS, rows - top)
        lat = work_lat(top + (n - 1) / 2)
        dx = j * step_km * np.cos(np.radians(lat))
        k = kernel(np.hypot(dx[None, :], dy[:, None]), alpha, h).astype(np.float32)
        k = np.roll(k, -pad, axis=0)  # row offset 0 at index 0
        block = np.zeros((height, cols), np.float32)
        lo, hi = max(top - pad, 0), min(top + n + pad, rows)
        block[lo - (top - pad): hi - (top - pad)] = work[lo:hi]
        conv = scipy.fft.irfft2(scipy.fft.rfft2(block, workers=-1) * scipy.fft.rfft2(k, workers=-1),
                                s=block.shape, workers=-1)
        out[top: top + n] = np.maximum(conv[pad: pad + n], 0.0)
    return 10 ** (-0.4 * NATURAL_SKY) + np.exp(log_k) * out


def filter_rows(q):
    """Each byte minus the one above it (mod 256), the first row as is: smooth fields deflate well."""
    d = q.astype(np.int16)
    d[1:] -= q[:-1].astype(np.int16)
    return (d % 256).astype(np.uint8)


def lookup(sqm, lat, lon):
    """Bilinear interpolation in the output grid, as the app does it (LightPollution.kt)."""
    rows, cols = sqm.shape
    y = (out_lat0() - lat) / GRID_STEP
    x = ((lon - out_lon0()) / GRID_STEP) % cols
    r = min(max(int(np.floor(y)), 0), rows - 2)
    fy = min(max(y - r, 0.0), 1.0)
    c = int(np.floor(x)) % cols
    fx = x - np.floor(x)
    c1 = (c + 1) % cols
    top = sqm[r, c] * (1 - fx) + sqm[r, c1] * fx
    bottom = sqm[r + 1, c] * (1 - fx) + sqm[r + 1, c1] * fx
    return top * (1 - fy) + bottom * fy


def out_lat0():
    """Latitude of the first output row's centre."""
    return 75.0 - (WORK_FACTOR * OUT_FACTOR - 1) / 2 / PIXELS_PER_DEGREE


def out_lon0():
    return -180.0 + (WORK_FACTOR * OUT_FACTOR - 1) / 2 / PIXELS_PER_DEGREE


def main():
    path = sys.argv[1] if len(sys.argv) > 1 else find_input()
    print("Input:", os.path.basename(path))
    work = read_emission(path)
    sites = read_calibration()

    params, residuals = fit(work, sites)
    alpha, log_h, log_k = params
    print("Fit: alpha %.3f, h %.2f km, K %.4g" % (alpha, np.exp(log_h), np.exp(log_k)))
    print("Point residuals (model - measured): rms %.2f, max |%.2f| mag" % (
        np.sqrt(np.mean(residuals ** 2)), np.max(np.abs(residuals))))

    lum = sky_brightness(work, params)
    rows, cols = lum.shape
    lum = lum.reshape(rows // OUT_FACTOR, OUT_FACTOR, cols // OUT_FACTOR, OUT_FACTOR).mean(axis=(1, 3))
    sqm = -2.5 * np.log10(lum)
    q = np.clip(np.round((sqm - Q_MIN) / Q_STEP), 0, 255).astype(np.uint8)
    sqm_q = Q_MIN + q * Q_STEP

    print("%-40s %8s %8s %6s" % ("Site", "measured", "grid", "diff"))
    diffs = []
    for name, lat, lon, measured in sites:
        g = lookup(sqm_q, lat, lon)
        diffs.append(g - measured)
        print("%-40s %8.2f %8.2f %+6.2f" % (name, measured, g, g - measured))
    diffs = np.array(diffs)
    print("Grid residuals: mean %+.2f, rms %.2f mag" % (diffs.mean(), np.sqrt(np.mean(diffs ** 2))))

    year = year_of(path)
    header = "\n".join([
        "# Skyglass light pollution: modelled clear, moonless zenith sky brightness.",
        "# Generated by tools/light_pollution/gen_light_pollution.py from EOG VIIRS Nighttime Lights",
        "# v2.2, %d annual masked average (Earth Observation Group, Payne Institute for Public Policy," % year,
        "# Colorado School of Mines; CC BY 4.0), calibrated against Kyba et al. 2015 (CC BY 4.0).",
        "# Model: alpha %.3f, h %.2f km, natural sky %.2f mag/arcsec^2." % (alpha, np.exp(log_h), NATURAL_SKY),
        "# grid: rows cols lat0 lon0 step qmin qstep. Row r, column c is the cell centred at",
        "# (lat0 - r*step, lon0 + c*step), rows north to south; value v is qmin + v*qstep mag/arcsec^2.",
        "# After the blank line: a zlib stream of rows*cols bytes, each the cell's value minus the",
        "# value above it, mod 256 (the first row as is).",
        "grid %d %d %.9f %.9f %.9f %.2f %.2f" % (rows // OUT_FACTOR, cols // OUT_FACTOR, out_lat0(), out_lon0(), GRID_STEP, Q_MIN, Q_STEP),
        "", ""])
    body = zlib.compress(filter_rows(q).tobytes(), 9)
    data = header.encode("ascii") + body
    if len(data) > MAX_OUTPUT_BYTES:
        sys.exit("Output is %d bytes, over the %d-byte budget" % (len(data), MAX_OUTPUT_BYTES))
    with open(OUTPUT, "wb") as f:
        f.write(data)
    print("Wrote %s (%.2f MB)" % (os.path.normpath(OUTPUT), len(data) / 1e6))


if __name__ == "__main__":
    main()
