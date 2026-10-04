"""Treasure chests and enchantments in the progression model (2026-10-04, user: loot more interesting,
chests Minecraftified, enchantments on drops). Everything about what a chest holds and what
Minecraft's enchanting rolls comes from the mod's export (rules.json "chests", "enchanting"); this
file only decides how a player meets chests and uses books - the KNOBS in sim.py.

- Chests on the way: while questing/grinding the player passes the zone's chests (wow.json
  "chests": entry -> [spawn points, up at a time]) at chests_per_hour, scaled by how many are up
  against chest_ref_up; each costs chest_s plus its opening (a right-click; locked ones pried with a
  pickaxe that can: rules "pry"). Loot goes to the inventory; books to the shelf; gear pieces build up
  credit per option and drop as one piece when it reaches 1 (expected-value walk).
- Books: each enchantment a book can roll goes to the shelf as an expected fraction; a whole book
  of a USED enchantment is applied (anvil; free here - the anvil can't lower the WoW level) to the
  piece that gains most. Books of other enchantments are counted, not used.
- Effects (sim.py asks): Sharpness +0.5 per level +0.5 attack; Protection 4% less damage per level
  over all pieces (cap 20 levels = 80%); Unbreaking wear x 1/(L+1) on weapons, x (0.6 + 0.4/(L+1))
  on armor; Smite / Bane +2.5 per level on WoW undead / arthropods (McwowCreatureKinds). A weapon
  holds one of Sharpness, Smite, Bane: a book replaces it when it is worth more against the zone's
  creatures (by spawn share). Not modelled: Fire Aspect, Looting, Sweeping, Thorns, the other
  Protections, Feather Falling, Efficiency, Fortune.
- Creature gear (McwowLoot.gearChance, rules loot "#gear"): credit per slot like chest gear."""
from collections import Counter, defaultdict

USED = ("sharpness", "smite", "bane_of_arthropods", "protection", "unbreaking")
WEAPON_ENCH = ("sharpness", "smite", "bane_of_arthropods", "unbreaking")
ARMOR_ENCH = ("protection", "unbreaking")
DAMAGE = ("sharpness", "smite", "bane_of_arthropods")  # one of them per weapon (Minecraft: exclusive)
BOOK = "minecraft:enchanted_book#enchanted"


def sharp_bonus(g, on=True):
    lv = (g or {}).get("ench", {}).get("sharpness", 0) if on else 0
    return 0.5 * lv + 0.5 if lv > 0 else 0.0


def kind_bonus(g, loot, on=True):
    """Smite on WoW undead, Bane on arthropods (McwowCreatureKinds): 2.5 per level."""
    if not on or not g:
        return 0.0
    e = g.get("ench", {})
    return 2.5 * (e.get("smite", 0) * loot.get("#undead", 0) + e.get("bane_of_arthropods", 0) * loot.get("#arthropod", 0))


def protection(pieces, on=True):
    return min(20.0, sum(g.get("ench", {}).get("protection", 0) for g in pieces)) if on else 0.0


def wear_factor(g, armor, on=True):
    lv = (g or {}).get("ench", {}).get("unbreaking", 0) if on else 0
    if lv <= 0:
        return 1.0
    return 0.6 + 0.4 / (lv + 1) if armor else 1.0 / (lv + 1)


class Treasure:
    def __init__(self, sim, chests, enchanting):
        self.s, self.chests_on, self.ench_on = sim, chests, enchanting
        self.e = sim.r.get("enchanting", {})
        self.pending = Counter()       # enchantment -> expected books not yet whole
        self.pending_lv = Counter()    # ... their summed levels
        self.shelf = defaultdict(list)  # enchantment -> levels of whole books held
        self.books = Counter()          # where books came from
        self.applied = []               # (level, enchantment, book level, slot)
        self.unused = Counter()         # whole books of enchantments the model doesn't use
        self.opened = Counter()         # zone -> chests opened
        self.locked_skipped = Counter() # zone -> locked chests passed (no pickaxe to pry them)
        self.credit = Counter()         # slot -> gear credit
        self.gear_got = []              # (level, item, equipped)
        self.first = {}                 # enchantment -> (player level, book level) first applied
        self._kinds = {}
        self.visited = set()            # levels an enchanting supplier was visited at
        self.vendor_log = []            # (level, {enchantment: books bought})

    # ---- books ---------------------------------------------------------------------------------
    def add_books(self, n, power, how):
        """n books (fractional) rolled at an enchanting power: their enchantments by the export's odds."""
        if n <= 0 or not self.e:
            return
        self.books[how] += n
        dist = self.e["book"][max(1, min(30, power)) - 1]
        for ench, (p, lv) in dist.items():
            self.pending[ench] += n * p
            self.pending_lv[ench] += n * p * lv
        self._whole()

    def add_exact(self, ench, n, how):
        """A quest reward's book with its rolled enchantments."""
        self.books[how] += n
        for k, lv in ench.items():
            self.pending[k] += n
            self.pending_lv[k] += n * lv
        self._whole()

    def _whole(self):
        for k in list(self.pending):
            while self.pending[k] >= 1:
                lv = self.pending_lv[k] / self.pending[k]
                self.pending[k] -= 1
                self.pending_lv[k] -= lv
                if k in USED:
                    self.shelf[k].append(lv)
                    self.shelf[k].sort()
                else:
                    self.unused[k] += 1
        self.apply()

    def apply(self):
        """Put the best shelf books on the pieces that gain from them."""
        if not self.ench_on:
            return
        s = self.s
        for k in USED:
            while self.shelf[k]:
                best = self.shelf[k][-1]
                slots = [sl for sl, g in s.gear.items() if g and g.get("gear")
                         and k in (WEAPON_ENCH if sl == "weapon" else ARMOR_ENCH)]
                if k in DAMAGE:
                    # one damage enchantment per weapon: only if it beats what the weapon has, here
                    slots = [sl for sl in slots if self.damage_value(k, best) > self.weapon_damage_value(s.gear[sl]) + 1e-6]
                else:
                    slots = [sl for sl in slots if s.gear[sl].get("ench", {}).get(k, 0) < best - 1e-6]
                if not slots:
                    break
                sl = min(slots, key=lambda x: (s.gear[x].get("ench", {}).get(k, 0), x != "weapon"))
                g = dict(s.gear[sl])
                ench = {kk: v for kk, v in g.get("ench", {}).items() if not (k in DAMAGE and kk in DAMAGE)}
                g["ench"] = dict(ench, **{k: best})
                s.gear[sl] = g
                self.shelf[k].pop()
                self.applied.append((s.level, k, round(best, 2), sl))
                self.first.setdefault(k, (s.level, round(best, 2)))

    def zone_kinds(self):
        """Spawn shares of undead and arthropods among the zone's attackable creatures."""
        z = self.s.zone
        if self._kinds.get("zone") != z:
            tot = und = arth = 0.0
            for e, c in self.s.c.items():
                n = c["spawns"].get(str(z), 0) if isinstance(c.get("spawns"), dict) else 0
                if not n or not c.get("attackable"):
                    continue
                loot = self.s.r["loot"].get(e, {})
                tot += n
                und += n * loot.get("#undead", 0)
                arth += n * loot.get("#arthropod", 0)
            self._kinds = {"zone": z, "undead": und / tot if tot else 0.0, "arthropod": arth / tot if tot else 0.0}
        return self._kinds

    def damage_value(self, k, lv):
        if k == "sharpness":
            return 0.5 * lv + 0.5
        f = self.zone_kinds()
        return 2.5 * lv * (f["undead"] if k == "smite" else f["arthropod"])

    def weapon_damage_value(self, g):
        e = (g or {}).get("ench", {})
        return max([self.damage_value(k, e[k]) for k in DAMAGE if e.get(k)] or [0.0])

    def gear_credit(self, n, level, how):
        """Expected gear pieces off creatures (uncommon, McwowLoot.gearChance): credit per slot."""
        opts = self.s.r.get("enchanting", {}).get("gear_options", {}).get(str(min(63, max(1, level))))
        if not opts or n <= 0:
            return
        for slot in set(filter(None, (self.s.slot_of(g) for g in opts))):
            mine = [g for g in opts if self.s.slot_of(g) == slot]
            key = ("creature", slot)
            self.credit[key] += n * sum(g["weight"] for g in mine)
            if self.credit[key] >= 1:
                self.credit[key] -= 1
                self.gear_piece(max(mine, key=lambda g: g["weight"]), level, how)

    # ---- enchanting suppliers (McwowVendors.books) ------------------------------------------------
    def vendor_visit(self):
        """A trip to a capital's enchanting supplier at a level-up, at least enchant_visit_levels after
        the last, when at least 2 books that improve what's worn are affordable (keeping
        emerald_reserve emeralds for food and repairs)."""
        s = self.s
        if not self.ench_on or not self.e.get("vendor_books"):
            return
        if s.level < s.knob("enchant_first_visit"):
            return
        if self.visited and s.level - max(self.visited) < s.knob("enchant_visit_levels"):
            return
        offers = {k: (lv, price, lots) for k, lv, price, lots in self.e["vendor_books"][str(min(60, s.level))]}
        wish = []  # enchantments to buy, in order
        w = s.gear.get("weapon")
        if w and w.get("gear"):
            best = max((k for k in DAMAGE if k in offers), key=lambda k: self.damage_value(k, offers[k][0]), default=None)
            if best and self.damage_value(best, offers[best][0]) > self.weapon_damage_value(w) + 1e-6:
                wish.append(best)
        for k in ("protection", "unbreaking"):
            if k in offers:
                for sl, g in s.gear.items():
                    if g and g.get("gear") and not (sl == "weapon" and k == "protection") \
                            and g.get("ench", {}).get(k, 0) < offers[k][0] - 1e-6:
                        wish.append(k)
        budget = s.inv["minecraft:emerald"] - s.knob("emerald_reserve")
        plan, n = [], Counter()
        for k in wish:
            lv, price, lots = offers[k]
            if n[k] < lots and price <= budget:
                plan.append(k)
                n[k] += 1
                budget -= price
        if len(plan) < 2:
            return
        self.visited.add(s.level)
        secs = s.knob("enchant_visit_s")
        s.t += secs
        r = s.row()
        r["s"] += secs
        r["enchant_s"] = r.get("enchant_s", 0.0) + secs
        for k in plan:
            lv, price, _ = offers[k]
            s.take("minecraft:emerald", price, "enchanting")
            s.gain(BOOK, 1, "bought")
            self.books["vendor"] += 1
            self.shelf[k].append(lv)
            self.shelf[k].sort()
        self.apply()
        self.vendor_log.append((s.level, dict(n)))

    # ---- chests --------------------------------------------------------------------------------
    def on_the_way(self, seconds):
        if not self.chests_on:
            return
        s = self.s
        zone = s.w.get("chests", {}).get(str(s.zone), {})
        rules = s.r.get("chests", {})
        up = sum(v[1] for e, v in zone.items() if e in rules)
        if up <= 0:
            return
        rate = s.knob("chests_per_hour") * min(2.0, up / s.knob("chest_ref_up"))
        count = rate * seconds / 3600
        for entry, (spawns, n_up) in zone.items():
            c = rules.get(entry)
            if not c:
                continue
            n = count * n_up / up
            open_s = 0.5  # unlocked: a right-click (McwowInteract)
            if "pry" in c:
                pry = c["pry"].get(s.pickaxe)
                if pry is None:
                    self.locked_skipped[s.zone_name] += n
                    continue
                open_s = pry
            secs = n * (s.knob("chest_s") + open_s)
            s.t += secs
            r = s.row()
            r["s"] += secs
            r["chest_s"] = r.get("chest_s", 0.0) + secs
            self.opened[s.zone_name] += n
            for item, avg in c["loot"].items():
                s.gain(item, avg * n, "chest")
            size_i = {"small": 0, "medium": 1, "large": 2}[c["size"]]
            power = self.e["chest_book_power"][c["size"]][min(63, c["level"]) - 1] if self.e else 1
            s.gain(BOOK, n * c["book_chance"], "chest")
            self.add_books(n * c["book_chance"], power, "chest")
            # Gear builds up credit per slot; a whole piece is the slot's likeliest option at this chest.
            for slot in set(filter(None, (s.slot_of(g) for g in c["gear"]))):
                opts = [g for g in c["gear"] if s.slot_of(g) == slot]
                self.credit[slot] += n * c["gear_chance"] * sum(g["weight"] for g in opts)
                if self.credit[slot] >= 1:
                    self.credit[slot] -= 1
                    self.gear_piece(max(opts, key=lambda g: g["weight"]), c["level"], "chest")

    def gear_piece(self, g, level, how):
        s = self.s
        slot = s.slot_of(g)
        if not slot:
            return
        g = dict(g)
        if self.ench_on and self.e:
            powers = self.e["chest_gear_power" if how == "chest" else "creature_gear_power"]
            power = powers[min(63, level) - 1]
            dist = self.e["weapon" if slot == "weapon" else "armor"][power - 1]
            g["ench"] = {k: p * lv for k, (p, lv) in dist.items() if k in USED}
        g["name"] = f"{s.describe(dict(g, name=None))} ({how} drop)"
        better = s.better(slot, g)
        if better:
            s.equip(slot, g, how)
            self.apply()
        self.gear_got.append((s.level, g["name"], better, how))

    def report(self):
        L = ["\n## Chests and enchantments"]
        if self.opened:
            L.append("- chests opened: " + ", ".join(f"{z} {n:.0f}" for z, n in self.opened.items() if n >= 0.5)
                     + f" (total {sum(self.opened.values()):.0f})")
        if self.locked_skipped:
            L.append("- locked chests passed without a pickaxe to pry them: " + ", ".join(
                f"{z} {n:.0f}" for z, n in self.locked_skipped.items() if n >= 0.5))
        if self.books:
            L.append("- books: " + ", ".join(f"{k} {v:.0f}" for k, v in self.books.items())
                     + "; whole books of unused enchantments: " + ", ".join(
                         f"{k} {v}" for k, v in self.unused.most_common(8)))
        if self.vendor_log:
            spent = sum(1 for _ in self.vendor_log)
            L.append(f"- enchanting supplier visits: {spent}; books bought: " + ", ".join(
                f"L{l} " + "+".join(f"{k} x{n}" for k, n in b.items()) for l, b in self.vendor_log if b))
        for k in USED:
            f = self.first.get(k)
            n = sum(1 for a in self.applied if a[1] == k)
            L.append(f"- {k}: " + (f"first applied at L{f[0]} (level {f[1]}), {n} books applied" if f else "never applied")
                     + (f", {len(self.shelf[k])} left on the shelf" if self.shelf[k] else ""))
        for how in ("chest", "creature"):
            got = [g for g in self.gear_got if g[3] == how]
            eq = [g for g in got if g[2]]
            L.append(f"- {how} gear: {len(got)} pieces found, {len(eq)} worn"
                     + (": " + "; ".join(f"L{g[0]} {g[1]}" for g in eq[:10]) if eq else ""))
        return L
