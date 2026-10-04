"""Herbs and brewing in the progression model (2026-10-04, user: "uses what it can brew", brewing
time not counted). Minecraft's own brewing, as the mod's export found it (rules.json "brewing":
every potion's effects and the steps potion --herb--> potion tried with the real herb stacks).
The player brews from what it holds (herbs gathered on the way, loot) and buys what a vendor of the
zone sells (glass bottles, nether wart, blaze powder...); it keeps a few potions of each kind and
uses them: Healing in dangerous fights, Strength and Swiftness kept up while questing and grinding,
Regeneration to shorten the rests between fights."""
import math
from collections import Counter, defaultdict

# The effects the model knows how to use, and how many potions of each the player keeps.
USED = {"minecraft:instant_health": 6, "minecraft:strength": 3, "minecraft:speed": 3, "minecraft:regeneration": 3}
BOTTLE, WATER, FUEL_USES = "minecraft:glass_bottle", "minecraft:water", 20  # vanilla blaze powder: 20 brews
BOTTLE_STOCK = 48  # glass bottles a player carries
TICKS = 20.0


class Alchemy:
    def __init__(self, sim, on=True):
        self.sim, self.on = sim, on
        b = sim.r.get("brewing", {"potions": {}, "herbs": {}, "edges": []})
        self.effects = b["potions"]  # potion -> [[effect, amplifier, ticks]]
        self.herbs = b["herbs"]  # herb name -> {item, per_harvest, fuel}
        self.fuel_items = {h["item"] for h in self.herbs.values() if h["fuel"]}
        # steps by item: (from potion, ingredient item) -> potion
        self.steps = defaultdict(set)
        for frm, herb, to in b["edges"]:
            self.steps[frm].add((self.herbs[herb]["item"], to))
        self.stock = Counter()  # potion -> count
        self.fuel = 0.0  # brews left in the stand's fuel
        self.first = {}  # effect -> (level, potion) first brewed
        self.brewed, self.used = Counter(), Counter()
        self.buff_left = Counter()  # effect -> seconds of the current potion left
        self.buff_amp = {}
        self.no_bottles = set()
        self.vstock = {}  # (vendor entry, item) -> (lots sold, play time of the last restock step)
        self.sold_out = Counter()  # item -> times a limited vendor stock ran out
        self.stand = False  # a brewing stand: bought once (vendors), carried

    # ---- what a potion does ------------------------------------------------------------------------
    def effect_of(self, potion, effect):
        for e, amp, ticks in self.effects.get(potion, []):
            if e == effect:
                return amp, ticks / TICKS
        return None

    def best(self, effect):
        """The stocked potion with this effect: strongest, then longest."""
        have = [(self.effect_of(p, effect), p) for p, n in self.stock.items() if n >= 1]
        have = [(e, p) for e, p in have if e]
        return max(have, key=lambda t: (t[0][0], t[0][1]))[1] if have else None

    def _buff(self, effect, seconds):
        """Keep an effect up for `seconds` of play: drink potions as each runs out. Its amplifier, or None."""
        if not self.on:
            return None
        while self.buff_left[effect] < seconds:
            p = self.best(effect)
            if not p:
                break
            amp, dur = self.effect_of(p, effect)
            self.stock[p] -= 1
            self.used[p] += 1
            self.buff_left[effect] += dur
            self.buff_amp[effect] = amp
        if self.buff_left[effect] <= 0:
            return None
        self.buff_left[effect] = max(0.0, self.buff_left[effect] - seconds)
        return self.buff_amp.get(effect)

    def tick(self, seconds):
        """Questing or grinding time passes: Strength and Swiftness kept up."""
        self.speed_amp = self._buff("minecraft:speed", seconds)
        self.strength_amp = self._buff("minecraft:strength", seconds)

    speed_amp = strength_amp = None

    def speed(self):
        """Walking speed factor (Swiftness: +20% a level)."""
        return 1.0 + 0.2 * (self.speed_amp + 1) if self.speed_amp is not None else 1.0

    def strength(self):
        """Melee damage added (Strength: +3 a level)."""
        return 3.0 * (self.strength_amp + 1) if self.strength_amp is not None else 0.0

    def heal_fight(self, dmg, n):
        """Healing potions for n fights of `dmg` hp: drink when a fight goes past the safe mark.
        Returns the hp healed per fight."""
        safe = self.sim.knob("safe_dmg")
        if not self.on or dmg <= safe:
            return 0.0
        healed = 0.0
        need = n
        while need > 1e-9:
            p = self.best("minecraft:instant_health")
            if not p:
                break
            amp, _ = self.effect_of(p, "minecraft:instant_health")
            per = 4.0 * (2 ** amp)  # Instant Health: 4 hp, doubled a level
            drinks = min(self.stock[p], need * math.ceil((dmg - safe) / per))
            self.stock[p] -= drinks
            self.used[p] += drinks
            healed += drinks * per
            need -= drinks / max(1, math.ceil((dmg - safe) / per))
            if self.stock[p] < 1:
                continue
        return healed / n if n else 0.0

    def regen_rate(self, rest_s):
        """Extra hp per second while resting (Regeneration: 1 hp per 50 ticks, halved per level)."""
        amp = self._buff("minecraft:regeneration", rest_s)
        return 0.0 if amp is None else TICKS / (50 >> amp)

    # ---- brewing ---------------------------------------------------------------------------------------
    def routes(self, effect):
        """Brewing routes to a potion with this effect: (potion, [ingredient items in order])."""
        out, seen, frontier = [], {WATER}, [(WATER, [])]
        for _ in range(4):
            nxt = []
            for pot, path in frontier:
                for item, to in self.steps.get(pot, ()):
                    if to in seen:
                        continue
                    seen.add(to)
                    nxt.append((to, path + [item]))
                    if self.effect_of(to, effect):
                        out.append((to, path + [item]))
            frontier = nxt
        # strongest, then longest first
        return sorted(out, key=lambda r: tuple(-x for x in self.effect_of(r[0], effect)))

    def lots_left(self, entry, o):
        """Lots a vendor has of an offer now: limited supply (McwowVendors.LIMITED) restocks one lot
        every restock_s of play after a sale."""
        if "limit" not in o:
            return 10 ** 9
        sold, since = self.vstock.get((entry, o["result"]["item"]), (0, self.sim.t))
        steps = int((self.sim.t - since) // o["restock_s"])
        sold = max(0, sold - steps)
        self.vstock[(entry, o["result"]["item"])] = (sold, since + steps * o["restock_s"] if sold else self.sim.t)
        return o["limit"] - sold

    def buy(self, item, n):
        """Buy n of an item from the zone's vendors (emeralds), cheapest first, as far as their stock
        goes. True if all n were had."""
        offers = []
        for e in self.sim.w["vendors"].get(str(self.sim.zone), []):
            v = self.sim.r["vendors"].get(str(e))
            for o in (v or {}).get("offers", []):
                if o["result"]["item"] == item and o["cost"] == "minecraft:emerald" and not o["result"].get("gear"):
                    offers.append((o["cost_count"] / o["result"]["count"], e, o))
        got = 0
        for per, e, o in sorted(offers, key=lambda t: t[0]):
            while got < n - 1e-9 and self.lots_left(e, o) > 0 and self.sim.inv["minecraft:emerald"] >= o["cost_count"]:
                self.sim.take("minecraft:emerald", o["cost_count"], "alchemy")
                self.sim.gain(item, o["result"]["count"], "bought")
                got += o["result"]["count"]
                if "limit" in o:
                    sold, since = self.vstock[(e, item)]
                    self.vstock[(e, item)] = (sold + 1, since)
            if got >= n - 1e-9:
                return True
        if offers and got < n - 1e-9 and any("limit" in o for _, _, o in offers):
            self.sold_out[item] += 1
        return got >= n - 1e-9

    def have(self, item, n):
        return self.sim.inv[item] >= n - 1e-9 or self.buy(item, n - self.sim.inv[item])

    def brew_up(self):
        """Top up each kept potion from what the bag holds and the zone sells; carry bottles from a
        zone that sells them (BOTTLE_STOCK) for the zones that don't."""
        if not self.on:
            return
        if self.sim.inv[BOTTLE] < BOTTLE_STOCK:
            self.buy(BOTTLE, BOTTLE_STOCK - self.sim.inv[BOTTLE])
        for effect, keep in USED.items():
            for _ in range(4):  # batches per visit
                if sum(n for p, n in self.stock.items() if self.effect_of(p, effect)) >= keep:
                    break
                if not self.brew_one(effect):
                    break

    def brew_one(self, effect):
        if not self.stand:
            if not self.buy("minecraft:brewing_stand", 1):
                return False
            self.sim.take("minecraft:brewing_stand", 1, "placed")
            self.stand = True
        for potion, items in self.routes(effect):
            need = Counter(items)
            if not all(self.sim.inv[i] >= n - 1e-9 for i, n in need.items()):
                # one missing ingredient may be bought
                if not all(self.have(i, n) for i, n in need.items()):
                    continue
            if not self.have(BOTTLE, 3):
                self.no_bottles.add(self.sim.zone_name)
                return False
            if self.fuel < len(items):
                fuel = next((f for f in sorted(self.fuel_items) if self.sim.inv[f] >= 1), None)
                if not fuel and not any(self.have(f, 1) for f in sorted(self.fuel_items)):
                    return False
                fuel = fuel or next(f for f in sorted(self.fuel_items) if self.sim.inv[f] >= 1)
                self.sim.take(fuel, 1, "fuel")
                self.fuel += FUEL_USES
            for i, n in need.items():
                self.sim.take(i, n, "brewed")
            self.sim.take(BOTTLE, 3, "brewed")
            self.fuel -= len(items)
            self.stock[potion] += 3
            self.brewed[potion] += 3
            self.first.setdefault(effect, (self.sim.level, potion))
            return True
        return False

    # ---- herbs on the way --------------------------------------------------------------------------
    def gather(self, seconds):
        """Herb nodes passed while questing are gathered: a mix of the zone's herbs by spawn count."""
        found = [(n, c) for n, c in self.sim.w.get("herbs", {}).get(str(self.sim.zone), {}).items() if n in self.herbs]
        total = sum(c for _, c in found)
        if not total:
            return
        count = self.sim.knob("herbs_per_hour") * seconds / 3600
        secs = count * self.sim.knob("herb_gather_s")
        self.sim.t += secs
        r = self.sim.row()
        r["s"] += secs
        for name, c in found:
            h = self.herbs[name]
            n = count * c / total * h["per_harvest"]
            self.sim.gain(h["item"], n, "herbs")
            self.sim.herb_names[name] += n
