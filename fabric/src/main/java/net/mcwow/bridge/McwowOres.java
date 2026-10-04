package net.mcwow.bridge;

import net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockBehaviour;

/**
 * WoW's ores (2026-10-03): tin, silver, mithril, truesilver, thorium and dark iron as Minecraft ore
 * blocks (stone and deepslate), their raw chunks and bars, plus WoW's alloys bronze (copper + tin)
 * and steel (iron + coal). Copper, iron and gold stay vanilla's. They lie under WoW's ground by the
 * zone's level (McwowZoneOres). Also WoW's leather and cloth tiers (McwowLoot). Models, loot,
 * recipes and tags are written by tools/gen_mod_data.py, which must list the same ids.
 */
public final class McwowOres {
    public static final String[] ORES = {"tin", "silver", "mithril", "truesilver", "thorium", "dark_iron"};
    public static final String[] ALLOYS = {"bronze", "steel"};
    /** WoW's leather and cloth, by level (McwowLoot drops them). */
    public static final String[] LEATHERS = {"light_leather", "medium_leather", "heavy_leather", "thick_leather", "rugged_leather"};
    public static final String[] CLOTHS = {"linen_cloth", "wool_cloth", "silk_cloth", "mageweave_cloth", "runecloth"};

    private McwowOres() {
    }

    private static Block block(String name, Block like) {
        ResourceKey<Block> key = ResourceKey.create(Registries.BLOCK, Identifier.fromNamespaceAndPath("mcwow", name));
        Block b = Registry.register(BuiltInRegistries.BLOCK, key,
                new Block(BlockBehaviour.Properties.ofFullCopy(like).setId(key)));
        ResourceKey<Item> itemKey = ResourceKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath("mcwow", name));
        Item item = Registry.register(BuiltInRegistries.ITEM, itemKey,
                new BlockItem(b, new Item.Properties().setId(itemKey).useBlockDescriptionPrefix()));
        tab("natural_blocks", item);
        return b;
    }

    private static Item item(String name) {
        ResourceKey<Item> key = ResourceKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath("mcwow", name));
        Item item = Registry.register(BuiltInRegistries.ITEM, key, new Item(new Item.Properties().setId(key)));
        tab("ingredients", item);
        return item;
    }

    private static void tab(String tab, Item item) {
        CreativeModeTabEvents.modifyOutputEvent(ResourceKey.create(Registries.CREATIVE_MODE_TAB,
                Identifier.withDefaultNamespace(tab))).register(out -> out.accept(item));
    }

    public static void register() {
        for (String ore : ORES) {
            block(ore + "_ore", Blocks.IRON_ORE);
            block("deepslate_" + ore + "_ore", Blocks.DEEPSLATE_IRON_ORE);
            item("raw_" + ore);
            item(ore + "_bar");
        }
        for (String alloy : ALLOYS) item(alloy + "_bar");
        for (String m : LEATHERS) item(m);
        for (String m : CLOTHS) item(m);
    }
}
