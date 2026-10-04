#!/usr/bin/env python3
"""Writes the mod's data for WoW's ores and WoW's materials (2026-10-03): ore blocks (stone and
deepslate, drawn as vanilla stone + our own ore-speck overlay PNG), raw ores and bars (vanilla item
models tinted - no Mojang textures copied), loot tables, tool tags, smelting/alloy recipes, leather
and cloth (tinted vanilla leather and paper), and the English names. McwowOres.ORES and
McwowMaterials must list the same ids. The ores are placed under WoW's ground by McwowGroundReveal
(2026-10-04; the mine dimensions and their worldgen are gone). Run from anywhere; rewrites in place."""
import glob, json, os, random, struct, zipfile, zlib

RES = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "fabric/src/main/resources")

# id: (name, speck shades dark/mid/light, bar tint, raw tint, tool tier)
ORES = {
    "tin":        ("Tin",        [(96, 94, 84), (160, 158, 146), (214, 212, 198)], 0xD6D2C2, 0xC8C4B4, "stone"),
    "silver":     ("Silver",     [(110, 120, 132), (186, 196, 206), (244, 248, 252)], 0xEEF4FA, 0xDDE4EC, "stone"),
    "mithril":    ("Mithril",    [(58, 110, 100), (132, 196, 180), (206, 244, 232)], 0xC2EEE0, 0xA8DCCC, "iron"),
    "truesilver": ("Truesilver", [(140, 120, 60), (226, 214, 156), (255, 252, 224)], 0xFFF6C8, 0xF0E4B0, "iron"),
    "thorium":    ("Thorium",    [(24, 84, 70), (56, 158, 128), (128, 222, 190)], 0x62C8AA, 0x50B496, "iron"),
    "dark_iron":  ("Dark Iron",  [(52, 18, 14), (128, 38, 24), (214, 92, 40)], 0x7A4038, 0x8A3A2C, "iron"),
}
# Alloys: id -> (name, tint)
ALLOYS = {"bronze": ("Bronze", 0xD08E50), "steel": ("Steel", 0x9AA6B4)}

# WoW's leather and cloth tiers (dropped by McwowLoot by creature level): id -> (name, tint, base model)
MATERIALS = {
    "light_leather": ("Light Leather", 0xF0D8C0, "leather"), "medium_leather": ("Medium Leather", 0xD8B898, "leather"),
    "heavy_leather": ("Heavy Leather", 0xB08868, "leather"), "thick_leather": ("Thick Leather", 0x8C6C58, "leather"),
    "rugged_leather": ("Rugged Leather", 0x6C5848, "leather"),
    "linen_cloth": ("Linen Cloth", 0xEADCB8, "paper"), "wool_cloth": ("Wool Cloth", 0xB8B0A0, "paper"),
    "silk_cloth": ("Silk Cloth", 0xE8B8E0, "paper"), "mageweave_cloth": ("Mageweave Cloth", 0xB098E0, "paper"),
    "runecloth": ("Runecloth", 0x8898E8, "paper"),
}

# Gear (2026-10-03, user: the look follows the material): material -> (bar item, armor look, tool look).
# Copper and iron are vanilla's own recipes (copper/iron ingots); McwowGear stamps every crafted
# piece with its material's item level from what lay in the grid.
GEAR = {
    "bronze": ("mcwow:bronze_bar", "golden", "golden"), "steel": ("mcwow:steel_bar", "iron", "iron"),
    "mithril": ("mcwow:mithril_bar", "chainmail", "iron"), "thorium": ("mcwow:thorium_bar", "diamond", "diamond"),
    "dark_iron": ("mcwow:dark_iron_bar", "netherite", "netherite"),
}
# WoW's herbs (user, 2026-10-04): a herb node drops a VANILLA brewing ingredient named after the herb
# and drawn as its own plant (a vanilla texture, tinted where it is a grey one), so Minecraft's own
# brewing takes it unchanged. id -> (WoW name, vanilla item, texture, tint or None). McwowNodes reads
# resources mcwow/herbs.json; the item look is the stack's item_model mcwow:herb/<id>.
HERBS = {
    "peacebloom": ("Peacebloom", "glistering_melon_slice", "block/oxeye_daisy", None),
    "silverleaf": ("Silverleaf", "nether_wart", "block/fern", 0xB8C4CC),
    "earthroot": ("Earthroot", "blaze_powder", "block/hanging_roots", None),
    "mageroyal": ("Mageroyal", "sugar", "block/pink_tulip", None),
    "briarthorn": ("Briarthorn", "spider_eye", "block/dead_bush", None),
    "stranglekelp": ("Stranglekelp", "pufferfish", "item/kelp", None),
    "bruiseweed": ("Bruiseweed", "rabbit_foot", "block/fern", 0x7A5A9A),
    "wild_steelbloom": ("Wild Steelbloom", "rabbit_foot", "block/azure_bluet", None),  # turtle scutes brew nothing
    "grave_moss": ("Grave Moss", "fermented_spider_eye", "block/short_grass", 0x4E6B3A),
    "kingsblood": ("Kingsblood", "glistering_melon_slice", "block/poppy", None),
    "liferoot": ("Liferoot", "ghast_tear", "block/mangrove_propagule", None),
    "fadeleaf": ("Fadeleaf", "golden_carrot", "block/fern", 0xC8D8B8),
    "goldthorn": ("Goldthorn", "glowstone_dust", "block/dandelion", None),
    "khadgars_whisker": ("Khadgar's Whisker", "redstone", "block/short_grass", 0xC8B860),
    "wintersbite": ("Wintersbite", "phantom_membrane", "block/cornflower", None),
    "firebloom": ("Firebloom", "magma_cream", "block/torchflower", None),
    "purple_lotus": ("Purple Lotus", "glowstone_dust", "block/allium", None),
    "arthas_tears": ("Arthas' Tears", "ghast_tear", "block/blue_orchid", None),
    "sungrass": ("Sungrass", "blaze_powder", "block/short_grass", 0xE0C040),
    "blindweed": ("Blindweed", "spider_eye", "block/nether_sprouts", None),
    "ghost_mushroom": ("Ghost Mushroom", "fermented_spider_eye", "block/brown_mushroom", None),
    "gromsblood": ("Gromsblood", "blaze_powder", "block/crimson_roots", None),
    "golden_sansam": ("Golden Sansam", "glistering_melon_slice", "block/sunflower_front", None),
    "dreamfoil": ("Dreamfoil", "phantom_membrane", "block/lily_of_the_valley", None),
    "mountain_silversage": ("Mountain Silversage", "ghast_tear", "block/fern", 0xD0D8E0),
    "plaguebloom": ("Plaguebloom", "fermented_spider_eye", "block/warped_roots", None),
    "icecap": ("Icecap", "breeze_rod", "block/white_tulip", None),
    "black_lotus": ("Black Lotus", "dragon_breath", "block/wither_rose", None),
}


def herbs():
    table = {}
    for hid, (name, item, texture, tint) in HERBS.items():
        write(f"assets/mcwow/models/item/herb/{hid}.json",
              {"parent": "minecraft:item/generated", "textures": {"layer0": f"minecraft:{texture}"}})
        model = {"type": "minecraft:model", "model": f"mcwow:item/herb/{hid}"}
        if tint is not None:
            model["tints"] = [{"type": "minecraft:constant", "value": tint - 0x1000000}]
        write(f"assets/mcwow/items/herb/{hid}.json", {"model": model})
        table[name] = {"id": hid, "item": f"minecraft:{item}"}
    write("mcwow/herbs.json", table)


ARMOR = ["helmet", "chestplate", "leggings", "boots"]
TOOLS = ["sword", "axe", "pickaxe", "shovel", "hoe", "spear"]
LEATHER_IDS = ["light_leather", "medium_leather", "heavy_leather", "thick_leather", "rugged_leather"]
# Cloth armor (armor classes, 2026-10-04): leather armor's shapes and look, dyed light by McwowGear.
CLOTH_IDS = ["linen_cloth", "wool_cloth", "silk_cloth", "mageweave_cloth", "runecloth"]


def vanilla_recipe(name):
    jar = glob.glob(os.path.expanduser("~/.gradle/caches/fabric-loom/minecraftMaven/net/minecraft/"
                                       "minecraft-common-deobf/*/minecraft-common-deobf-*.jar"))[0]
    with zipfile.ZipFile(jar) as z:
        return json.loads(z.read(f"data/minecraft/recipe/{name}.json"))


def gear_recipes():
    """Our materials' gear in vanilla's shapes. Vanilla's diamond/netherite gear recipes stay (26.3's
    recipe registry can't lose entries its advancements name): neither material exists in our worlds,
    so those looks come only from thorium and dark iron."""
    for mat, (bar, armor_look, tool_look) in GEAR.items():
        for piece in ARMOR + TOOLS:
            r = vanilla_recipe(f"iron_{piece}")
            r["key"] = {k: (v if v == "minecraft:stick" else bar) for k, v in r["key"].items()}
            r["group"] = f"mcwow_{piece}"
            r["result"] = {"id": f"minecraft:{armor_look if piece in ARMOR else tool_look}_{piece}"}
            write(f"data/mcwow/recipe/gear/{mat}_{piece}.json", r)
    for leather in LEATHER_IDS + CLOTH_IDS:
        for piece in ARMOR:
            r = vanilla_recipe(f"leather_{piece}")
            r["key"] = {k: f"mcwow:{leather}" for k in r["key"]}
            r["group"] = f"mcwow_{piece}"
            write(f"data/mcwow/recipe/gear/{leather}_{piece}.json", r)


TOOL_TAG = {"stone": "needs_stone_tool", "iron": "needs_iron_tool"}


def write(rel, obj):
    p = os.path.join(RES, rel)
    os.makedirs(os.path.dirname(p), exist_ok=True)
    with open(p, "w") as f:
        json.dump(obj, f, indent=2)
        f.write("\n")


def png(rel, w, h, rgba):
    raw = b"".join(b"\0" + bytes(rgba[y * w * 4:(y + 1) * w * 4]) for y in range(h))
    chunk = lambda t, d: struct.pack(">I", len(d)) + t + d + struct.pack(">I", zlib.crc32(t + d) & 0xFFFFFFFF)
    data = b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 6, 0, 0, 0)) \
        + chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b"")
    p = os.path.join(RES, rel)
    os.makedirs(os.path.dirname(p), exist_ok=True)
    open(p, "wb").write(data)


def speck_overlay(ore, shades):
    """Our own ore texture: clusters of 2-5 pixels, lit from the top left, on a clear 16x16."""
    rnd = random.Random("mcwow-" + ore)
    px = [0] * (16 * 16 * 4)
    taken = set()
    for _ in range(6):
        for _try in range(30):
            cx, cy = rnd.randrange(1, 15), rnd.randrange(1, 15)
            cells = {(cx, cy)}
            while len(cells) < rnd.randint(2, 5):
                x, y = rnd.choice(sorted(cells))
                dx, dy = rnd.choice([(1, 0), (-1, 0), (0, 1), (0, -1)])
                if 0 <= x + dx < 16 and 0 <= y + dy < 16:
                    cells.add((x + dx, y + dy))
            ring = {(x + a, y + b) for x, y in cells for a in (-1, 0, 1) for b in (-1, 0, 1)}
            if not ring & taken:
                break
        taken |= cells
        for x, y in cells:
            lit = (x - 1, y) not in cells and (x, y - 1) not in cells
            shade = (x + 1, y) not in cells and (x, y + 1) not in cells
            r, g, b = shades[2] if lit else shades[0] if shade else shades[1]
            i = (y * 16 + x) * 4
            px[i:i + 4] = [r, g, b, 255]
    png(f"assets/mcwow/textures/block/{ore}_ore_overlay.png", 16, 16, px)


# Wands (McwowWands, 2026-10-04): one per spell school; catalyst over a metal bar over a stick.
WAND_SCHOOLS = {  # school: (name, gem colour, catalyst)
    "holy": ("Holy", (0xFF, 0xF2, 0xA0), "minecraft:glowstone_dust"),
    "fire": ("Fire", (0xFF, 0x7A, 0x20), "minecraft:blaze_powder"),
    "nature": ("Nature", (0x60, 0xD0, 0x40), "minecraft:slime_ball"),
    "frost": ("Frost", (0x9A, 0xD8, 0xFF), "minecraft:snowball"),
    "shadow": ("Shadow", (0x5A, 0x24, 0x80), "minecraft:ink_sac"),
    "arcane": ("Arcane", (0xFF, 0x78, 0xE8), "minecraft:lapis_lazuli"),
}
WAND_METALS = ["minecraft:copper_ingot", "minecraft:gold_ingot", "mcwow:bronze_bar", "minecraft:iron_ingot",
               "mcwow:steel_bar", "mcwow:mithril_bar", "mcwow:thorium_bar", "mcwow:dark_iron_bar"]


def shade(c, f):
    return tuple(max(0, min(255, int(v * f))) for v in c)


def wand_texture(school, gem):
    """Our own 16x16 wand: a wooden shaft from the bottom left, a grip wrap, a metal band and a
    glowing gem in the school's colour at the top right."""
    px = [0] * (16 * 16 * 4)
    def put(x, y, c, a=255):
        if 0 <= x < 16 and 0 <= y < 16:
            px[(y * 16 + x) * 4:(y * 16 + x) * 4 + 4] = [*c, a]
    wood, wood_hi, wood_lo = (0x7A, 0x52, 0x30), (0x9C, 0x6C, 0x40), (0x4E, 0x32, 0x1C)
    for i in range(10):
        x, y = 2 + i, 13 - i
        grip = i <= 2
        put(x, y, (0x3A, 0x28, 0x1E) if grip else wood)
        put(x + 1, y, (0x2A, 0x1C, 0x14) if grip else wood_lo)  # shadow side
        put(x, y - 1, (0x50, 0x3A, 0x2A) if grip else wood_hi) if i < 9 else None
    for x, y in ((9, 6), (10, 6), (9, 5)):  # metal band under the gem
        put(x, y, (0xB8, 0xBE, 0xC6))
    put(10, 5, (0x80, 0x86, 0x8E))
    for x, y in ((12, 3), (11, 4)):  # glow around the gem
        for dx, dy in ((-1, 0), (1, 0), (0, -1), (0, 1), (-1, -1), (1, 1), (1, -1), (-1, 1)):
            put(x + dx, y + dy, gem, 90)
    for x, y in ((11, 3), (12, 3), (11, 4), (12, 4)):
        put(x, y, shade(gem, 0.8))
    put(11, 3, shade(gem, 1.25))
    put(12, 4, shade(gem, 0.55))
    png(f"assets/mcwow/textures/item/{school}_wand.png", 16, 16, px)


# Guns (McwowGuns, 2026-10-04): rifle and blunderbuss of any gun metal; ammo from gunpowder + 2 nuggets (32 shots).
GUN_METALS = [m for m in WAND_METALS if m != "minecraft:gold_ingot"]
AMMO = {  # id: (name, metal colour, nugget)
    "light_shot": ("Light Shot", (0xC8, 0x7A, 0x50), "minecraft:copper_nugget"),
    "heavy_shot": ("Heavy Shot", (0xB8, 0xB8, 0xB8), "minecraft:iron_nugget"),
    "solid_shot": ("Solid Shot", (0x9A, 0xA6, 0xB4), "mcwow:steel_nugget"),
    "mithril_gyro_shot": ("Mithril Gyro-Shot", (0x7A, 0xD0, 0xC8), "mcwow:mithril_nugget"),
    "thorium_shell": ("Thorium Shells", (0xE0, 0x8A, 0x40), "mcwow:thorium_nugget"),
}
NUGGETS = {"steel": ("Steel Nugget", 0x9AA6B4), "mithril": ("Mithril Nugget", 0x7AD0C8), "thorium": ("Thorium Nugget", 0xE08A40)}


def canvas():
    px = [0] * (16 * 16 * 4)
    def put(x, y, c, a=255):
        if 0 <= x < 16 and 0 <= y < 16:
            px[(y * 16 + x) * 4:(y * 16 + x) * 4 + 4] = [*c, a]
    return px, put


GUN_PALETTE = {
    "H": (0xB0, 0x7A, 0x4A), "W": (0x8A, 0x5A, 0x34), "w": (0x5A, 0x3A, 0x22),  # wood: light, mid, dark
    "L": (0xD4, 0xDA, 0xE2), "S": (0x92, 0x98, 0xA2), "s": (0x56, 0x5C, 0x66),  # steel: light, mid, dark
    "K": (0x34, 0x34, 0x3A), "D": (0x1C, 0x1C, 0x20), "B": (0xC8, 0x9A, 0x40),  # lock, bore, brass
}
GUN_MAPS = {
    # Our own pixel art (2026-10-04): stock bottom left, barrel to the top right.
    "rifle": [
        "................",
        ".............LS.",
        "............LSs.",
        "...........LSs..",
        "..........LSs...",
        ".........LSs....",
        "........LSs.....",
        ".......HWs......",
        "......HWw.......",
        ".....BKw........",
        "....HKKw........",
        "...HWWw.........",
        "..HWWWw.........",
        ".HWWWw..........",
        ".WWww...........",
        "................",
    ],
    "blunderbuss": [
        "...........LLL..",
        "..........LSSSs.",
        "..........SDDSs.",
        "...........SDSs.",
        "..........LSs...",
        ".........LSs....",
        "........LSs.....",
        ".......HWs......",
        "......HWw.......",
        ".....BKw........",
        "....HKKw........",
        "...HWWw.........",
        "..HWWWw.........",
        ".HWWWw..........",
        ".WWww...........",
        "................",
    ],
}


# Held like a gun (user, 2026-10-04: not straight up like a sword). z turns the drawn 45-degree
# barrel in the sprite's plane first, then y +90 points the sprite's right side away from the
# player (-90, the sword's, pointed it back at them: first live try). In the hand's frame the
# barrel then runs along the arm: level in first person; in third person Steve aims with both arms
# forward (client AvatarArmPoseMixin, the loaded crossbow's pose), so along the arm is forward
# (z -135 instead pointed it at the ground: second live try).
GUN_DISPLAY = {
    "thirdperson_righthand": {"rotation": [0, 90, -45], "translation": [0, 1.0, -1.0], "scale": [0.95, 0.95, 0.95]},
    "thirdperson_lefthand": {"rotation": [0, -90, 45], "translation": [0, 1.0, -1.0], "scale": [0.95, 0.95, 0.95]},
    "firstperson_righthand": {"rotation": [0, 90, -40], "translation": [1.5, 2.6, 0.5], "scale": [0.75, 0.75, 0.75]},
    "firstperson_lefthand": {"rotation": [0, -90, 40], "translation": [1.5, 2.6, 0.5], "scale": [0.75, 0.75, 0.75]},
}


def gun_texture(name, blunderbuss):
    """Our own 16x16 gun from GUN_MAPS (blunderbuss: the flared muzzle)."""
    px, put = canvas()
    for y, row in enumerate(GUN_MAPS[name]):
        for x, ch in enumerate(row):
            if ch in GUN_PALETTE:
                put(x, y, GUN_PALETTE[ch])
    png(f"assets/mcwow/textures/item/{name}.png", 16, 16, px)


def ammo_texture(aid, color):
    """A few round shots in a little heap, lit from the top left."""
    px, put = canvas()
    for cx, cy in ((5, 11), (9, 11), (7, 8), (11, 9), (4, 8)):
        for dx, dy in ((0, 0), (1, 0), (0, 1), (1, 1)):
            put(cx + dx, cy + dy, shade(color, 0.85))
        put(cx, cy, shade(color, 1.2))
        put(cx + 1, cy + 1, shade(color, 0.55))
    png(f"assets/mcwow/textures/item/{aid}.png", 16, 16, px)


def weapons(lang):
    write("data/mcwow/tags/item/gun_metals.json", {"replace": False, "values": GUN_METALS})
    for gun, flared, name, pattern in (("rifle", False, "Rifle", ["MMM", " TP"]),
                                       ("blunderbuss", True, "Blunderbuss", ["M M", " M ", "TP "])):
        gun_texture(gun, flared)
        write(f"assets/mcwow/models/item/{gun}.json", {"parent": "minecraft:item/generated",
                                                       "textures": {"layer0": f"mcwow:item/{gun}"},
                                                       "display": GUN_DISPLAY})
        write(f"assets/mcwow/items/{gun}.json", {"model": {"type": "minecraft:model", "model": f"mcwow:item/{gun}"}})
        lang[f"item.mcwow.{gun}"] = name
        write(f"data/mcwow/recipe/gun/{gun}.json", {
            "type": "minecraft:crafting_shaped", "category": "equipment", "pattern": pattern,
            "key": {"M": "#mcwow:gun_metals", "T": "minecraft:tripwire_hook", "P": "#minecraft:planks"},
            "result": {"id": f"mcwow:{gun}"}})
    for aid, (name, color, nugget) in AMMO.items():
        ammo_texture(aid, color)
        write(f"assets/mcwow/models/item/{aid}.json", {"parent": "minecraft:item/generated",
                                                       "textures": {"layer0": f"mcwow:item/{aid}"}})
        write(f"assets/mcwow/items/{aid}.json", {"model": {"type": "minecraft:model", "model": f"mcwow:item/{aid}"}})
        lang[f"item.mcwow.{aid}"] = name
        write(f"data/mcwow/recipe/gun/{aid}.json", {
            "type": "minecraft:crafting_shapeless", "category": "equipment",
            "ingredients": ["minecraft:gunpowder", nugget, nugget], "result": {"id": f"mcwow:{aid}", "count": 32}})
    for metal, (name, tint) in NUGGETS.items():
        nid = f"{metal}_nugget"
        write(f"assets/mcwow/items/{nid}.json", {"model": {"type": "minecraft:model", "model": "minecraft:item/iron_nugget",
                                                          "tints": [{"type": "minecraft:constant", "value": tint - 0x1000000}]}})
        lang[f"item.mcwow.{nid}"] = name
        write(f"data/mcwow/recipe/{nid}.json", {"type": "minecraft:crafting_shapeless", "category": "misc",
                                               "ingredients": [f"mcwow:{metal}_bar"], "result": {"id": f"mcwow:{nid}", "count": 9}})
        write(f"data/mcwow/recipe/{metal}_bar_from_nuggets.json", {
            "type": "minecraft:crafting_shaped", "category": "misc", "pattern": ["NNN", "NNN", "NNN"],
            "key": {"N": f"mcwow:{nid}"}, "result": {"id": f"mcwow:{metal}_bar"}})


def wands(lang):
    write("data/mcwow/tags/item/wand_metals.json", {"replace": False, "values": WAND_METALS})
    for school, (name, gem, catalyst) in WAND_SCHOOLS.items():
        wid = f"{school}_wand"
        wand_texture(school, gem)
        write(f"assets/mcwow/models/item/{wid}.json", {"parent": "minecraft:item/handheld",
                                                       "textures": {"layer0": f"mcwow:item/{wid}"}})
        write(f"assets/mcwow/items/{wid}.json", {"model": {"type": "minecraft:model", "model": f"mcwow:item/{wid}"}})
        lang[f"item.mcwow.{wid}"] = f"Wand of {name}"
        write(f"data/mcwow/recipe/wand/{wid}.json", {
            "type": "minecraft:crafting_shaped", "category": "equipment",
            "pattern": ["C", "M", "S"],
            "key": {"C": catalyst, "M": "#mcwow:wand_metals", "S": "minecraft:stick"},
            "result": {"id": f"mcwow:{wid}"}})
        lang[f"death.attack.mcwow.spell_{school}"] = f"%1$s was slain by {name.lower()} magic"
        lang[f"death.attack.mcwow.spell_{school}.player"] = f"%1$s was slain by %2$s's {name.lower()} magic"


def main():
    lang_path = os.path.join(RES, "assets/mcwow/lang/en_us.json")
    lang = json.load(open(lang_path))
    face = lambda tex: {d: {"texture": tex, "cullface": d} for d in ("down", "up", "north", "south", "west", "east")}
    write("assets/mcwow/models/block/ore.json", {
        "parent": "minecraft:block/block",
        "textures": {"particle": "#base"},
        "elements": [{"from": [0, 0, 0], "to": [16, 16, 16], "faces": face("#base")},
                     {"from": [0, 0, 0], "to": [16, 16, 16], "faces": face("#ore")}]})
    blocks, by_tool = [], {"stone": [], "iron": []}
    for ore, (name, shades, bar, raw, tool) in ORES.items():
        speck_overlay(ore, shades)
        for variant, base, prefix in (("", "minecraft:block/stone", ""),
                                      ("deepslate_", "minecraft:block/deepslate", "Deepslate ")):
            bid = f"{variant}{ore}_ore"
            blocks.append(bid)
            by_tool[tool].append(f"mcwow:{bid}")
            write(f"assets/mcwow/models/block/{bid}.json", {"parent": "mcwow:block/ore", "textures": {
                "base": base, "ore": f"mcwow:block/{ore}_ore_overlay"}})
            write(f"assets/mcwow/blockstates/{bid}.json", {"variants": {"": {"model": f"mcwow:block/{bid}"}}})
            write(f"assets/mcwow/items/{bid}.json", {"model": {"type": "minecraft:model", "model": f"mcwow:block/{bid}"}})
            write(f"data/mcwow/loot_table/blocks/{bid}.json", {"type": "minecraft:block", "pools": [{"rolls": 1, "entries": [
                {"type": "minecraft:alternatives", "children": [
                    {"type": "minecraft:item", "condition": "minecraft:tool/can_silk_touch", "name": f"mcwow:{bid}"},
                    {"type": "minecraft:item", "name": f"mcwow:raw_{ore}", "modifier": [
                        {"type": "minecraft:apply_bonus", "enchantment": "minecraft:fortune", "formula": "minecraft:ore_drops"},
                        {"type": "minecraft:explosion_decay"}]}]}]}],
                "random_sequence": f"mcwow:blocks/{bid}"})
            lang[f"block.mcwow.{bid}"] = f"{prefix}{name} Ore"
        write(f"assets/mcwow/items/raw_{ore}.json", {"model": {"type": "minecraft:model", "model": "minecraft:item/raw_iron",
                                                              "tints": [{"type": "minecraft:constant", "value": raw - 0x1000000}]}})
        lang[f"item.mcwow.raw_{ore}"] = f"{name} Ore Chunk"
        for kind in ("smelting", "blasting"):
            write(f"data/mcwow/recipe/{ore}_bar_from_{kind}.json", {
                "type": f"minecraft:{kind}", "cookingtime": 200 if kind == "smelting" else 100, "experience": 0.7,
                "group": f"{ore}_bar", "ingredient": f"mcwow:raw_{ore}", "result": {"id": f"mcwow:{ore}_bar"}})
    for alloy, (name, tint) in [(o, (v[0], v[2])) for o, v in ORES.items()] + list(ALLOYS.items()):
        write(f"assets/mcwow/items/{alloy}_bar.json", {"model": {"type": "minecraft:model", "model": "minecraft:item/iron_ingot",
                                                                 "tints": [{"type": "minecraft:constant", "value": tint - 0x1000000}]}})
        lang[f"item.mcwow.{alloy}_bar"] = f"{name} Bar"
    for mid, (name, tint, base) in MATERIALS.items():
        write(f"assets/mcwow/items/{mid}.json", {"model": {"type": "minecraft:model", "model": f"minecraft:item/{base}",
                                                          "tints": [{"type": "minecraft:constant", "value": tint - 0x1000000}]}})
        lang[f"item.mcwow.{mid}"] = name
    # WoW's alloys: 1 copper + 1 tin = 2 bronze; steel = iron + coal.
    write("data/mcwow/recipe/bronze_bar.json", {"type": "minecraft:crafting_shapeless", "category": "misc",
                                                "ingredients": ["minecraft:copper_ingot", "mcwow:tin_bar"],
                                                "result": {"id": "mcwow:bronze_bar", "count": 2}})
    write("data/mcwow/recipe/steel_bar.json", {"type": "minecraft:crafting_shapeless", "category": "misc",
                                               "ingredients": ["minecraft:iron_ingot", ["minecraft:coal", "minecraft:charcoal"]],
                                               "result": {"id": "mcwow:steel_bar"}})
    # Tool tags (merged with the waygate's entries).
    def tag(rel, values):
        p = os.path.join(RES, rel)
        old = json.load(open(p))["values"] if os.path.exists(p) else []
        keep = [v for v in old if not any(v == f"mcwow:{b}" for b in blocks)]
        write(rel, {"replace": False, "values": keep + values})
    tag("data/minecraft/tags/block/mineable/pickaxe.json", [f"mcwow:{b}" for b in blocks])
    for tool, ids in by_tool.items():
        tag(f"data/minecraft/tags/block/{TOOL_TAG[tool]}.json", ids)

    wands(lang)
    weapons(lang)
    with open(lang_path, "w") as f:
        json.dump(lang, f, indent=2)
        f.write("\n")
    gear_recipes()
    herbs()
    print(f"{len(blocks)} ore blocks, gear recipes for {len(GEAR)} metals + {len(LEATHER_IDS)} leathers + {len(CLOTH_IDS)} cloths")


main()
