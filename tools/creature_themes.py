#!/usr/bin/env python3
"""Loot themes for WoW creatures (2026-10-03): which Minecraft drops a kill gives beyond the level
materials (leather off skinnable beasts, cloth off humanoids, emeralds from the corpse's money) -
kobolds candles and coal, murlocs fish, spiders string, skeletons bones... Written as
fabric/src/main/resources/mcwow/creature_themes.json ({theme: [entries]}); McwowLoot holds the
drops per theme. Creatures without a theme get their creature type's defaults there.
Usage: tools/creature_themes.py [--stats]   (needs MariaDB on 3307)"""
import json, os, re, subprocess, sys
from collections import defaultdict

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "fabric/src/main/resources/mcwow/creature_themes.json")

BEAST, DRAGONKIN, DEMON, ELEMENTAL, GIANT, UNDEAD, HUMANOID, CRITTER, MECHANICAL = 1, 2, 3, 4, 5, 6, 7, 8, 9
FAMILY = {1: "wolf", 2: "cat", 3: "spider", 4: "bear", 5: "boar", 6: "scaly", 7: "bird", 8: "crab", 9: "gorilla",
          11: "scaly", 12: "bird", 20: "scorpid", 21: "turtle", 24: "bat", 25: "wolf", 26: "bird", 27: "serpent"}

# (theme, name keys) in order; the first match wins (see first()).
BEAST_WORDS = [
    ("spider", "spider widow tarantula recluse lurker crawler"), ("wolf", "wolf worg hound coyote jackal hyena"),
    ("cat", "cat lion panther tiger lynx cougar leopard stalker prowler sabercat nightsaber frostsaber"),
    ("bear", "bear grizzly"), ("boar", "boar pig"), ("bird", "owl hawk vulture buzzard condor eagle strider "
     "plainstrider fleetfoot hippogryph roc chicken parrot screecher moth"), ("crab", "crab crawler lobster"),
    ("scaly", "crocolisk basilisk lizard raptor scalebelly threshadon thunderhead stegodon devilsaur"),
    ("serpent", "serpent snake cobra adder viper python asp"), ("turtle", "turtle tortoise snapjaw"),
    ("bat", "bat"), ("scorpid", "scorpid scorpion"), ("fish", "shark fish barracuda eel piranha"),
    ("hoofed", "kodo stag deer doe elk ram goat gazelle zhevra mountain"), ("rat", "rat"),
    ("gorilla", "gorilla ape silverback yeti"), ("insect", "silithid wasp beetle locust fly scarab worm larva"),
]
HUMANOID_WORDS = [
    ("kobold", "kobold tunnel_rat"), ("murloc", "murloc mrgl"), ("gnoll", "gnoll riverpaw mosshide shadowhide rot_hide mudsnout woodpaw"), ("ogre", "ogre mauler boulderfist dustbelcher mo'grosh gordunni crushridge splinter_fist"), ("troll", "troll bloodscalp skullsplitter witherbark vilebranch shadowpine darkspear frostmane "
     "mossflayer smolderthorn sandfury hakkari amani zandalar gurubashi shatterspear"), ("harpy", "harpy"), ("quilboar", "quilboar razormane "
     "bristleback"), ("centaur", "centaur kolkar galak"), ("naga", "naga"), ("trogg", "trogg"),
    ("furbolg", "furbolg"), ("dark_iron", "dark_iron"), ("goblin", "goblin venture"), ("defias", "defias"),
]
UNDEAD_WORDS = [
    ("skeleton", "skeleton skeletal bone"), ("ghost", "ghost spirit phantom wraith banshee specter spectre "
     "shade apparition haunt wailing"),
]
ELEMENTAL_WORDS = [
    ("fire", "fire flame magma lava ember infernal blaze scorch cinder"), ("water", "water tide sea wave murk "
     "brine"), ("earth", "earth rock stone boulder crag dust gravel"), ("air", "air wind cyclone gust storm breeze"),
    ("ooze", "ooze slime sludge jelly"),
]
CRITTER_WORDS = [
    ("rabbit", "rabbit hare"), ("chicken", "chicken hen rooster"), ("cow", "cow"), ("sheep", "sheep"),
    ("pig", "pig"), ("deer", "deer fawn"), ("fish", "fish"),
]


def first(name, table):
    """The first theme with a key in the name: a key is a whole word; a_b matches the words "a b"."""
    low = name.lower()
    words = set(re.findall(r"[a-z']+", low))
    for theme, keys in table:
        for k in keys.split():
            if ("_" in k and k.replace("_", " ") in low) or k in words:
                return theme
    return None


def theme(name, ctype, family):
    if ctype == BEAST:
        return FAMILY.get(family) or first(name, BEAST_WORDS) or "beast"
    if ctype == HUMANOID:
        return first(name, HUMANOID_WORDS)
    if ctype == UNDEAD:
        return first(name, UNDEAD_WORDS) or "zombie"
    if ctype == ELEMENTAL:
        return first(name, ELEMENTAL_WORDS) or "earth"
    if ctype == CRITTER:
        return first(name, CRITTER_WORDS)
    if ctype == MECHANICAL:
        return "mechanical"
    if ctype == DRAGONKIN:
        return "dragonkin"
    if ctype == DEMON:
        return "demon"
    if ctype == GIANT:
        return "giant"
    return None


def main():
    out = subprocess.run(["mysql", "-h127.0.0.1", "-P3307", "-umangos", "-pmangos", "mangos", "-N", "-B", "-e",
                          "select entry, name, type, pet_family from creature_template group by entry"],
                         capture_output=True, text=True, check=True).stdout
    themes = defaultdict(list)
    for line in out.splitlines():
        entry, name, ctype, family = line.split("\t")
        t = theme(name, int(ctype), int(family))
        if t:
            themes[t].append(int(entry))
    if "--stats" in sys.argv:
        for t, e in sorted(themes.items(), key=lambda kv: -len(kv[1])):
            print(f"{t:12} {len(e)}")
        return
    with open(OUT, "w") as f:
        json.dump({t: sorted(e) for t, e in sorted(themes.items())}, f, separators=(",", ":"))
        f.write("\n")
    print(f"{sum(map(len, themes.values()))} creatures in {len(themes)} themes -> {OUT}")


main()
