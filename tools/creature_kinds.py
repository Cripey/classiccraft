#!/usr/bin/env python3
"""WoW creatures Minecraft's Smite and Bane of Arthropods work on (2026-10-04): the stand-ins of WoW
creatures are one entity type to Minecraft, so McwowActorEntity adds the bonus itself by entry -
undead = creature type Undead; arthropods = beasts (and unspecified) of the spider, crab and scorpid
families or named like insects. Writes fabric/src/main/resources/mcwow/creature_kinds.json
({"undead": [entries], "arthropod": [entries]}).
Usage: tools/creature_kinds.py [--stats]   (needs MariaDB on 3307)"""
import json, os, re, sys

from wowdata import ROOT, sql

OUT = os.path.join(ROOT, "fabric/src/main/resources/mcwow/creature_kinds.json")
BEAST, UNDEAD, NOT_SPECIFIED = 1, 6, 10
FAMILIES = {3, 8, 20}  # spider, crab, scorpid
WORDS = re.compile(r"\b(spider|widow|tarantula|recluse|crab|scorpid|scorpion|silithid|wasp|beetle|locust|scarab|"
                   r"tick|fly|larva|crawler|centipede|mantis|hive|swarmer|lurker)s?\b", re.I)


def main():
    undead, arthropod = [], []
    for entry, name, typ, family in sql("select entry, name, type, pet_family from creature_template group by entry"):
        e, t, f = int(entry), int(typ), int(family)
        if t == UNDEAD:
            undead.append(e)
        elif t in (BEAST, NOT_SPECIFIED) and (f in FAMILIES or WORDS.search(name)):
            arthropod.append(e)
    if "--stats" in sys.argv:
        print(len(undead), "undead,", len(arthropod), "arthropods")
        return
    with open(OUT, "w") as f:
        json.dump({"undead": sorted(undead), "arthropod": sorted(arthropod)}, f, separators=(",", ":"))
        f.write("\n")
    print(f"{len(undead)} undead, {len(arthropod)} arthropods -> {OUT}")


main()
