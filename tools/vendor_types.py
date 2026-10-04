#!/usr/bin/env python3
"""WoW's vendors for Minecraft's trade screens (2026-10-03, user: the original vendor NPCs sell
Minecraft items, stock by vendor type). Per vendor creature entry: its type (from its subname -
"Weaponsmith", "Innkeeper", "Tailoring Supplies"...) and the level of the area it stands in (its
own level is unreliable: innkeepers are 30), as fabric/src/main/resources/mcwow/vendors.json
({entry: [type, level]}). McwowVendors holds the stock per type.
Usage: tools/vendor_types.py [--stats]   (needs MariaDB on 3307 and data/server/maps)"""
import json, os, sys
from collections import Counter

from wowdata import ROOT, area_flag, areas as load_areas, sql

OUT = os.path.join(ROOT, "fabric/src/main/resources/mcwow/vendors.json")

# (type, subname words) in order; the first match wins.
TYPES = [
    ("innkeeper", "innkeeper"),
    ("food", "food drink baker butcher bartender cook meat fruit mushroom cheese wine brewer"),
    ("bowyer", "bow bowyer gunsmith gun fletcher arrow ammunition"),
    ("weapons", "weapon weaponsmith blade axe staff mace sword dagger"),
    ("armor", "armor armorer shieldcrafter clothier robe"),
    ("tailoring", "tailoring cloth"),
    ("leatherworking", "leatherworking leather"),
    ("blacksmithing", "blacksmithing"),
    ("engineering", "engineering"),
    ("alchemy", "alchemy herbalism poison"),
    ("enchanting", "enchanting"),
    ("mining", "mining"),
    ("fishing", "fishing fisherman"),
    ("cooking", "cooking"),
    ("reagents", "reagent reagents"),
    ("general", "general trade tradesman supplies supplier goods bag"),
]


def vendor_type(subname):
    words = set((subname or "").lower().replace("&", " ").split())
    for t, keys in TYPES:
        if words & set(keys.split()):
            return t
    return "general"


def main():
    areas = load_areas()
    zone_level = {e: lvl for (m, f), (e, z, lvl, n) in areas.items()}
    vendors = {}
    for entry, subname, level in sql("select entry, subname, max(level_min) from creature_template "
                                     "where npc_flags & 4 group by entry"):
        vendors[int(entry)] = [vendor_type(subname if subname != "NULL" else ""), int(level)]
    # The area level where each vendor stands (its first spawn), else its zone's, else its own level.
    for entry, m, x, y in sql("select id, map, position_x, position_y from creature group by id"):
        v = vendors.get(int(entry))
        if not v:
            continue
        a = areas.get((int(m), area_flag(int(m), float(x), float(y))))
        if a:
            lvl = a[2] or zone_level.get(a[1], 0)
            if lvl:
                v[1] = lvl
    if "--stats" in sys.argv:
        print(Counter(t for t, _ in vendors.values()).most_common())
        return
    with open(OUT, "w") as f:
        json.dump({str(k): v for k, v in sorted(vendors.items())}, f, separators=(",", ":"))
        f.write("\n")
    print(f"{len(vendors)} vendors -> {OUT}")


main()
