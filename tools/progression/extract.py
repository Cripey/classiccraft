#!/usr/bin/env python3
"""WoW side of the progression sim (2026-10-04): reads the VMaNGOS world DB (MariaDB on 3307) and
the server's .map files into build/progression/wow.json - everything the 1-60 model needs from WoW:
the XP table and rates, and for every zone on the route its quests (objectives, XP, money, reward
items) and creatures (level, rank, type, health, damage, money, spawns).

Rules of thumb, not a quest database: the 1.12 row of each quest/creature (highest patch <= 10),
quests a human can take (no class, profession, reputation, PvP, dungeon or raid quests), creatures
an Alliance player can attack. Spawns are put in zones by the .map area grid (tools/wowdata.py).
Usage: tools/progression/extract.py <route.json> <out.json>"""
import json, os, re, sys

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
from wowdata import ROOT, area_flag, areas as load_areas, sql

PATCH = 10  # 1.12
HUMAN = 1
SKIP_TYPES = {41, 62, 81, 82, 85}  # PvP, raid, dungeon, world event, heroic
CONT = (0, 1)


def latest(table, cols, key="entry", where="1"):
    """Rows of a patch-versioned table, newest patch <= 1.12 per key."""
    rows = sql(f"select {key}, patch, {cols} from {table} t where patch <= {PATCH} and {where} "
               f"and patch = (select max(patch) from {table} u where u.{key} = t.{key} and u.patch <= {PATCH})")
    return {int(r[0]): r[2:] for r in rows}


def mangosd_rates():
    rates = {"kill": 1.0, "kill_elite": 1.0, "quest": 1.0}
    keys = {"Rate.XP.Kill": "kill", "Rate.XP.Kill.Elite": "kill_elite", "Rate.XP.Quest": "quest"}
    try:
        for line in open(os.path.join(ROOT, "build/vmangos-run/etc/mangosd.conf")):
            m = re.match(r"\s*(Rate\.XP\.[\w.]+)\s*=\s*([\d.]+)", line)
            if m and m.group(1) in keys:
                rates[keys[m.group(1)]] = float(m.group(2))
    except OSError:
        pass
    return rates


def main():
    route, out = sys.argv[1], sys.argv[2]
    zones = [z["zone"] for z in json.load(open(route))["zones"]]
    area_of = load_areas()  # (map, flag) -> (entry, zone, level, name)

    def zone_at(m, x, y):
        a = area_of.get((m, area_flag(m, x, y)))
        return None if not a else (a[1] or a[0])

    # Creatures -----------------------------------------------------------------------------
    cls = {(int(c), int(l)): (float(h), float(d), float(ap)) for c, l, h, d, ap in
           sql("select class, level, health, melee_damage, attack_power from creature_classlevelstats")}
    friendly = {int(i): (int(f), int(h)) for i, f, h in sql("select id, friendly_mask, hostile_mask from faction_template")}
    ct = latest("creature_template",
                "name, level_min, level_max, faction, npc_flags, type, pet_family, `rank`, unit_class, xp_multiplier, "
                "health_multiplier, damage_multiplier, base_attack_time, skinning_loot_id, gold_min, gold_max, civilian, loot_id, "
                "damage_school, spell_list_id")
    # Spell lists that cast a damaging spell of a magic school (effect 2 = SCHOOL_DAMAGE, school 0 = physical)
    nuke = {int(i) for (i,) in sql("select entry from spell_template where school > 0 and 2 in (effect1, effect2, effect3)")}
    casters = set()
    for row in sql("select entry, " + ", ".join(f"spellId_{k}" for k in range(1, 9)) + " from creature_spells"):
        if any(int(x) in nuke for x in row[1:]):
            casters.add(int(row[0]))
    spawns = {}  # entry -> {zone: count}
    for cid, m, x, y in sql(f"select id, map, position_x, position_y from creature where map in {CONT}"):
        z = zone_at(int(m), float(x), float(y))
        if z in zones:
            d = spawns.setdefault(int(cid), {})
            d[z] = d.get(z, 0) + 1
    creatures = {}
    for e, r in ct.items():
        (name, lmin, lmax, fac, npc, typ, fam, rank, ucls, xpm, hpm, dmgm, bat, skin, gmin, gmax, civ, loot, school, slist) = r
        lmin, lmax, ucls = int(lmin), int(lmax), max(1, int(ucls))
        lvl = (lmin + lmax) // 2
        stats = cls.get((ucls, lvl)) or cls.get((1, lvl)) or (0.0, 0.0, 0.0)
        fm, hm = friendly.get(int(fac), (0, 0))
        attackable = (fm & 3) == 0 and int(npc) == 0 and int(typ) != 8 and int(civ) == 0
        bat = int(bat) or 2000
        creatures[e] = {
            "name": name, "level": lvl, "level_min": lmin, "level_max": lmax, "rank": int(rank), "type": int(typ),
            "family": int(fam), "skinnable": int(skin) > 0, "gold": (int(gmin) + int(gmax)) / 2.0,
            "hp": round(stats[0] * float(hpm)), "attack_ms": bat,
            # average melee hit: weapon damage + attack power's share (AP/14 per second of swing)
            "hit": round((stats[1] + stats[2] / 14.0 * bat / 1000.0) * float(dmgm), 1),
            "xp_mult": float(xpm), "attackable": attackable,
            # share of its damage that is spell damage (rough): magic melee all, casters (paladin,
            # mage class) 0.6, others with a damaging spell 0.25
            "spell_share": 1.0 if int(school) else (0.6 if int(ucls) in (2, 8) else 0.25) if int(slist) in casters else 0.0, "hostile": bool(hm & 3),
            "spawns": spawns.get(e, {}),
        }

    # Quests ----------------------------------------------------------------------------------
    givers = {int(q) for (q,) in sql("select quest from creature_questrelation union select quest from gameobject_questrelation")}
    givers |= {int(q) for (q,) in sql("select distinct start_quest from item_template where start_quest > 0")}
    qcols = ["ZoneOrSort", "MinLevel", "QuestLevel", "Type", "RequiredClasses", "RequiredRaces", "RequiredSkill",
             "RepObjectiveFaction", "QuestFlags", "SpecialFlags", "PrevQuestId", "ExclusiveGroup", "SuggestedPlayers",
             "RewXP", "RewOrReqMoney", "Title"]
    qcols += [f"ReqItemId{i}" for i in range(1, 5)] + [f"ReqItemCount{i}" for i in range(1, 5)]
    qcols += [f"ReqCreatureOrGOId{i}" for i in range(1, 5)] + [f"ReqCreatureOrGOCount{i}" for i in range(1, 5)]
    qcols += [f"RewChoiceItemId{i}" for i in range(1, 7)] + [f"RewChoiceItemCount{i}" for i in range(1, 7)]
    qcols += [f"RewItemId{i}" for i in range(1, 5)] + [f"RewItemCount{i}" for i in range(1, 5)]
    # A quest's ZoneOrSort can be a sub-area (Northshire Valley): count it in its zone.
    zone_of_area = {int(e): int(z) or int(e) for e, z in sql("select entry, zone_id from area_template")}
    in_route = [a for a, z in zone_of_area.items() if z in zones]
    qt = latest("quest_template", ", ".join(qcols), where=f"ZoneOrSort in ({','.join(map(str, in_route))})")
    quests, want_items = {}, set()
    for q, r in qt.items():
        f = dict(zip(qcols, r))
        i = lambda k: int(f[k])
        if q not in givers or i("Type") in SKIP_TYPES or i("RequiredClasses") or i("RequiredSkill") or i("RepObjectiveFaction"):
            continue
        if i("RequiredRaces") and not i("RequiredRaces") & HUMAN:
            continue
        if i("SpecialFlags") & 1:  # repeatable
            continue
        reqs = [(i(f"ReqItemId{k}"), i(f"ReqItemCount{k}")) for k in range(1, 5) if i(f"ReqItemId{k}") > 0]
        kills = [(i(f"ReqCreatureOrGOId{k}"), i(f"ReqCreatureOrGOCount{k}")) for k in range(1, 5) if i(f"ReqCreatureOrGOId{k}")]
        choice = [(i(f"RewChoiceItemId{k}"), i(f"RewChoiceItemCount{k}")) for k in range(1, 7) if i(f"RewChoiceItemId{k}") > 0]
        rew = [(i(f"RewItemId{k}"), i(f"RewItemCount{k}")) for k in range(1, 5) if i(f"RewItemId{k}") > 0]
        want_items |= {it for it, _ in reqs + choice + rew}
        quests[q] = {
            "title": f["Title"], "zone": zone_of_area[i("ZoneOrSort")], "min": max(1, i("MinLevel")), "level": i("QuestLevel"),
            "type": i("Type"), "players": i("SuggestedPlayers"), "prev": i("PrevQuestId"), "excl": i("ExclusiveGroup"),
            "xp": i("RewXP"), "money": i("RewOrReqMoney"),
            "kills": [[c, n] for c, n in kills if c > 0], "objects": [[-c, n] for c, n in kills if c < 0],
            "items": [[it, n] for it, n in reqs], "choice": choice, "reward": rew,
        }

    # Items: reward items' fields (McwowDialog.WowItem) and quest items' sources
    stat_cols = ", ".join(f"stat_type{k}, stat_value{k}" for k in range(1, 11))
    it = latest("item_template", "name, class, subclass, inventory_type, quality, item_level, required_level, "
                f"{stat_cols}, holy_res, fire_res, nature_res, frost_res, shadow_res, arcane_res, dmg_type1",
                where=f"entry in ({','.join(map(str, want_items)) or 0})")
    items = {}
    for e, r in it.items():
        n, c, s_, iv, qq, il, rl = r[:7]
        st = [int(v) for v in r[7:27]]
        # stats as (type, value) pairs, empty slots dropped (McwowDialog.WowItem / McwowEnchants.fromStats)
        stats = [x for k in range(0, 20, 2) if st[k + 1] != 0 for x in (st[k], st[k + 1])]
        items[e] = {"name": n, "class": int(c), "subclass": int(s_), "inv": int(iv), "quality": int(qq), "ilvl": int(il),
                    "req": int(rl), "stats": stats, "resist": [int(v) for v in r[27:33]], "dmg_type": int(r[33])}
    req_items = {itm for q in quests.values() for itm, _ in q["items"]}
    if req_items:
        ids = ",".join(map(str, req_items))
        by_loot = {}
        for e, r in ct.items():
            if int(r[17]) > 0:
                by_loot.setdefault(int(r[17]), []).append(e)
        for lid, itm, ch in sql(f"select entry, item, ChanceOrQuestChance from creature_loot_template where item in ({ids})"):
            for e in by_loot.get(int(lid), []):
                # negative = quest drop (only those reach a bridged player's bags: ClassicCraft::OnKillLoot)
                items.setdefault(int(itm), {}).setdefault("drops", []).append([e, float(ch)])
        for gid, itm, ch in sql(f"select g.entry, l.item, l.ChanceOrQuestChance from gameobject_loot_template l "
                                f"join gameobject_template g on g.data1 = l.entry and g.type = 3 where l.item in ({ids})"):
            items.setdefault(int(itm), {}).setdefault("objects", []).append([int(gid), abs(float(ch))])
    xp = {int(l): int(x) for l, x in sql("select lvl, xp_for_next_level from player_xp_for_level")}
    used = {c for q in quests.values() for c, _ in q["kills"]}
    used |= {c for i in items.values() for c, _ in i.get("drops", [])}
    keep = {e: c for e, c in creatures.items() if c["spawns"] and (c["attackable"] or e in used)}
    # WoW ore veins per zone (spawn points; pools keep only some up at a time): McwowNodes mines them.
    vein_names = {int(e): n for e, n in sql("select entry, name from gameobject_template where type = 3 and "
                                            "(name like '%Vein%' or name like '%Deposit%') group by entry")}
    veins = {}
    if vein_names:
        for gid, m, x, y in sql(f"select id, map, position_x, position_y from gameobject where map in {CONT} "
                                f"and id in ({','.join(map(str, vein_names))})"):
            z = zone_at(int(m), float(x), float(y))
            if z in zones:
                d = veins.setdefault(z, {})
                d[vein_names[int(gid)]] = d.get(vein_names[int(gid)], 0) + 1
    # WoW herb nodes per zone (McwowNodes gathers the ones in resources mcwow/herbs.json).
    herb_names = set(json.load(open(os.path.join(ROOT, "fabric/src/main/resources/mcwow/herbs.json"))))
    herb_ids = {int(e): n for e, n in sql("select entry, name from gameobject_template where type = 3 group by entry")
                if n in herb_names}
    herbs = {}
    if herb_ids:
        for gid, m, x, y in sql(f"select id, map, position_x, position_y from gameobject where map in {CONT} "
                                f"and id in ({','.join(map(str, herb_ids))})"):
            z = zone_at(int(m), float(x), float(y))
            if z in zones:
                d = herbs.setdefault(z, {})
                d[herb_ids[int(gid)]] = d.get(herb_ids[int(gid)], 0) + 1
    # WoW treasure chests per zone (McwowChests; resources mcwow/chests.json): spawn points and how many are
    # up at a time (a pool keeps max_limit of its members up, spread over them).
    chest_ids = json.load(open(os.path.join(ROOT, "fabric/src/main/resources/mcwow/chests.json")))["entries"]
    chests = {}
    if chest_ids:
        pool_of = {}
        for guid, limit, members in sql("select pg.guid, pt.max_limit, (select count(*) from pool_gameobject p2 "
                                        "where p2.pool_entry = pt.entry) from pool_gameobject pg join pool_template pt "
                                        "on pt.entry = pg.pool_entry"):
            pool_of[int(guid)] = (int(limit) or int(members)) / max(1, int(members))
        for guid, gid, m, x, y in sql(f"select guid, id, map, position_x, position_y from gameobject where map in {CONT} "
                                      f"and patch_max >= 10 and id in ({','.join(chest_ids)})"):
            z = zone_at(int(m), float(x), float(y))
            if z in zones:
                d = chests.setdefault(z, {}).setdefault(gid, [0, 0.0])
                d[0] += 1
                d[1] += pool_of.get(int(guid), 1.0)
    vendors = {}  # zone -> vendor entries standing in it (their stock: resources mcwow/vendors.json)
    for e, r in ct.items():
        if int(r[4]) & 4:
            for z in spawns.get(e, {}):
                vendors.setdefault(z, []).append(e)
    json.dump({"rates": mangosd_rates(), "xp_for_level": xp, "creatures": keep, "quests": quests, "items": items,
               "vendors": vendors, "veins": veins, "herbs": herbs, "chests": chests,
               "zones": zones},
              open(out, "w"), indent=0, sort_keys=True)
    print(f"extract: {len(quests)} quests, {len(keep)} creatures, {len(items)} items -> {out}")


if __name__ == "__main__":
    main()
