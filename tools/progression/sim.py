#!/usr/bin/env python3
"""The 1-60 progression model (2026-10-04). A rough, deterministic, expected-value walk of one
player through a route (route_*.json): quests and grinding in each zone, fights as time-to-kill and
damage taken per creature, kill/quest XP, loot, quest rewards, vendors, crafting, mining, repairs
and food. It answers "is the journey still playable after this change?" - not "is it fun".

Inputs (tools/progression.sh makes them):
  wow.json   - WoW data from the DB (extract.py)
  rules.json - the MOD's rules evaluated by the mod itself (McwowSimExport): gear stats, recipes,
               damage conversions, loot per creature, quest rewards as Minecraft items, vendor
               offers, ore yields and dig times. Nothing of those is re-implemented here.
Mirrored here (not in the mod): VMaNGOS's kill/quest XP formulas (src/game/Formulas.h,
QuestDef.cpp) and the quest-money -> emerald rule (McwowQuestRewards.grant). Everything else this
file decides is a modelling KNOB (below), listed in every report.

Output: report.md (for reading, Claude first), report.json (everything), and a diff against
baseline.json when there is one."""
import json, math, os, sys
from collections import Counter, defaultdict

from alchemy import Alchemy, USED
from treasure import BOOK, Treasure, kind_bonus, protection, sharp_bonus, wear_factor

# ---- model knobs: assumptions, not game rules -------------------------------------------------
KNOBS = {
    "search_s": 20,          # finding and walking to the next target creature
    "approach_s": 1.0,       # closing in before the first swing
    "quest_overhead_s": 240, # per quest: pick up, travel, turn in, read
    "object_s": 30,          # per quest object gathered / used / unknown item source
    "hit_rate": 0.95,        # player swings that land
    "crit_mult": 1.15,       # average damage bonus from jump crits (30% x 1.5)
    "creature_hit_rate": 0.92,
    "regen_hp_s": 1.0,       # healing while eating between fights (Minecraft hp per second)
    "food_per_hp": 1.5,      # food+saturation points per hp healed (6 exhaustion = 1.5 points)
    "food_drain_min": 0.6,   # food points per minute of play from moving/sprinting
    "death_cost_s": 180,     # respawn at bed/hearth and walk back
    "safe_dmg": 12.0,        # damage per fight (of 20 hp) above which deaths start
    "quest_ahead": 3,        # quests up to this many levels above the player are taken
    "grind_band": [-3, 1],   # grind targets relative to the player's level
    "mine_gain": 4,          # ilvl gain (weapon or armor average) that is worth a mining trip
    "mine_move_s": 0.25,     # walking per block dug
    "durability_hits": 1,    # weapon durability lost per hit
    "repair_fraction": 0.25, # one repair item restores this much of a piece's durability (anvil)
    "max_trip_min": 90,      # longest mining trip a player takes for an upgrade or repairs
    "max_item_kills": 60,    # more kills than this for one quest item: the quest is skipped
    "kill_chunk": 10,        # kills between repair/eat/level checks
    "stock_repairs": 2,      # full repairs of the worn gear a player carries out of a zone
    "vein_s": 90,            # finding and walking to the next WoW ore vein (McwowNodes), besides mining it
    "vein_min_spawns": 5,    # vein kinds with fewer spawn points in a zone don't count as a source
    "veins_per_hour": 4,     # veins passed and mined along the way per hour of questing/grinding
    "herbs_per_hour": 6,     # herb nodes passed and gathered along the way per hour of questing/grinding
    "herb_gather_s": 1.5,    # gathering one herb (bare hand ~1.2 s, a hoe or shears quicker)
    "alchemy": True,         # brew and use potions (alchemy.py); the report also runs once without
    "chests": True,          # open WoW's treasure chests on the way (treasure.py); the report also runs once without
    "enchanting": True,      # use enchanted books and gear enchantments (treasure.py)
    "chests_per_hour": 1.0,  # chests found and opened per hour of questing/grinding in a zone with chest_ref_up up
    "chest_ref_up": 15,      # chests up at a time in a zone that gives chests_per_hour (more up: more found, up to 2x)
    "chest_s": 30,           # the detour to a chest, besides opening it
    "enchant_first_visit": 4,   # trips to a capital's enchanting supplier from this level (when 2+ useful books are affordable)
    "enchant_visit_levels": 3,  # at least this many levels apart
    "enchant_visit_s": 420,     # the trip (there, shop, back)
    "emerald_reserve": 16,      # emeralds kept back from books for food and repairs
    "style": "melee",        # melee | wand | rifle | blunderbuss: the weapon fights use (ranged ones of the melee weapon's metal)
    "ranged_free_s": 3.0,    # a wand/rifle fight's opening seconds before the creature reaches the player
    "blunderbuss_free_s": 0.8,  # ... a blunderbuss's (short range)
    "pellet_hit": 0.7,       # share of a blunderbuss's pellets that hit
}
NOT_MODELLED = ("enchantments besides sharpness/smite/bane/protection/unbreaking", "anvil costs", "bows/crossbows and arrows", "shields", "potions",
                "dungeons and group quests", "travel between zones", "rested XP", "quest chains across zones",
                "XP orbs left behind", "smelting fuel and time", "inventory space")
FREE = ("stick", "planks", "cobblestone", "cobbled_deepslate", "blackstone", "string")  # gathered anywhere
ARMOR = ("helmet", "chestplate", "leggings", "boots")
WEAPONS = ("sword", "axe", "spear")
FOOD = {"minecraft:bread": 11, "minecraft:cooked_beef": 20.8, "minecraft:beef": 20.8, "minecraft:porkchop": 20.8,
        "minecraft:cooked_porkchop": 20.8, "minecraft:mutton": 15.6, "minecraft:chicken": 13.2,
        "minecraft:cooked_chicken": 13.2, "minecraft:cod": 11, "minecraft:cooked_cod": 11, "minecraft:salmon": 15.6,
        "minecraft:rabbit": 11, "minecraft:apple": 6.4, "minecraft:honey_bottle": 7.2, "minecraft:cookie": 2.4,
        "minecraft:pumpkin_pie": 12.8, "minecraft:sweet_berries": 2.4, "minecraft:potion": 0}


def kill_xp(pl, mob, elite, xp_mult, rates):
    """VMaNGOS MaNGOS::XP::Gain for a solo player."""
    gray = 0 if pl <= 5 else pl - 5 - pl // 10 if pl <= 39 else pl - 1 - pl // 5
    if mob >= pl:
        f = 1.0 + 0.05 * min(mob - pl, 4)
    elif mob > gray:
        zd = 5 if pl < 8 else 6 if pl < 10 else 7 if pl < 12 else 8 if pl < 16 else 9 if pl < 20 else \
            11 if pl < 30 else 12 if pl < 40 else 13 if pl < 45 else 14 if pl < 50 else 15 if pl < 55 else 16 if pl < 60 else 17
        f = (zd + mob - pl) / zd
    else:
        return 0
    xp = (pl * 5 + 45) * f
    if elite:
        xp *= 2.0 * rates["kill_elite"]
    return round(xp * xp_mult * rates["kill"])


def quest_xp(pl, q, rates):
    """VMaNGOS Quest::XPValue."""
    full, ql = q["xp"], q["level"]
    if full <= 0:
        return 0
    f = 1.0 if pl <= ql + 5 else {6: 0.8, 7: 0.6, 8: 0.4, 9: 0.2}.get(pl - ql, 0.1)
    return math.ceil(full * f * rates["quest"])


class Sim:
    def __init__(self, wow, rules, route):
        self.w, self.r, self.route = wow, rules, route
        self.c = wow["creatures"]
        cb = rules["combat"]
        self.wow_per_mc, self.mc_per_wow, self.af = cb["wow_per_mc"], cb["mc_per_wow"], cb["armor_factor"]
        # the difficulty curve on hits against the player (McwowCombat.takenCurve), by attacker level
        self.curve = cb.get("taken_curve", [1.0] * 63)
        self.gear_at = {}  # level -> the gear worn when it ended (the curve table)
        self.emerald_copper = cb["emerald_copper"]
        self.level, self.xp, self.t = 1, 0, 0.0
        self.inv = Counter()
        self.ledger = defaultdict(Counter)  # item -> {loot, mined, reward, bought, crafted, used, sold, repair, eaten}
        self.food = 20.0
        mats = rules["materials"]
        stone = dict(mats["stone"]["pieces"]["sword"], name="Worn Shortsword (starter)")
        self.gear = {"weapon": stone}
        for s in ARMOR:
            self.gear[s] = None
        self.wear = Counter()  # slot -> durability used
        self.pickaxe = "stone"  # cobblestone is everywhere
        self.inv["minecraft:bread"] = 4  # starting kit (McwowGear, JOIN)
        self.ref_hit = {}  # creature level -> median melee hit (armor scoring)
        for c in wow["creatures"].values():
            if c["rank"] == 0:
                self.ref_hit.setdefault(c["level"], []).append(c["hit"])
        self.ref_hit = {l: sorted(v)[len(v) // 2] for l, v in self.ref_hit.items()}
        spell = defaultdict(list)  # creature level -> average spell share (armor scoring)
        for c in wow["creatures"].values():
            if c["rank"] == 0 and c["attackable"]:
                spell[c["level"]].append(c.get("spell_share", 0.0))
        self.ref_spell = {l: sum(v) / len(v) for l, v in spell.items()}
        self.done, self.skipped, self.excl_done = set(), {}, set()
        self.rows = {}  # level -> stats
        self.events = []  # gear changes etc.
        self.flags = []
        self.zone = None
        self.knobs = dict(KNOBS)
        self.herb_names = Counter()  # herbs gathered by WoW name
        self.alchemy = None  # made in run(), after the knobs are set
        self.treasure = None  # chests and books, made in run()
        self.zones = []  # per zone visited: levels, hours, quests
        self.gear_recipes = [x for x in rules["recipes"] if "gear" in x["result"]]
        self.makes = defaultdict(list)  # item -> recipes producing it (non-gear)
        for x in rules["recipes"]:
            if "gear" not in x["result"]:
                self.makes[x["result"]["item"]].append(x)

    # ---- bookkeeping ----------------------------------------------------------------------------
    def row(self):
        r = self.rows.get(self.level)
        if r is None:
            r = self.rows[self.level] = {"level": self.level, "zone": self.zone_name, "s": 0.0, "quest_s": 0.0,
                                         "grind_s": 0.0, "mine_s": 0.0, "death_s": 0.0, "quest_xp": 0, "kill_xp": 0,
                                         "kills": 0.0, "deaths": 0.0, "fights": [], "quests": 0, "chest_s": 0.0}
        return r

    def spend(self, seconds, kind):
        self.t += seconds
        r = self.row()
        r["s"] += seconds
        r[kind + "_s"] += seconds
        self.food -= self.knob("food_drain_min") * seconds / 60
        if kind in ("quest", "grind"):
            self.veins_on_the_way(seconds)
            self.alchemy.gather(seconds)
            self.treasure.on_the_way(seconds)
            self.alchemy.tick(seconds)

    def veins_on_the_way(self, seconds):
        """Veins passed while questing are mined (McwowNodes): a mix of the zone's veins by spawn count."""
        veins = [(n, c) for n, c in self.w.get("veins", {}).get(str(self.zone), {}).items()
                 if c >= self.knob("vein_min_spawns") and n in self.r.get("veins", {})]
        total = sum(c for _, c in veins)
        if not total:
            return
        count = self.knob("veins_per_hour") * seconds / 3600
        for name, c in veins:
            v = self.r["veins"][name]
            b = self.r["blocks"].get(v["ore"], {}).get("pickaxe", {}).get(self.pickaxe)
            if not b or not b["correct"]:
                continue
            n = count * c / total
            secs = n * v["blocks"] * b["seconds"]
            self.t += secs
            r = self.row()
            r["s"] += secs
            r["mine_s"] += secs
            self.gain(v["item"], n * v["per_harvest"], "veins")

    def knob(self, k):
        return self.knobs[k]

    def gain(self, item, n, how):
        if n <= 0:
            return
        self.inv[item] += n
        self.ledger[item][how] += n

    def take(self, item, n, how):
        self.inv[item] -= n
        self.ledger[item][how] += n

    def add_xp(self, xp, kind):
        if self.level >= 60:
            return
        self.row()[kind + "_xp"] += xp
        self.xp += xp
        while self.level < 60 and self.xp >= self.w["xp_for_level"][str(self.level)]:
            self.xp -= self.w["xp_for_level"][str(self.level)]
            self.close_row()
            self.level += 1
            self.row()
            self.upgrade(level_up=True)

    def close_row(self):
        r = self.row()
        r["weapon"] = self.weapon()["gear"][1] if self.weapon().get("gear") else 1
        r["weapon_name"] = self.describe(self.weapon())
        r["armor"] = round(self.armor_ilvl(), 1)
        r["armor_points"] = sum((g or {}).get("armor", 0) for s, g in self.gear.items() if s != "weapon")
        r["emeralds"] = round(self.inv["minecraft:emerald"], 1)
        r["hours"] = round(self.t / 3600, 2)
        r["pickaxe"] = self.pickaxe
        self.gear_at[self.level] = {k: (dict(v) if v else None) for k, v in self.gear.items()}

    def describe(self, g):
        if not g:
            return "-"
        if g.get("name"):
            return g["name"]
        m = g.get("gear")
        return f"{m[0]} {g['item'].split(':')[1].split('_')[-1]} ({m[1]})" if m else g["item"]

    # ---- combat -----------------------------------------------------------------------------------
    def weapon(self):
        w = self.gear["weapon"]
        if w and (not w.get("gear") or w["gear"][2] <= self.level):
            return w
        return {"attack": 1.0, "speed": 4.0, "item": "hand"}

    def armor_ilvl(self):
        return sum((g["gear"][1] if g and g.get("gear") and g["gear"][2] <= self.level else 0)
                   for s, g in self.gear.items() if s != "weapon") / 4.0

    def ranged(self):
        """The ranged weapon the style fights with: {per_shot Minecraft damage, interval s, ilvl, free s,
        ammo_cost emeralds per shot}, of the melee weapon's metal (a wand or gun crafted from the same
        bar); None for melee."""
        style = self.knob("style")
        rg = self.r.get("ranged")
        if style == "melee" or not rg:
            return None
        w = self.weapon()
        mat = w["gear"][0] if w.get("gear") and w["gear"][0] in rg["materials"] else "copper"
        m = rg["materials"][mat]
        ilvl = w["gear"][1] if w.get("gear") and w["gear"][0] == mat else m["ilvl"]
        if style == "wand":
            cloth = sum(1 for s, g in self.gear.items() if s != "weapon" and g and g.get("class") == "cloth"
                        and g.get("gear") and g["gear"][2] <= self.level)
            dmg = m["wand"] * (1 + rg["cloth_power"] * cloth) * (1 + rg["dot_share"])  # a fire wand: its burn on top
            return {"per_shot": dmg, "interval": rg["wand_interval"], "ilvl": ilvl, "free": self.knob("ranged_free_s"),
                    "ammo_cost": 0.0}
        tiers = [a for a in rg["ammo"] if a["req"] <= self.level]
        tier_i = len(tiers) - 1
        mult = tiers[-1]["multiplier"] if tiers else 1.0
        cost = (tier_i + 1) / rg.get("ammo_per_lot", 16)  # engineering vendors: a lot of shots for tier+1 emeralds
        if style == "rifle":
            return {"per_shot": m["rifle"] * mult, "interval": rg["rifle_interval"], "ilvl": ilvl,
                    "free": self.knob("ranged_free_s"), "ammo_cost": cost}
        return {"per_shot": m["pellet"] * mult * rg["pellets"] * self.knob("pellet_hit"), "interval": rg["blunderbuss_interval"],
                "ilvl": ilvl, "free": self.knob("blunderbuss_free_s"), "ammo_cost": cost}

    def fight(self, entry):
        """One fight against a creature: time to kill, Minecraft damage taken, death chance."""
        c = self.c[str(entry)]
        rw = self.ranged()
        if rw:
            return self.ranged_fight(c, rw)
        w = self.weapon()
        ilvl = w["gear"][1] if w.get("gear") else 1
        on = self.knob("enchanting")
        mc_hit = (w["attack"] + sharp_bonus(w, on) + kind_bonus(w, self.r["loot"].get(str(entry), {}), on)
                  + self.alchemy.strength()) * self.knob("crit_mult")
        wow_hit = max(1.0, mc_hit * self.wow_per_mc[min(63, max(1, ilvl)) - 1])
        hits = math.ceil(max(1, c["hp"]) / wow_hit) / self.knob("hit_rate")
        ttk = hits / w["speed"] + self.knob("approach_s")
        lvl = min(63, max(1, c["level"]))
        factor = self.af[lvl - 1][min(70, round(self.armor_ilvl()))]
        per_hit = self.mitigate(c["hit"] * self.mc_per_wow[lvl - 1] * self.curve[lvl - 1] * factor,
                                c.get("spell_share", 0.0), self.gear)
        swings = ttk / (c["attack_ms"] / 1000.0) * self.knob("creature_hit_rate")
        dmg = swings * per_hit
        safe = self.knob("safe_dmg")
        death = 1.0 if dmg >= 20 else max(0.0, (dmg - safe) / (20 - safe)) * 0.5
        return {"ttk": ttk, "dmg": dmg, "death": death, "hits": hits, "taken": swings, "level": c["level"]}

    def ranged_fight(self, c, rw):
        """A fight with a wand or gun: shots at its interval; the creature lands no hits for the
        opening free seconds (it is still closing in)."""
        wow_hit = max(1.0, (rw["per_shot"] + self.alchemy.strength()) * self.wow_per_mc[min(63, max(1, rw["ilvl"])) - 1])
        hits = math.ceil(max(1, c["hp"]) / wow_hit) / self.knob("hit_rate")
        ttk = hits * rw["interval"] + self.knob("approach_s")
        lvl = min(63, max(1, c["level"]))
        factor = self.af[lvl - 1][min(70, round(self.armor_ilvl()))]
        per_hit = self.mitigate(c["hit"] * self.mc_per_wow[lvl - 1] * self.curve[lvl - 1] * factor,
                                c.get("spell_share", 0.0), self.gear)
        swings = max(0.0, ttk - rw["free"]) / (c["attack_ms"] / 1000.0) * self.knob("creature_hit_rate")
        dmg = swings * per_hit
        safe = self.knob("safe_dmg")
        death = 1.0 if dmg >= 20 else max(0.0, (dmg - safe) / (20 - safe)) * 0.5
        return {"ttk": ttk, "dmg": dmg, "death": death, "hits": hits, "taken": swings, "level": c["level"],
                "ammo_cost": rw["ammo_cost"] * hits}

    def kill(self, entry, n, kind):
        """n kills of a creature in chunks (repairs, food and level-ups in between). False if it
        can't be fought."""
        if self.fight(entry)["dmg"] >= 20:
            return False
        left = n
        while left > 1e-9:
            k = min(self.knob("kill_chunk"), left)
            self.kill_chunk(entry, k, kind)
            left -= k
            self.repair()
        return True

    def kill_chunk(self, entry, n, kind):
        f = self.fight(entry)
        c = self.c[str(entry)]
        # Healing potions in a dangerous fight: less to rest off, fewer deaths.
        healed = self.alchemy.heal_fight(f["dmg"], n)
        dmg = max(0.0, f["dmg"] - healed)
        safe = self.knob("safe_dmg")
        death = 0.0 if dmg <= safe else max(0.0, (dmg - safe) / (20 - safe)) * 0.5
        heal = min(dmg, 20)
        rest = heal / self.knob("regen_hp_s")
        rate = self.knob("regen_hp_s") + self.alchemy.regen_rate(rest * n)
        cycle = self.knob("search_s") / self.alchemy.speed() + f["ttk"] + heal / rate
        self.spend(cycle * n, kind)
        deaths = death * n
        if deaths:
            self.spend(deaths * self.knob("death_cost_s"), "death")
            self.row()["deaths"] += deaths
        self.food -= heal * self.knob("food_per_hp") * n
        r = self.row()
        r["kills"] += n
        r["fights"].append((n, f["ttk"], f["dmg"], c["level"], int(entry)))
        on = self.knob("enchanting")
        self.wear["weapon"] += f["hits"] * n * self.knob("durability_hits") * wear_factor(self.gear["weapon"], False, on)
        if f.get("ammo_cost"):
            cost = f["ammo_cost"] * n
            self.take("minecraft:emerald", cost, "ammo")
            if self.inv["minecraft:emerald"] < 0:
                self.row()["ammo_short"] = self.row().get("ammo_short", 0) + 1
        for s in ARMOR:
            if self.gear[s]:
                # vanilla: each armor piece loses max(1, damage / 4) per hit taken
                self.wear[s] += (f["taken"] * n * max(1.0, f["dmg"] / max(1, f["taken"]) / 4.0)
                                 * wear_factor(self.gear[s], True, on))
        for item, avg in self.r["loot"].get(str(entry), {}).items():
            if item == "#gear":
                self.treasure.gear_credit(avg * n, c["level"], "creature")
            if item.startswith("#"):
                continue
            self.gain(item, avg * n, "loot")
            if item == BOOK and self.r.get("enchanting"):
                power = self.r["enchanting"]["creature_book_power"][min(63, c["level"]) - 1]
                self.treasure.add_books(avg * n, power, "creature")
        elite = c["rank"] in (1, 2, 3)
        self.add_xp(round(kill_xp(self.level, c["level"], elite, c["xp_mult"], self.w["rates"]) * n), "kill")
        self.eat()

    def eat(self):
        """Keep the food bar up: own food first, then bread from a vendor in the zone."""
        if self.food >= 0:
            return
        for item, pts in sorted(FOOD.items(), key=lambda kv: -kv[1]):
            while self.food < 0 and pts > 0 and self.inv[item] >= 1:
                self.take(item, 1, "eaten")
                self.food += pts
        if self.food < 0:
            offer = self.food_offer()
            if offer:
                need = math.ceil(-self.food / (FOOD[offer["result"]["item"]] * offer["result"]["count"]))
                cost = need * offer["cost_count"]
                if self.inv["minecraft:emerald"] >= cost:
                    self.take("minecraft:emerald", cost, "food")
                    self.ledger[offer["result"]["item"]]["bought"] += need * offer["result"]["count"]
                    self.food += need * offer["result"]["count"] * FOOD[offer["result"]["item"]]
            if self.food < 0:
                self.row().setdefault("hungry", 0)
                self.row()["hungry"] += -self.food
                self.food = 0  # starving is modelled as a flag, not as a stop

    def food_offer(self):
        best = None
        for v in self.vendors():
            for o in v["offers"]:
                it = o["result"]["item"]
                if o["cost"] == "minecraft:emerald" and FOOD.get(it, 0) > 0:
                    val = FOOD[it] * o["result"]["count"] / o["cost_count"]
                    if not best or val > best[0]:
                        best = (val, o)
        return best[1] if best else None

    # ---- gear -------------------------------------------------------------------------------------
    def vendors(self):
        out = []
        for e in self.w["vendors"].get(str(self.zone), []):
            v = self.r["vendors"].get(str(e))
            if v:
                out.append(v)
        return out

    def slot_of(self, g):
        p = g["item"].split(":")[1].split("_")[-1]
        return p if p in ARMOR else "weapon" if p in WEAPONS else None

    def score(self, slot, g):
        """How good a piece is for the player now (0 = unusable)."""
        if not g or not g.get("gear") or g["gear"][2] > self.level:
            return 0.0
        if slot == "weapon":
            dmg = self.treasure.weapon_damage_value(g) if self.treasure and self.knob("enchanting") else 0.0
            return ((g["attack"] + dmg) * g["speed"]
                    * self.wow_per_mc[min(63, g["gear"][1]) - 1])
        return 1.0 / self.taken_factor({slot: g}) - 1.0 / self.taken_factor({slot: None})

    def mitigate(self, per_hit, spell_share, gear):
        """A hit after Minecraft's armor formula: physical against armor points, spell hits (magic
        damage) against the armor classes' spell protection (McwowCombat); then Protection."""
        pieces = [g for s, g in gear.items() if s != "weapon" and g]
        def cut(armor, tough):
            c = min(20.0, max(armor / 5.0, armor - per_hit / (2.0 + tough / 4.0)))
            return per_hit * (1.0 - c / 25.0)
        phys = cut(sum(g.get("armor", 0) for g in pieces), sum(g.get("toughness", 0) for g in pieces))
        spell = cut(sum(g.get("spell", 0) for g in pieces), sum(g.get("spell_toughness", 0) for g in pieces))
        return ((1.0 - spell_share) * phys + spell_share * spell) * (1.0 - 0.04 * protection(pieces, self.knob("enchanting")))

    def taken_factor(self, swap):
        """Minecraft damage per WoW hit of a typical creature of the player's level, with the
        armor slots in `swap` replaced."""
        gear = {s: (swap[s] if s in swap else self.gear[s]) for s in ARMOR}
        usable = {s: g for s, g in gear.items() if g and g.get("gear") and g["gear"][2] <= self.level}
        ilvl = sum(g["gear"][1] for g in usable.values()) / 4.0
        lvl = min(63, self.level)
        hit = self.ref_hit.get(lvl) or 10.0
        per_hit = hit * self.mc_per_wow[lvl - 1] * self.curve[lvl - 1] * self.af[lvl - 1][min(70, round(ilvl))]
        return self.mitigate(per_hit, self.ref_spell.get(lvl, 0.0), usable)

    def better(self, slot, g):
        if slot != "weapon":
            if not g or not g.get("gear") or g["gear"][2] > self.level:
                return False
            return self.taken_factor({slot: g}) < self.taken_factor({}) * 0.999
        return self.score(slot, g) > self.score(slot, self.gear[slot]) * 1.0001 + 1e-9

    def equip(self, slot, g, how):
        old = self.gear[slot]
        self.gear[slot] = g
        self.wear[slot] = 0
        self.events.append({"level": self.level, "hours": round(self.t / 3600, 2), "slot": slot, "how": how,
                            "item": self.describe(g), "ilvl": g["gear"][1], "was": self.describe(old)})
        if self.treasure:
            self.treasure.apply()

    def upgrade(self, level_up=False):
        self.alchemy.brew_up()
        if level_up and self.treasure:
            self.treasure.vendor_visit()
        self.repair()
        self.craft_from_inventory()
        self.shop()
        if level_up or self.entered:
            self.entered = False
            self.mine_for_upgrades()
            self.craft_from_inventory()
        self.sell()

    def resolve(self, item, n, plan, depth=0):
        """What it takes to get n of an item: from inventory, free, mined here, or made. Fills plan
        ({"use": Counter, "mine": Counter}); False when the item can't be had in this zone."""
        if n <= 1e-9:
            return True
        if any(item.endswith(f) for f in FREE):
            return True
        have = self.inv[item] - plan["use"][item]
        if have > 0:
            take = min(have, n)
            plan["use"][item] += take
            n -= take
            if n <= 1e-9:
                return True
        if item in self.mineable():
            plan["mine"][item] += n
            return True
        if depth > 4:
            return False
        for x in sorted(self.makes.get(item, []), key=lambda x: x["kind"] != "smelting"):
            if x["kind"] not in ("smelting", "craft"):
                continue
            runs = n / x["result"]["count"]
            trial = {"use": Counter(plan["use"]), "mine": Counter(plan["mine"])}
            if all(self.resolve(opts[0], runs, trial, depth + 1) for opts in self.ingredient_counts(x)):
                plan["use"], plan["mine"] = trial["use"], trial["mine"]
                return True
        return False

    @staticmethod
    def ingredient_counts(x):
        out = []
        for opts in x["ingredients"]:
            free = [o for o in opts if any(o.endswith(f) for f in FREE)]
            out.append(free or opts)
        return out

    def mineable(self):
        """Raw items mined here with the current pickaxe -> best yield per second."""
        cache_key = (self.zone, self.pickaxe)
        if getattr(self, "_mine_key", None) == cache_key:
            return self._mine
        best = {}
        for d, m in self.r["mining"].get(str(self.zone), {}).items():
            rock = self.r["blocks"][m["rock"]]["pickaxe"][self.pickaxe]
            secs = m["dug"] * (rock["seconds"] + self.knob("mine_move_s"))
            drops = Counter()
            for block, cnt in m["ores"].items():
                b = self.r["blocks"].get(block, {}).get("pickaxe", {}).get(self.pickaxe)
                if b and b["correct"]:
                    for it, avg in b["drops"].items():
                        drops[it] += cnt * avg
            for it, total in drops.items():
                rate = total / secs
                if it not in best or rate > best[it]["rate"]:
                    best[it] = {"rate": rate, "depth": int(d), "per_s": {k: v / secs for k, v in drops.items()}}
        # WoW ore veins (McwowNodes): blocks of the ore they're named for, mined with the pickaxe
        for name, count in self.w.get("veins", {}).get(str(self.zone), {}).items():
            v = self.r.get("veins", {}).get(name)
            if not v or count < self.knob("vein_min_spawns"):
                continue
            b = self.r["blocks"].get(v["ore"], {}).get("pickaxe", {}).get(self.pickaxe)
            if not b or not b["correct"]:
                continue
            secs = self.knob("vein_s") + v["blocks"] * (b["seconds"] + self.knob("mine_move_s"))
            per_s = {v["item"]: v["per_harvest"] / secs}
            for it, rate in per_s.items():
                if it not in best or rate > best[it]["rate"]:
                    best[it] = {"rate": rate, "depth": f"{name}s", "per_s": per_s}
        self._mine_key, self._mine = cache_key, best
        return best

    def obtain(self, item, n, why):
        """Make n of an item from the bag, mining what's missing here (a trip of at most
        max_trip_min). False if it can't be had."""
        plan = {"use": Counter(), "mine": Counter()}
        if not self.resolve(item, n, plan):
            return False
        if plan["mine"]:
            if self.trip_minutes(plan["mine"]) > self.knob("max_trip_min"):
                return False
            self.mine(plan["mine"], why)
        for it, k in plan["use"].items():
            self.take(it, k, "used")
        for it, k in plan["mine"].items():
            self.take(it, k, "used")
        self.gain(item, n, "crafted")
        return True

    def trip_minutes(self, needs):
        mine = self.mineable()
        return sum(n / mine[item]["rate"] for item, n in needs.items()) / 60

    def mine(self, needs, why="gear"):
        mine = self.mineable()
        for item, n in needs.items():
            m = mine[item]
            secs = n / m["rate"]
            self.spend(secs, "mine")
            for it, per_s in m["per_s"].items():
                self.gain(it, per_s * secs, "mined")
            self.events.append({"level": self.level, "hours": round(self.t / 3600, 2), "slot": "-", "how": "mined",
                                "item": f"{n:.0f} {item} " + (f"from {m['depth']}" if isinstance(m['depth'], str) else f"at depth {m['depth']}")
                                        + f" ({secs / 60:.0f} min, {self.pickaxe} pickaxe) for {why}",
                                "ilvl": 0, "was": ""})

    def craft(self, x, plan_use):
        for item, n in plan_use.items():
            self.take(item, n, "used")

    def candidates(self):
        """Gear recipes for pieces that are an upgrade now: (gain, slot, recipe)."""
        out = []
        for x in self.gear_recipes:
            g = dict(x["result"])
            mat = self.r["materials"].get(g["gear"][0], {}).get("pieces", {})
            piece = g["item"].split(":")[1].split("_")[-1]
            stats = mat.get(piece)
            if stats:
                g.update({k: v for k, v in stats.items() if k in ("attack", "speed", "armor", "toughness", "durability")})
            if piece == "pickaxe":
                continue
            slot = self.slot_of(g)
            if slot and self.better(slot, g):
                out.append((self.score(slot, g) - self.score(slot, self.gear[slot]), slot, x, g))
        out.sort(key=lambda t: -t[0])
        return out

    def craft_from_inventory(self):
        while True:
            for gain, slot, x, g in self.candidates():
                plan = {"use": Counter(), "mine": Counter()}
                if all(self.resolve(o[0], 1, plan) for o in self.ingredient_counts(x)) and not plan["mine"]:
                    self.craft(x, plan["use"])
                    self.ledger[g["item"]]["crafted"] += 1
                    self.equip(slot, g, "crafted")
                    break
            else:
                break
        self.better_pickaxe(mine_ok=False)

    def better_pickaxe(self, mine_ok):
        """Craft the best pickaxe whose material is at hand (or, if mine_ok, can be mined here)."""
        order = ["stone", "copper", "bronze", "iron", "steel", "mithril", "thorium", "dark_iron"]
        for x in self.gear_recipes:
            g = x["result"]
            if not g["item"].endswith("_pickaxe") or g["gear"][0] not in order:
                continue
            if order.index(g["gear"][0]) <= order.index(self.pickaxe) or g["gear"][2] > self.level:
                continue
            plan = {"use": Counter(), "mine": Counter()}
            if all(self.resolve(o[0], 1, plan) for o in self.ingredient_counts(x)) and (mine_ok or not plan["mine"]):
                if plan["mine"]:
                    self.mine(plan["mine"])
                    plan = {"use": Counter(), "mine": Counter()}
                    if not all(self.resolve(o[0], 1, plan) for o in self.ingredient_counts(x)) or plan["mine"]:
                        continue
                self.craft(x, plan["use"])
                self.events.append({"level": self.level, "hours": round(self.t / 3600, 2), "slot": "pickaxe",
                                    "how": "crafted", "item": g["gear"][0] + " pickaxe", "ilvl": g["gear"][1], "was": self.pickaxe})
                self.pickaxe = g["gear"][0]
                self._mine_key = None

    def mine_for_upgrades(self):
        """A mining trip when the best craftable set from this zone's ground is a real upgrade."""
        self.better_pickaxe(mine_ok=True)
        by_mat = defaultdict(list)
        for gain, slot, x, g in self.candidates():
            by_mat[g["gear"][0]].append((slot, x, g))
        best = None
        for mat, items in by_mat.items():
            chosen = {}
            for slot, x, g in items:
                if slot not in chosen or self.score(slot, g) > self.score(slot, chosen[slot][1]):
                    chosen[slot] = (x, g)
            w_gain = self.score("weapon", chosen["weapon"][1]) / max(1e-9, self.score("weapon", self.gear["weapon"])) if "weapon" in chosen else 0
            new_armor = sum((chosen[s][1]["gear"][1] if s in chosen else
                             (self.gear[s]["gear"][1] if self.gear[s] and self.gear[s].get("gear") else 0)) for s in ARMOR) / 4
            a_gain = new_armor - self.armor_ilvl()
            ilvl_gain = max(a_gain, (chosen["weapon"][1]["gear"][1] - (self.weapon()["gear"][1] if self.weapon().get("gear") else 1))
                            if "weapon" in chosen else 0)
            if ilvl_gain < self.knob("mine_gain"):
                continue
            plan = {"use": Counter(), "mine": Counter()}
            ok = all(self.resolve(o[0], 1, plan) for x, g in chosen.values() for o in self.ingredient_counts(x))
            if not ok:
                continue
            minutes = self.trip_minutes(plan["mine"]) if plan["mine"] else 0
            if minutes > self.knob("max_trip_min"):
                self.flags.append(("slow_mining", self.level, f"{mat} gear in {self.zone_name} needs a "
                                   f"{minutes:.0f} min mining trip ({', '.join(f'{n:.0f} {i}' for i, n in plan['mine'].items())})"))
                continue
            value = ilvl_gain / (minutes + 5)
            if not best or value > best[0]:
                best = (value, mat, plan)
        if best and best[2]["mine"]:
            self.mine(best[2]["mine"], best[1] + " gear")

    def shop(self):
        for v in self.vendors():
            for o in sorted(v["offers"], key=lambda o: o["cost_count"]):
                g = o["result"]
                if o["cost"] != "minecraft:emerald" or not g.get("gear"):
                    continue
                slot = self.slot_of(g)
                if slot and self.better(slot, g) and self.inv["minecraft:emerald"] >= o["cost_count"]:
                    self.take("minecraft:emerald", o["cost_count"], "gear")
                    self.ledger[g["item"]]["bought"] += 1
                    self.equip(slot, g, f"bought ({o['cost_count']} emeralds, {v['type']} vendor)")

    def keep(self):
        """Items worth keeping: what gear of the next materials needs."""
        need = set()
        for x in self.gear_recipes:
            if x["result"]["gear"][2] <= self.level + 10:
                for opts in x["ingredients"]:
                    need.update(opts)
        for m in self.r["materials"].values():
            if m.get("repair"):
                need.add(m["repair"])
        return need

    def sell(self):
        keep = self.keep()
        for v in self.vendors():
            for o in v["offers"]:
                if o["result"]["item"] != "minecraft:emerald":
                    continue
                item, per = o["cost"], o["cost_count"]
                spare = self.inv[item] - (64 if item in keep else 0)
                n = int(spare // per)
                if n > 0:
                    self.take(item, n * per, "sold")
                    self.gain("minecraft:emerald", n, "sales")

    def repair(self):
        """Anvil repairs with the piece's repair item (one restores repair_fraction). Missing items
        are mined if this zone has them (a trip of at most max_trip_min); a piece used up with no
        way to repair it breaks."""
        for slot, g in self.gear.items():
            if not g or not g.get("durability") or not g.get("gear"):
                continue
            unit = g["durability"] * self.knob("repair_fraction")
            if self.wear[slot] < unit:
                continue
            item = self.r["materials"].get(g["gear"][0], {}).get("repair") or ""
            if not item or any(item.endswith(f) for f in FREE) or g["gear"][0] in ("wood", "stone"):
                self.wear[slot] = 0
                continue
            units = math.floor(self.wear[slot] / unit)
            if self.inv[item] < units:
                self.obtain(item, units - self.inv[item], f"repairs ({item})")
            pay = min(units, math.floor(self.inv[item]))
            if pay:
                self.take(item, pay, "repair")
                self.wear[slot] -= pay * unit
            if self.wear[slot] >= g["durability"]:
                self.events.append({"level": self.level, "hours": round(self.t / 3600, 2), "slot": slot, "how": "BROKE",
                                    "item": self.describe(g), "ilvl": 0, "was": f"no {item} to repair with in {self.zone_name}"})
                self.gear[slot] = None if slot != "weapon" else dict(self.r["materials"]["stone"]["pieces"]["sword"])
                self.wear[slot] = 0
                self.craft_from_inventory()
                self.shop()

    # ---- quests -----------------------------------------------------------------------------------
    def item_source(self, item_id, n):
        """How to get n of a quest's required item: ("kill", entry, kills) / ("object", None, count) /
        ("gap", reason)."""
        it = self.w["items"].get(str(item_id), {})
        best = None
        for e, ch in it.get("drops", []):
            c = self.c.get(str(e))
            # Only quest drops (negative chance) reach a bridged player: ClassicCraft::OnKillLoot
            # bags those and clears the rest of the corpse's WoW loot.
            if not c or ch >= 0 or str(self.zone) not in c["spawns"]:
                continue
            kills = n / (-ch / 100.0)
            if not best or kills < best[1]:
                best = (e, kills)
        if not best and not it.get("objects") and any(ch > 0 for _, ch in it.get("drops", [])) \
                and not any(ch < 0 for _, ch in it.get("drops", [])):
            return ("gap", "normal WoW loot drop (cleared for bridged players)")
        if it.get("objects") or (not best and not any(ch < 0 for _, ch in it.get("drops", []))):
            if not best or best[1] > 3 * n:
                return ("object", None, n)  # chests, clams, crates - or given by an NPC/script
        if not best:
            return ("gap", "item source outside the zone")
        if best[1] > self.knob("max_item_kills"):
            return ("gap", f"item too rare ({best[1]:.0f} kills)")
        return ("kill", best[0], best[1])

    def quest_ok(self, qid, q):
        if qid in self.done or qid in self.skipped:
            return False
        if q["min"] > self.level or q["level"] > self.level + self.knob("quest_ahead"):
            return False
        if q["excl"] > 0 and q["excl"] in self.excl_done:
            return False
        prev = q["prev"]
        if prev > 0 and str(prev) in self.w["quests"] and prev not in self.done:
            return False
        return True

    def do_quest(self, qid, q):
        plan = []
        for e, n in q["kills"]:
            if str(e) not in self.c:
                plan.append(("object", None, n))
                continue
            plan.append(("kill", e, n))
        for item, n in q["items"]:
            plan.append(self.item_source(item, n))
        plan += [("object", None, n) for _, n in q["objects"]]
        for p in plan:
            if p[0] == "gap":
                self.skipped[qid] = p[1]
                return
            if p[0] == "kill":
                f = self.fight(p[1])
                if f["dmg"] >= 20:
                    c = self.c[str(p[1])]
                    self.skipped[qid] = f"too hard: {c['name']} (level {c['level']}, rank {c['rank']}) " \
                                        f"deals {f['dmg']:.0f} hp per fight"
                    return
        self.spend(self.knob("quest_overhead_s") / self.alchemy.speed(), "quest")
        for kind, e, n in plan:
            if kind == "kill":
                self.kill(e, n, "quest")
            else:
                self.spend(n * self.knob("object_s") / self.alchemy.speed(), "quest")
        self.done.add(qid)
        if q["excl"] > 0:
            self.excl_done.add(q["excl"])
        self.row()["quests"] += 1
        if q["money"] > 0:  # McwowQuestRewards.grant
            self.gain("minecraft:emerald", max(1, round(q["money"] / self.emerald_copper[self.level - 1])), "quest")
        self.rewards(q)
        self.add_xp(quest_xp(self.level, q, self.w["rates"]), "quest")
        self.upgrade()

    def rewards(self, q):
        rw = self.r["rewards"]
        for item, n in q["reward"]:
            self.take_reward(rw.get(str(item)), n)
        if q["choice"]:
            opts = [rw.get(str(i)) for i, _ in q["choice"]]
            best = max((o for o in opts if o), key=lambda o: self.reward_value(o), default=None)
            if best:
                self.take_reward(best, 1)

    def reward_value(self, o):
        if o.get("gear"):
            slot = self.slot_of(o)
            if slot:
                return 1000 + self.score(slot, o) - self.score(slot, self.gear[slot])
        return FOOD.get(o["item"], 0) + (5 if o.get("enchanted") else 0)

    def take_reward(self, o, n):
        if not o:
            self.row().setdefault("rewards_lost", 0)
            self.row()["rewards_lost"] += 1
            return
        slot = self.slot_of(o) if o.get("gear") else None
        if slot and self.better(slot, o):
            self.equip(slot, o, "quest reward")
        else:
            self.gain(o["item"] + ("#enchanted" if o.get("enchanted") else ""), o["count"] * n, "reward")
            if o["item"] == "minecraft:enchanted_book" and o.get("ench"):
                self.treasure.add_exact(o["ench"], o["count"] * n, "quest")

    # ---- the walk ---------------------------------------------------------------------------------
    def grind(self, until_level, why):
        """Kill the best XP-per-hour creature of the zone until a level is reached."""
        lo, hi = self.knob("grind_band")
        while self.level < until_level and self.level < 60:
            best = None
            for e, c in self.c.items():
                n = c["spawns"].get(str(self.zone), 0)
                if n < 3 or not c["attackable"] or c["rank"] != 0 or c["xp_mult"] <= 0:
                    continue
                if not (self.level + lo <= c["level"] <= self.level + hi):
                    continue
                f = self.fight(e)
                if f["dmg"] >= 20:
                    continue
                cycle = self.knob("search_s") + f["ttk"] + f["dmg"] / self.knob("regen_hp_s") + f["death"] * self.knob("death_cost_s")
                rate = kill_xp(self.level, c["level"], False, c["xp_mult"], self.w["rates"]) / cycle
                if not best or rate > best[0]:
                    best = (rate, e)
            if not best:
                self.flags.append(("no_grind", self.level, f"{self.zone_name}: nothing to grind at level {self.level} ({why})"))
                return False
            need = self.w["xp_for_level"][str(self.level)] - self.xp
            per = kill_xp(self.level, self.c[best[1]]["level"], False, self.c[best[1]]["xp_mult"], self.w["rates"])
            lvl = self.level
            self.kill(best[1], max(1, math.ceil(need / max(1, per))), "grind")
            self.row()["grind_target"] = self.c[best[1]]["name"]
            if self.level == lvl:  # rounding
                self.add_xp(max(0, self.w["xp_for_level"][str(self.level)] - self.xp), "kill")
        return True

    def run(self):
        self.alchemy = Alchemy(self, self.knob("alchemy"))
        self.treasure = Treasure(self, self.knob("chests"), self.knob("enchanting"))
        zones = self.route["zones"]
        for zi, z in enumerate(zones):
            self.zone, self.zone_name, self.entered = z["zone"], z["name"], True
            zstat = {"zone": z["name"], "level_in": self.level, "t_in": self.t, "quests": 0}
            done_before = len(self.done)
            last = zi == len(zones) - 1
            leave = 60 if last else z["leave"]
            quests = sorted(((int(k), q) for k, q in self.w["quests"].items() if q["zone"] == self.zone),
                            key=lambda kq: (kq[1]["level"], kq[1]["min"], kq[0]))
            self.upgrade()
            while self.level < 60:
                todo = [(k, q) for k, q in quests if self.quest_ok(k, q)]
                if todo and (self.level < leave):
                    self.do_quest(*todo[0])
                    continue
                if self.level >= leave:
                    break
                later = [q["min"] for k, q in quests if k not in self.done and k not in self.skipped
                         and self.level < q["min"] <= leave + 2]
                target = min(later) if later else leave
                if not later and not last and self.level >= leave - 2:
                    break  # close enough: move on and level in the next zone
                if not self.grind(min(target, leave), "waiting for quests" if later else "zone out of quests"):
                    break
            for k, q in quests:
                if k in self.done or k in self.skipped:
                    continue
                prev = q["prev"]
                if prev > 0 and str(prev) in self.w["quests"] and prev not in self.done:
                    self.skipped[k] = "left behind: chain not reached"
                elif q["excl"] > 0 and q["excl"] in self.excl_done:
                    self.skipped[k] = "left behind: other quest of its group done"
                elif q["min"] > self.level or q["level"] > self.level + self.knob("quest_ahead"):
                    self.skipped[k] = "left behind: above the player's level when leaving"
                else:
                    self.skipped[k] = "left behind: outleveled (zone left at its leave level)"
            self.stock_up()
            zstat.update(level_out=self.level, hours=round((self.t - zstat.pop("t_in")) / 3600, 2),
                         done=len(self.done) - done_before, offered=len(quests))
            self.zones.append(zstat)
            if self.level >= 60:
                break
        self.close_row()

    def stock_up(self):
        """Leaving a zone: carry repair items for the gear worn (what this zone's ground gives)."""
        need = Counter()
        for slot, g in self.gear.items():
            if g and g.get("gear") and g.get("durability"):
                item = self.r["materials"].get(g["gear"][0], {}).get("repair") or ""
                if item and not any(item.endswith(f) for f in FREE) and g["gear"][0] not in ("wood", "stone"):
                    need[item] += self.knob("stock_repairs") / self.knob("repair_fraction")
        for item, n in need.items():
            if self.inv[item] < n:
                self.obtain(item, n - self.inv[item], f"stock for the road ({item})")


# ---- the difficulty curve ------------------------------------------------------------------------
# Hits a player in gear of their level takes from one even-level normal creature, by level band
# (user, 2026-10-04: early easier, later firmer, the hard part is dungeons and raids). Hits to kill
# stay as they are (~4, the weapon's item level against the creature's level).
TARGET_TAKEN = {1: 10, 11: 9, 21: 8, 31: 7, 41: 6, 51: 6}


def curve_table(sim, scale=None):
    """Per band: median creature health, hits to kill (plain / Strength II), hits the player can
    take, the target. `scale` ({band: x}) multiplies the damage taken (fitting the curve)."""
    import statistics as st
    rows = []
    for lo, target in TARGET_TAKEN.items():
        kills, kills2, takes, hps = [], [], [], []
        for L in range(lo, lo + 10):
            gear = sim.gear_at.get(L)
            cs = [c for c in sim.c.values() if c["rank"] == 0 and c["attackable"] and c["level"] == L and c["hp"] > 0]
            if not gear or not cs:
                continue
            hp, hit = st.median(c["hp"] for c in cs), st.median(c["hit"] for c in cs)
            share = st.mean(c.get("spell_share", 0.0) for c in cs)
            w = gear["weapon"] if gear["weapon"] and gear["weapon"].get("gear", [0, 0, 0])[2] <= L else \
                {"attack": 1.0, "gear": ["hand", 1, 1]}
            per_mc = sim.wow_per_mc[min(63, w["gear"][1]) - 1]
            atk = w["attack"] + sharp_bonus(w, sim.knob("enchanting"))
            kills.append(math.ceil(hp / max(1, round(atk * per_mc))))
            kills2.append(math.ceil(hp / max(1, round((atk + 6) * per_mc))))
            usable = {s: g for s, g in gear.items() if s != "weapon" and g and g.get("gear") and g["gear"][2] <= L}
            ilvl = sum(g["gear"][1] for g in usable.values()) / 4.0
            lvl = min(63, L)
            per_hit = hit * sim.mc_per_wow[lvl - 1] * sim.curve[lvl - 1] * sim.af[lvl - 1][min(70, round(ilvl))]
            per_hit *= (scale or {}).get(lo, 1.0)
            takes.append(20.0 / max(1e-6, sim.mitigate(per_hit, share, usable)))
            hps.append(hp)
        if kills:
            rows.append({"band": f"{lo}-{lo + 9}", "lo": lo, "hp": round(st.median(hps)), "kill": round(st.mean(kills), 1),
                         "kill_str2": round(st.mean(kills2), 1), "take": round(st.mean(takes), 1), "target": target})
    return rows


def fit_taken(sim):
    """Per band, the factor on the damage taken that meets its target (bisection), and the curve
    anchors that gives (the current anchors x factor) - to paste into McwowCombat.TAKEN_CURVE."""
    out = {}
    for lo, target in TARGET_TAKEN.items():
        a, b = 0.05, 20.0
        for _ in range(40):
            m = (a * b) ** 0.5
            row = next((r for r in curve_table(sim, {lo: m}) if r["lo"] == lo), None)
            if row is None:
                break
            a, b = (m, b) if row["take"] > target else (a, m)
        out[lo] = (a * b) ** 0.5
    anchors = [sim.curve[min(62, int(lo + 4.5) - 1)] * out[lo] for lo in TARGET_TAKEN]
    return out, anchors


# ---- report ------------------------------------------------------------------------------------
def summarize(sim):
    rows = []
    for lvl in sorted(sim.rows):
        r = sim.rows[lvl]
        if "hours" not in r:
            continue
        n = sum(f[0] for f in r["fights"])
        avg = lambda i: sum(f[0] * f[i] for f in r["fights"]) / n if n else 0  # kill-weighted
        xp = r["quest_xp"] + r["kill_xp"]
        rows.append({
            "level": lvl, "zone": r["zone"], "minutes": round(r["s"] / 60, 1), "hours": r["hours"],
            "quest_pct": round(100 * r["quest_xp"] / xp) if xp else 0,
            "grind_min": round(r["grind_s"] / 60, 1), "mine_min": round(r["mine_s"] / 60, 1),
            "quests": r["quests"], "kills": round(r["kills"]), "deaths": round(r["deaths"], 2),
            "mob_level": round(avg(3), 1), "ttk_s": round(avg(1), 1), "dmg_hp": round(avg(2), 1),
            "weapon_ilvl": r["weapon"], "armor_ilvl": r["armor"], "armor_points": r["armor_points"],
            "emeralds": r["emeralds"], "hungry": round(r.get("hungry", 0)), "rewards_lost": r.get("rewards_lost", 0),
            "weapon": r["weapon_name"], "pickaxe": r["pickaxe"],
        })
    return rows


def find_flags(sim, rows):
    flags = list(sim.flags)
    for r in rows:
        L = r["level"]
        if r["mob_level"] and r["weapon_ilvl"] < r["mob_level"] - 8:
            flags.append(("weapon_lag", L, f"weapon ilvl {r['weapon_ilvl']} vs creatures {r['mob_level']}"))
        if r["mob_level"] and r["armor_ilvl"] < r["mob_level"] - 10:
            flags.append(("armor_lag", L, f"armor ilvl {r['armor_ilvl']} vs creatures {r['mob_level']}"))
        if r["ttk_s"] > 25:
            flags.append(("slow_kills", L, f"{r['ttk_s']} s to kill"))
        if 0 < r["ttk_s"] < 2.5 and L > 5:
            flags.append(("trivial_kills", L, f"{r['ttk_s']} s to kill"))
        if r["dmg_hp"] > KNOBS["safe_dmg"]:
            flags.append(("dangerous", L, f"{r['dmg_hp']} of 20 hp lost per fight"))
        if r["minutes"] > 90:
            flags.append(("slow_level", L, f"{r['minutes']} min"))
        if r["minutes"] and r["grind_min"] / r["minutes"] > 0.5 and r["minutes"] > 10:
            flags.append(("grind_heavy", L, f"{r['grind_min']} of {r['minutes']} min grinding in {r['zone']}"))
        if r["deaths"] > 1:
            flags.append(("deaths", L, f"{r['deaths']} deaths"))
        if r["hungry"] > 20:
            flags.append(("hungry", L, f"{r['hungry']} food points short (no food, no emeralds for bread)"))
    # economy over the whole run
    em = sim.ledger["minecraft:emerald"]
    if rows and rows[-1]["emeralds"] > 128:
        flags.append(("emerald_pile", 60, f"{rows[-1]['emeralds']} emeralds unspent at the end "
                                          f"(earned {em['loot'] + em['quest'] + em['sales']:.0f}, spent on gear {em['gear']:.0f}, food {em['food']:.0f}, books {em['enchanting']:.0f})"))
    for item, l in sim.ledger.items():
        got = l["loot"] + l["mined"] + l["veins"] + l["reward"] + l["chest"]
        if got >= 64 and l["used"] + l["repair"] == 0 and item.startswith("mcwow:"):
            flags.append(("unused_material", 60, f"{item}: {got:.0f} gained, never crafted with or repaired with"
                                                 f" ({l['sold']:.0f} sold)"))
    mats_used = {e["item"].split(" ")[0] for e in sim.events if e["how"] == "crafted"}
    for m in ("copper", "bronze", "iron", "steel", "mithril", "thorium", "dark_iron"):
        if m not in mats_used and not any(e["item"].startswith(m) for e in sim.events):
            flags.append(("material_skipped", 60, f"no {m} gear crafted or used on the whole route"))
    broke = [e for e in sim.events if e["how"] == "BROKE"]
    for e in broke:
        flags.append(("broke", e["level"], f"{e['item']} broke ({e['was']})"))
    reasons = Counter(v.split(":")[0] if v.startswith("too hard") else
                      v.split(" (")[0] if v.startswith("item too rare") else v for v in sim.skipped.values())
    return flags, reasons


def write_report(sim, out_dir, baseline_path, without=None, plain=None, styles=None):
    rows = summarize(sim)
    flags, reasons = find_flags(sim, rows)
    for c in curve_table(sim):
        if abs(c["take"] - c["target"]) > 0.15 * c["target"]:
            flags.append(("curve_off", c["lo"], f"levels {c['band']}: the player takes {c['take']} hits, target {c['target']}"))
    a = sim.alchemy
    for effect in USED:
        if effect not in a.first:
            flags.append(("potion_never", 60, f"no {effect.split(':')[1]} potion brewed on the whole route"))
    for item, n in a.sold_out.most_common():
        flags.append(("sold_out", 60, f"{item.split(':')[1]}: limited vendor stock ran out {n} times"))
    for zone in sorted(a.no_bottles):
        flags.append(("no_bottles", 60, f"{zone}: wanted to brew, no glass bottles held or sold"))
    for name, n in sim.herb_names.items():
        item = a.herbs[name]["item"]
        if n >= 10 and sim.ledger[item]["brewed"] + sim.ledger[item]["fuel"] < 1:
            flags.append(("herb_unused", 60, f"{name} ({item.split(':')[1]}): {n:.0f} gathered, never brewed with"))
    total_h = round(sim.t / 3600, 1)
    report = {"total_hours": total_h, "final_level": sim.level, "quests_done": len(sim.done),
              "quests_skipped": len(sim.skipped), "deaths": round(sum(r["deaths"] for r in rows), 1),
              "rows": rows, "zones": sim.zones, "events": sim.events, "flags": [list(f) for f in flags],
              "skipped": {str(k): v for k, v in sorted(sim.skipped.items())},
              "ledger": {k: {kk: round(vv, 1) for kk, vv in v.items()} for k, v in sorted(sim.ledger.items())},
              "knobs": KNOBS, "rates": sim.w["rates"], "route": sim.route["name"]}

    L = []
    L.append(f"# Progression sim: {sim.route['name']}")
    L.append(f"Level {sim.level} after **{total_h} h** played; {len(sim.done)} quests done, {len(sim.skipped)} skipped, "
             f"{report['deaths']} deaths. XP rates kill x{sim.w['rates']['kill']}, quest x{sim.w['rates']['quest']}.")
    if without is not None:
        d_h = round(sim.t / 3600 - without.t / 3600, 1)
        d_deaths = round(sum(r["deaths"] for r in sim.rows.values()) - sum(r["deaths"] for r in without.rows.values()), 1)
        L.append(f"Alchemy: {d_h:+} h and {d_deaths:+} deaths against the same run without potions "
                 f"({sum(a.brewed.values()):.0f} potions brewed, {sum(a.used.values()):.0f} used).")
        report["alchemy"] = {"hours_vs_without": d_h, "deaths_vs_without": d_deaths,
                             "brewed": dict(a.brewed), "used": dict(a.used),
                             "first": {k: list(v) for k, v in a.first.items()},
                             "herbs": {k: round(v, 1) for k, v in sim.herb_names.items()}}
    if plain is not None:
        d_h = round(sim.t / 3600 - plain.t / 3600, 1)
        d_deaths = round(sum(r["deaths"] for r in sim.rows.values()) - sum(r["deaths"] for r in plain.rows.values()), 1)
        L.append(f"Chests + enchanting: {d_h:+} h and {d_deaths:+} deaths against the same run without them "
                 f"({sum(sim.treasure.opened.values()):.0f} chests, {len(sim.treasure.applied)} books applied).")
        report["treasure"] = {"hours_vs_without": d_h, "deaths_vs_without": d_deaths,
                              "opened": dict(sim.treasure.opened), "books": dict(sim.treasure.books),
                              "applied": sim.treasure.applied, "unused": dict(sim.treasure.unused)}
    if styles:
        deaths = lambda x: sum(r["deaths"] for r in x.rows.values())
        parts, report["styles"] = [], {}
        for st, x in styles.items():
            em = x.ledger["minecraft:emerald"]
            short = sum(r.get("ammo_short", 0) for r in x.rows.values())
            parts.append(f"{st} {x.t / 3600:.1f} h / {deaths(x):.1f} deaths" + (f" / {em['ammo']:.0f} emeralds on ammo" if em["ammo"] else "")
                         + (f" (ammo unaffordable in {short} fights)" if short else ""))
            report["styles"][st] = {"hours": round(x.t / 3600, 1), "deaths": round(deaths(x), 1), "ammo": round(em["ammo"], 1),
                                    "ammo_short": short}
        L.append(f"Fighting styles (whole walk with that weapon, else the same): melee {sim.t / 3600:.1f} h / "
                 f"{deaths(sim):.1f} deaths; " + "; ".join(parts) + ".")
    diff = diff_report(report, baseline_path)
    if diff:
        L.append("\n## Changes vs baseline")
        L += diff
    L.append("\n## Flags")
    by = defaultdict(list)
    for code, lvl, msg in flags:
        by[code].append((lvl, msg))
    if not by:
        L.append("none")
    for code, items in sorted(by.items(), key=lambda kv: -len(kv[1])):
        lv = sorted({l for l, _ in items})
        span = f"levels {compress(lv)}" if len(lv) > 1 or lv[0] != 60 else "whole run"
        L.append(f"- **{code}** ({len(items)}, {span}): " + "; ".join(f"L{l} {m}" if l != 60 else m for l, m in items[:4])
                 + (" ..." if len(items) > 4 else ""))
    curve = curve_table(sim)
    L.append("\n## Difficulty curve (one even-level normal creature, the player in gear of their level)")
    L.append("| levels | creature hp | hits to kill | with Strength II | hits the player can take | target |")
    L.append("|---|---|---|---|---|---|")
    for c in curve:
        L.append(f"| {c['band']} | {c['hp']} | {c['kill']} | {c['kill_str2']} | {c['take']} | {c['target']} |")
    report["curve"] = curve
    L.append("\n## Zones")
    L.append("| zone | levels | h | quests done/offered | grind min | mine min |")
    L.append("|---|---|---|---|---|---|")
    for z in sim.zones:
        rs = [r for r in rows if r["zone"] == z["zone"]]
        L.append(f"| {z['zone']} | {z['level_in']}-{z['level_out']} | {z['hours']} | {z['done']}/{z['offered']} | "
                 f"{sum(r['grind_min'] for r in rs):.0f} | {sum(r['mine_min'] for r in rs):.0f} |")
    blocked = [(k, v) for k, v in sorted(sim.skipped.items()) if not v.startswith("left behind")]
    if blocked:
        L.append("\n## Quests the bridge can't complete (id, title, zone: reason)")
        for k, v in blocked:
            q = sim.w["quests"][str(k)]
            zn = next((z["name"] for z in sim.route["zones"] if z["zone"] == q["zone"]), q["zone"])
            L.append(f"- {k} {q['title']} (L{q['level']}, {zn}): {v}")
    fights = defaultdict(lambda: [0, 0, 0, 0, 0])
    for r in sim.rows.values():
        for n, ttk, dmg, lvl, e in r["fights"]:
            f = fights[(e, r["level"])]
            f[0] += n; f[1] = ttk; f[2] = dmg
    L.append("\n## Hardest fights (creature, player level: hp lost per fight of 20, seconds to kill, kills)")
    for (e, pl), f in sorted(fights.items(), key=lambda kv: -kv[1][2])[:8]:
        c = sim.c[str(e)]
        L.append(f"- {c['name']} (entry {e}, L{c['level']}, rank {c['rank']}) at player L{pl}: {f[2]:.1f} hp, {f[1]:.1f} s, {f[0]:.0f} kills")
    L.append("\n## Per level")
    L.append("| L | zone | min | h | q% | grind | mine | mob L | ttk s | dmg hp | deaths | weapon | armor | em |")
    L.append("|---|---|---|---|---|---|---|---|---|---|---|---|---|---|")
    for r in rows:
        L.append(f"| {r['level']} | {r['zone']} | {r['minutes']} | {r['hours']} | {r['quest_pct']} | {r['grind_min']} | "
                 f"{r['mine_min']} | {r['mob_level']} | {r['ttk_s']} | {r['dmg_hp']} | {r['deaths']} | "
                 f"{r['weapon_ilvl']} | {r['armor_ilvl']} | {r['emeralds']:.0f} |")
    L.append("\n## Alchemy")
    for effect in USED:
        first = a.first.get(effect)
        L.append(f"- {effect.split(':')[1]}: " + (f"first brewed at L{first[0]} ({first[1].split(':')[1]})" if first else "never brewed"))
    if a.brewed:
        L.append("- potions brewed/used: " + ", ".join(f"{p.split(':')[1]} {a.brewed[p]:.0f}/{a.used[p]:.0f}"
                                                      for p in sorted(a.brewed, key=lambda p: -a.brewed[p])))
    if sim.herb_names:
        L.append("- herbs gathered: " + ", ".join(f"{n} {c:.0f}" for n, c in sim.herb_names.most_common()))
    L += sim.treasure.report()
    L.append("\n## Gear and mining")
    for e in sim.events:
        L.append(f"- L{e['level']} ({e['hours']} h) {e['slot']}: {e['how']} {e['item']}" + (f" (was {e['was']})" if e['was'] else ""))
    L.append("\n## Skipped quests by reason")
    for why, n in reasons.most_common():
        L.append(f"- {n} x {why}")
    L.append("\n## Materials (gained / used / sold / held)")
    for item, l in sorted(sim.ledger.items(), key=lambda kv: -(kv[1]["loot"] + kv[1]["mined"] + kv[1]["veins"] + kv[1]["reward"])):
        got = (l["loot"] + l["mined"] + l["veins"] + l["herbs"] + l["reward"] + l["crafted"] + l["bought"] + l["quest"]
               + l["sales"] + l["chest"])
        if got < 1 or not (item.startswith("mcwow:") or "raw_" in item or "ingot" in item or item.endswith("emerald")
                           or "#enchanted" in item or l["herbs"] or l["brewed"] or l["fuel"]):
            continue
        src = ", ".join(f"{k} {v:.0f}" for k, v in l.items()
                        if k in ("loot", "mined", "veins", "herbs", "bought", "reward", "quest", "sales", "chest") and v >= 0.5)
        out = ", ".join(f"{k} {v:.0f}" for k, v in l.items()
                        if k in ("used", "repair", "sold", "gear", "food", "alchemy", "enchanting", "brewed", "fuel") and v >= 0.5)
        L.append(f"- {item}: +{got:.0f} ({src}); -{', '.join([out]) or '0'}; held {sim.inv[item]:.0f}")
    L.append("\n## Model knobs (assumptions)")
    L.append(", ".join(f"{k}={v}" for k, v in KNOBS.items()))
    L.append("\nNot modelled: " + ", ".join(NOT_MODELLED) + ".")
    open(os.path.join(out_dir, "report.md"), "w").write("\n".join(L) + "\n")
    json.dump(report, open(os.path.join(out_dir, "report.json"), "w"), indent=1)
    return report


def compress(levels):
    out, start, prev = [], None, None
    for l in levels:
        if start is None:
            start = prev = l
        elif l == prev + 1:
            prev = l
        else:
            out.append(f"{start}-{prev}" if start != prev else str(start))
            start = prev = l
    if start is not None:
        out.append(f"{start}-{prev}" if start != prev else str(start))
    return ",".join(out)


def diff_report(now, baseline_path):
    if not baseline_path or not os.path.exists(baseline_path):
        return []
    base = json.load(open(baseline_path))
    out = []
    if base["total_hours"]:
        pct = 100 * (now["total_hours"] - base["total_hours"]) / base["total_hours"]
        out.append(f"- total time {base['total_hours']} -> {now['total_hours']} h ({pct:+.0f}%); "
                   f"quests {base['quests_done']} -> {now['quests_done']}; deaths {base['deaths']} -> {now['deaths']}")
    b_rows = {r["level"]: r for r in base["rows"]}
    changes = []
    for r in now["rows"]:
        b = b_rows.get(r["level"])
        if not b:
            continue
        d = []
        if abs(r["minutes"] - b["minutes"]) > max(5, 0.25 * b["minutes"]):
            d.append(f"min {b['minutes']}->{r['minutes']}")
        for k in ("weapon_ilvl", "armor_ilvl"):
            if abs(r[k] - b[k]) >= 3:
                d.append(f"{k.split('_')[0]} {b[k]}->{r[k]}")
        if abs(r["ttk_s"] - b["ttk_s"]) > max(2, 0.3 * b["ttk_s"]):
            d.append(f"ttk {b['ttk_s']}->{r['ttk_s']}")
        if abs(r["dmg_hp"] - b["dmg_hp"]) > max(2, 0.3 * b["dmg_hp"]):
            d.append(f"dmg {b['dmg_hp']}->{r['dmg_hp']}")
        if abs(r["emeralds"] - b["emeralds"]) > max(10, 0.5 * b["emeralds"]):
            d.append(f"emeralds {b['emeralds']:.0f}->{r['emeralds']:.0f}")
        if r["zone"] != b["zone"]:
            d.append(f"zone {b['zone']}->{r['zone']}")
        if d:
            changes.append(f"L{r['level']}: " + ", ".join(d))
    if changes:
        out.append("- per level: " + "; ".join(changes[:25]) + (f" (+{len(changes) - 25} more)" if len(changes) > 25 else ""))
    bf = {(f[0], f[1]) for f in base["flags"]}
    nf = {(f[0], f[1]) for f in now["flags"]}
    new = Counter(c for c, l in nf - bf)
    gone = Counter(c for c, l in bf - nf)
    if new:
        out.append("- new flags: " + ", ".join(f"{c} x{n}" for c, n in new.most_common()))
    if gone:
        out.append("- cleared flags: " + ", ".join(f"{c} x{n}" for c, n in gone.most_common()))
    if len(out) == 1 and not changes:
        out.append("- no per-level changes beyond the thresholds")
    return out


def run_sim(wow, rules, route, **knobs):
    sim = Sim(wow, rules, route)
    sim.knobs.update(knobs)
    sim.entered = True
    sim.zone_name = sim.route["zones"][0]["name"]
    sim.run()
    return sim


def main():
    fit = "--fit-taken" in sys.argv
    args = [a for a in sys.argv[1:] if a != "--fit-taken"]
    wow, rules, route, out_dir = args[0:4]
    baseline = args[4] if len(args) > 4 else None
    data = json.load(open(wow)), json.load(open(rules)), json.load(open(route))
    sim = run_sim(*data)
    if fit:
        factors, anchors = fit_taken(sim)
        print("taken-curve fit: factor per band " + ", ".join(f"{lo}: x{f:.2f}" for lo, f in factors.items()))
        print("McwowCombat.TAKEN_CURVE = {" + ", ".join(f"{a:.2f}F" for a in anchors) + "};")
        return
    without = run_sim(*data, alchemy=False)  # what alchemy is worth
    plain = run_sim(*data, chests=False, enchanting=False)  # what chests and enchanting are worth
    styles = {st: run_sim(*data, style=st) for st in ("wand", "rifle", "blunderbuss")} if data[1].get("ranged") else {}
    rep = write_report(sim, out_dir, baseline, without, plain, styles)
    print(f"sim: level {sim.level} in {rep['total_hours']} h, {rep['quests_done']} quests, "
          f"{len(rep['flags'])} flags -> {os.path.join(out_dir, 'report.md')}")


if __name__ == "__main__":
    main()
