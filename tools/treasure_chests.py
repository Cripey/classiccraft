#!/usr/bin/env python3
"""WoW's treasure chests for Minecraft (2026-10-04, user: chests Minecraftified). Every open-world
chest object whose lock has WoW's Treasure case and whose loot has no quest items (quest objects
share the lock: Atal'ai Artifacts, Cat Figurines...) - the same rule the server checks in
HandleCCHarvestOpcode. Per entry: name, level (the zone level where it spawns, area_levels.json,
median over its spawns), size (small/medium/large, by name: what the chest rolls) and the
Lockpicking skill its lock asks (0 = none; Minecraft pries it open with a pickaxe instead).
Writes fabric/src/main/resources/mcwow/chests.json:
  {"entries": {entry: [name, level, size, pick, spawns]}, "names": {name: [size, pick]}}
(names for the client's hint and gather, entries for the server's loot).
Usage: tools/treasure_chests.py [--stats]   (needs MariaDB on 3307 and data/server/maps)"""
import json, os, statistics, struct, sys
from collections import defaultdict

from wowdata import ROOT, area_flag, areas as load_areas, sql

OUT = os.path.join(ROOT, "fabric/src/main/resources/mcwow/chests.json")
AREA_LEVELS = os.path.join(ROOT, "fabric/src/main/resources/mcwow/area_levels.json")
LOCK_DBC = os.path.join(ROOT, "data/server/5875/dbc/Lock.dbc")
LOCK_KEY_SKILL, LOCKTYPE_PICKLOCK, LOCKTYPE_TREASURE = 2, 1, 6


def locks():
    """Lock id -> (has a Treasure case, Lockpicking skill asked or 0)."""
    d = open(LOCK_DBC, "rb").read()
    _, n, f, rs, _ = struct.unpack("<4s4I", d[:20])
    out = {}
    for i in range(n):
        r = struct.unpack("<%dI" % f, d[20 + i * rs:20 + (i + 1) * rs])
        typ, idx, skill = r[1:9], r[9:17], r[17:25]
        treasure = any(typ[k] == LOCK_KEY_SKILL and idx[k] == LOCKTYPE_TREASURE for k in range(8))
        pick = max([skill[k] for k in range(8) if typ[k] == LOCK_KEY_SKILL and idx[k] == LOCKTYPE_PICKLOCK] or [0])
        out[r[0]] = (treasure, pick)
    return out


# Containers by name (other objects on the lock - Cat Figurines that wake a guardian, Ritual Candles -
# keep WoW's own use).
WORDS = ("chest", "footlocker", "strongbox", "coffer", "crate", "box", "clam", "trunk", "cache", "lockbox")


def size_of(name):
    n = name.lower()
    if n.startswith("large") or "mithril bound" in n or "coffer" in n or "darkwood" in n:
        return "large"
    if "solid" in n or "strongbox" in n or "alliance chest" in n or "horde chest" in n or "iron bound" in n:
        return "medium"
    return "small"


def main():
    lock = locks()
    levels = {int(k): v[0] for k, v in json.load(open(AREA_LEVELS)).items()}
    area_of = {key: v[0] for key, v in load_areas().items()}
    quest_loot = {int(e) for (e,) in sql("select distinct entry from gameobject_loot_template where ChanceOrQuestChance < 0")}
    chests = {}
    for entry, name, lock_id, loot, quest in sql(
            "select entry, name, data0, data1, data8 from gameobject_template where type = 3 group by entry"):
        treasure, pick = lock.get(int(lock_id), (False, 0))
        if (treasure and int(quest) == 0 and int(loot) not in quest_loot
                and any(w in name.lower().split()[-1] for w in WORDS) and not name.startswith("Practice")):
            chests[int(entry)] = {"name": name, "pick": pick, "levels": []}
    for entry, m, x, y in sql("select id, map, position_x, position_y from gameobject where map in (0, 1)"):
        c = chests.get(int(entry))
        if c is None:
            continue
        a = area_of.get((int(m), area_flag(int(m), float(x), float(y))))
        c["levels"].append(levels.get(a, 0) if a else 0)
    entries, names = {}, {}
    for entry, c in sorted(chests.items()):
        lv = [l for l in c["levels"] if l > 0]
        if not c["levels"]:
            continue  # instanced or never spawned
        level = int(statistics.median(lv)) if lv else 0
        if level <= 0:
            continue
        size = size_of(c["name"])
        entries[str(entry)] = [c["name"], level, size, c["pick"], len(c["levels"])]
        old = names.get(c["name"])
        names[c["name"]] = [size, max(c["pick"], old[1] if old else 0)]
    if "--stats" in sys.argv:
        by = defaultdict(int)
        for e in entries.values():
            by[(e[2], e[3] > 0)] += e[4]
        for (size, locked), n in sorted(by.items()):
            print(f"{size:6} {'locked' if locked else 'open':6} {n} spawn points")
        for e in sorted(entries.values(), key=lambda e: -e[4])[:40]:
            print(e)
        return
    with open(OUT, "w") as f:
        json.dump({"entries": entries, "names": names}, f, separators=(",", ":"), sort_keys=True)
        f.write("\n")
    print(f"{len(entries)} treasure chests ({len(names)} names) -> {OUT}")


main()
