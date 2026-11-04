#!/usr/bin/env python3
"""Bake AutoEq ParametricEQ results into a compact DynamicsProcessing postEq asset.

Usage: git clone --depth 1 --filter=blob:none --sparse https://github.com/jaakkopasanen/AutoEq.git /tmp/autoeq
       (cd /tmp/autoeq && git sparse-checkout set --no-cone '/results/**/*ParametricEQ.txt')
       python3 tools/build_autoeq.py /tmp/autoeq app/src/main/assets/autoeq_posteq.bin

Each headphone's PEQ is evaluated (RBJ biquads, fs=48k) and averaged in dB over
BANDS log-spaced bands (20Hz-20kHz), the same layout EqualizerService gives the
postEq stage. Data: AutoEq by Jaakko Pasanen, MIT License.
"""
import math, os, re, struct, sys

BANDS = 32
F_LO, F_HI, FS = 20.0, 20000.0, 48000.0
SOURCE_PRIORITY = ["oratory1990", "crinacle", "Rtings", "Innerfidelity", "Headphone.com Legacy"]
TYPES = {"over-ear": 0, "in-ear": 1, "earbud": 2}
LINE = re.compile(r"Filter \d+: ON (PK|LSC|HSC) Fc ([\d.]+) Hz Gain ([-\d.]+) dB Q ([\d.]+)")

def edges():
    return [F_LO * (F_HI / F_LO) ** (i / BANDS) for i in range(BANDS + 1)]

def biquad(kind, f0, g, q):
    a = 10 ** (g / 40); w0 = 2 * math.pi * f0 / FS
    c, s = math.cos(w0), math.sin(w0); al = s / (2 * q)
    if kind == "PK":
        b = [1 + al * a, -2 * c, 1 - al * a]; d = [1 + al / a, -2 * c, 1 - al / a]
    else:
        sq = 2 * math.sqrt(a) * al
        if kind == "LSC":
            b = [a * ((a + 1) - (a - 1) * c + sq), 2 * a * ((a - 1) - (a + 1) * c), a * ((a + 1) - (a - 1) * c - sq)]
            d = [(a + 1) + (a - 1) * c + sq, -2 * ((a - 1) + (a + 1) * c), (a + 1) + (a - 1) * c - sq]
        else:
            b = [a * ((a + 1) + (a - 1) * c + sq), -2 * a * ((a - 1) + (a + 1) * c), a * ((a + 1) + (a - 1) * c - sq)]
            d = [(a + 1) - (a - 1) * c + sq, 2 * ((a - 1) - (a + 1) * c), (a + 1) - (a - 1) * c - sq]
    return b, d

def resp_db(filters, f):
    w = 2 * math.pi * f / FS; z1 = complex(math.cos(-w), math.sin(-w)); z2 = z1 * z1
    total = 0.0
    for b, d in filters:
        h = (b[0] + b[1] * z1 + b[2] * z2) / (d[0] + d[1] * z1 + d[2] * z2)
        total += 20 * math.log10(max(abs(h), 1e-9))
    return total

def band_gains(filters):
    e = edges(); out = []
    for i in range(BANDS):
        pts = [e[i] * (e[i + 1] / e[i]) ** ((k + 0.5) / 8) for k in range(8)]
        out.append(sum(resp_db(filters, f) for f in pts) / len(pts))
    peak = max(out)
    return [g - peak for g in out] if peak > 0 else out  # never boost: clipping-safe

def main(root, dst):
    found = {}
    for dirpath, _, files in os.walk(os.path.join(root, "results")):
        for fn in files:
            if not fn.endswith(" ParametricEQ.txt"):
                continue
            parts = os.path.relpath(dirpath, os.path.join(root, "results")).split(os.sep)
            source, name = parts[0], parts[-1]
            htype = next((TYPES[p] for p in parts if p in TYPES), 0)
            rank = SOURCE_PRIORITY.index(source) if source in SOURCE_PRIORITY else len(SOURCE_PRIORITY)
            key = re.sub(r"[^a-z0-9]", "", name.lower())
            if key in found and found[key][0] <= rank:
                continue
            filters = []
            with open(os.path.join(dirpath, fn), encoding="utf-8") as fh:
                for line in fh:
                    m = LINE.search(line)
                    if m:
                        filters.append(biquad(m.group(1), float(m.group(2)), float(m.group(3)), float(m.group(4))))
            if filters:
                found[key] = (rank, name, htype, filters)
    entries = sorted(found.values(), key=lambda t: t[1].lower())
    with open(dst, "wb") as out:
        out.write(b"AEQ1"); out.write(struct.pack("<HB", len(entries), BANDS))
        for f in edges():
            out.write(struct.pack("<f", f))
        for _, name, htype, filters in entries:
            nb = name.encode("utf-8")[:255]
            out.write(struct.pack("<BB", len(nb), htype)); out.write(nb)
            out.write(struct.pack("<%db" % BANDS, *[max(-127, min(127, round(g * 4))) for g in band_gains(filters)]))
    print(f"{len(entries)} headphones -> {dst} ({os.path.getsize(dst)} bytes)")

if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
