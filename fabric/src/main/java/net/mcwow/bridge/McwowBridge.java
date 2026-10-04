package net.mcwow.bridge;

import net.fabricmc.api.ModInitializer;
import net.mcwow.bridge.combat.McwowCombat;

/**
 * Common entrypoint (2026-10-01): registrations that must exist on the integrated server too -
 * the WoW creature stand-in entity type (Phase 5 combat).
 */
public final class McwowBridge implements ModInitializer {
    @Override
    public void onInitialize() {
        McwowDanceNet.register(); // /dance, relayed to everyone who sees the dancer
        McwowMusic.register(); // WoW music discs
        McwowWaygates.register(); // the waygate portal network
        McwowOres.register(); // WoW ores, raw chunks and bars, leather and cloth
        McwowGear.register(); // levelled gear: item level by material
        McwowVendors.register(); // WoW vendors' Minecraft trade screens
        McwowNodes.register(); // WoW ore veins mined with a Minecraft pickaxe
        McwowTrees.register(); // WoW trees chopped for logs
        McwowAnvils.register(); // WoW anvils open Minecraft's anvil screen
        McwowWands.register(); // wands: spell bolts of WoW's schools
        McwowGuns.register(); // rifles, blunderbusses and their ammo
        McwowWowWeapons.register(); // weapons drawn with WoW item models (local art)
        McwowQuestRewards.register(); // quest rewards as Minecraft items and emeralds
        McwowCommands.register(); // /mcwow digging, /mcwow mine reset
        McwowCombat.init();
        net.mcwow.bridge.combat.McwowXp.init(); // kill XP as XP orbs (leveling)
        McwowGeomCache.init(); // persistent WoW geometry cells: memory budget + pinning
        McwowTerrainFill.init(); // real ground under the WoW surface (breakable terrain)
        McwowGroundReveal.init(); // ... placed where it is needed
        McwowSimExport.init(); // progression sim rules (tools/progression.sh only)
    }
}
