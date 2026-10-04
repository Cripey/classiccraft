#!/usr/bin/env python3
"""Writes fabric/src/main/resources/mcwow/area_levels.json (2026-10-04): each WoW area's zone level,
for the ores under WoW's ground (McwowGroundReveal picks the zone's mining tier by it).

AreaTable.dbc (1.12) gives sub-areas an exploration level (Goldshire 5) and zones none; a zone's
level is the median of its sub-areas', and every area of the zone gets it - one tier per zone
(user's pick over per-sub-area levels). Reads the DBCs extracted for the server (tools/extract.sh).
Output: {"<area id>": [zone level, zone id]}. Usage: tools/area_levels.py"""
import json, os, statistics, struct

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DBC = os.path.join(ROOT, "data/server/5875/dbc/AreaTable.dbc")
OUT = os.path.join(ROOT, "fabric/src/main/resources/mcwow/area_levels.json")
ID, PARENT, LEVEL = 0, 2, 10

d = open(DBC, "rb").read()
magic, n, fields, size, _ = struct.unpack_from("<4s4i", d, 0)
assert magic == b"WDBC" and fields == 25, (magic, fields)
recs = [struct.unpack_from(f"<{fields}i", d, 20 + i * size) for i in range(n)]
parent = {r[ID]: r[PARENT] for r in recs}


def zone_of(a):
    seen = set()
    while parent.get(a, 0) and a not in seen:
        seen.add(a)
        a = parent[a]
    return a


levels = {}
for r in recs:
    if r[LEVEL] > 0:
        levels.setdefault(zone_of(r[ID]), []).append(r[LEVEL])
zone_level = {z: round(statistics.median(v)) for z, v in levels.items()}
out = {str(r[ID]): [zone_level[zone_of(r[ID])], zone_of(r[ID])] for r in recs if zone_of(r[ID]) in zone_level}
with open(OUT, "w") as f:
    json.dump(out, f, separators=(",", ":"), sort_keys=True)
    f.write("\n")
print(f"{len(out)} areas in {len(zone_level)} zones -> {OUT}")
