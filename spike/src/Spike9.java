import com.mojang.datafixers.DataFixer;
import com.mojang.serialization.Dynamic;

import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.TagParser;
import net.minecraft.server.Bootstrap;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.util.datafix.fixes.References;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/** Spike9: coverage sweep - does vanilla's own fixer really cover ALL common 1.20.4 NBT? */
public class Spike9 {
    static final int V1_20_4 = 3700;
    static DataFixer FIXER;
    static int CUR;
    static int itemOk, itemGap, entOk, entGap, beOk, beGap;
    static List<String> gaps = new ArrayList<>();

    public static void main(String[] a) throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        CUR = SharedConstants.WORLD_VERSION;
        FIXER = DataFixers.getDataFixer();
        System.out.println("target dataVersion = " + CUR + "  (from 1.20.4 = " + V1_20_4 + ")\n");

        System.out.println("================ 物品 NBT (ITEM_STACK) ================");
        item("ench",                      "minecraft:diamond_sword", "{Enchantments:[{id:\"minecraft:sharpness\",lvl:5}]}");
        item("display(Name+Lore+color)",  "minecraft:leather_chestplate", "{display:{Name:'{\"text\":\"N\"}',Lore:['{\"text\":\"L\"}'],color:16711680}}");
        item("display(color only)",       "minecraft:leather_boots", "{display:{color:255}}");
        item("Unbreakable",               "minecraft:diamond_pickaxe", "{Unbreakable:1b}");
        item("CustomModelData",           "minecraft:stick", "{CustomModelData:7}");
        item("AttributeModifiers",        "minecraft:diamond_boots", "{AttributeModifiers:[{AttributeName:\"minecraft:generic.movement_speed\",Name:\"spd\",Amount:0.1d,Operation:2,UUID:[I;1,2,3,4],Slot:\"feet\"}]}");
        item("CanDestroy/CanPlaceOn",     "minecraft:diamond_pickaxe", "{CanDestroy:[\"minecraft:stone\"],CanPlaceOn:[\"minecraft:dirt\"]}");
        item("HideFlags",                 "minecraft:netherite_sword", "{HideFlags:1,Enchantments:[{id:\"minecraft:sharpness\",lvl:1}]}");
        item("RepairCost",                "minecraft:shield", "{RepairCost:3}");
        item("Damage",                    "minecraft:iron_axe", "{Damage:5}");
        item("Potion",                    "minecraft:potion", "{Potion:\"minecraft:strong_strength\"}");
        item("CustomPotionEffects",       "minecraft:potion", "{CustomPotionEffects:[{Id:1b,Amplifier:2b,Duration:200,Ambient:0b,ShowParticles:1b}]}");
        item("StoredEnchantments(book)",  "minecraft:enchanted_book", "{StoredEnchantments:[{id:\"minecraft:mending\",lvl:1}]}");
        item("SkullOwner(string)",        "minecraft:player_head", "{SkullOwner:\"Notch\"}");
        item("SkullOwner(compound)",      "minecraft:player_head", "{SkullOwner:{Id:[I;1,2,3,4],Name:\"Notch\",Properties:{textures:[{Value:\"abc\"}]}}}");
        item("BlockEntityTag(container)", "minecraft:shulker_box", "{BlockEntityTag:{Items:[{Slot:0b,id:\"minecraft:diamond\",Count:3b,tag:{display:{Name:'\"Gem\"'}}}]}}");
        item("EntityTag(spawn egg)",      "minecraft:pig_spawn_egg", "{EntityTag:{id:\"minecraft:pig\",CustomName:'{\"text\":\"Piggy\"}'}}");
        item("Fireworks",                 "minecraft:firework_rocket", "{Fireworks:{Flight:2,Explosions:[{Type:1,Colors:[I;16711680],FadeColors:[I;255],Trail:1b,Flicker:1b}]}}");
        item("Explosion(rocket)",         "minecraft:firework_rocket", "{Explosion:{Type:0,Colors:[I;255],Flicker:0b,Trail:0b}}");
        item("ChargedProjectiles",        "minecraft:crossbow", "{ChargedProjectiles:[{id:\"minecraft:arrow\",Count:1b}]}");
        item("Items(bundle)",             "minecraft:bundle", "{Items:[{id:\"minecraft:apple\",Count:1b}]}");
        item("written book",              "minecraft:written_book", "{title:\"T\",author:\"A\",pages:['{\"text\":\"page1\"}'],resolved:1b}");
        item("Recipes(knowledge book)",   "minecraft:knowledge_book", "{Recipes:[\"minecraft:stone\"]}");
        item("Trim(armor trim)",          "minecraft:diamond_chestplate", "{Trim:{material:\"minecraft:gold\",pattern:\"minecraft:vex\"}}");
        item("BlockStateTag",             "minecraft:oak_sign", "{BlockStateTag:{rotation:\"8\"}}");
        item("map id",                    "minecraft:filled_map", "{map:1}");
        item("HideFlags+Unbreakable mix", "minecraft:elytra", "{HideFlags:2,Unbreakable:1b,Damage:1}");

        System.out.println("\n================ 实体 NBT (ENTITY) ================");
        entity("CustomName(json)",   "minecraft:zombie", "{CustomName:'{\"text\":\"Bob\"}'}");
        entity("Hand/ArmorItems",    "minecraft:zombie", "{HandItems:[{id:\"minecraft:diamond_sword\",Count:1b,tag:{Enchantments:[{id:\"minecraft:sharpness\",lvl:2}]}},{}],ArmorItems:[{id:\"minecraft:diamond_boots\",Count:1b,tag:{Unbreakable:1b}},{},{},{}]}");
        entity("Attributes",         "minecraft:pig", "{Attributes:[{Name:\"minecraft:generic.max_health\",Base:20.0d}]}");
        entity("ActiveEffects",      "minecraft:zombie", "{ActiveEffects:[{Id:1b,Amplifier:1b,Duration:200,ShowParticles:1b}]}");
        entity("Saddle/ArmorItem",   "minecraft:horse", "{SaddleItem:{id:\"minecraft:saddle\",Count:1b},ArmorItem:{id:\"minecraft:diamond_horse_armor\",Count:1b}}");
        entity("Color(sheep)",       "minecraft:sheep", "{Color:5b}");
        entity("plain flags",        "minecraft:zombie", "{NoAI:1b,Silent:1b,PersistenceRequired:1b,Health:20.0f,Tags:[\"a\"]}");
        entity("CustomNameVisible",  "minecraft:armor_stand", "{CustomNameVisible:1b,ShowArms:1b}");

        System.out.println("\n================ 方块实体 NBT (BLOCK_ENTITY) ================");
        be("chest Items",     "{id:\"minecraft:chest\",Items:[{Slot:0b,id:\"minecraft:diamond\",Count:3b,tag:{display:{Name:'{\"text\":\"Gem\"}'}}}]}");
        be("sign front_text", "{id:\"minecraft:sign\",front_text:{messages:['{\"text\":\"hello\"}','{\"text\":\"\"}','{\"text\":\"\"}','{\"text\":\"\"}'],color:\"black\"}}");
        be("spawner",         "{id:\"minecraft:mob_spawner\",SpawnCount:1s,SpawnRange:4s,Delay:20s,MinSpawnDelay:200s,MaxSpawnDelay:800s,SpawnData:{entity:{id:\"minecraft:zombie\"}}}");
        be("jukebox",         "{id:\"minecraft:jukebox\",RecordItem:{id:\"minecraft:music_disc_cat\",Count:1b},IsPlaying:1b}");
        be("furnace",         "{id:\"minecraft:furnace\",BurnTime:100s,CookTime:50s,CookTimeTotal:200s,Items:[{Slot:1b,id:\"minecraft:iron_ore\",Count:1b}]}");

        System.out.println("\n================ 汇总 ================");
        System.out.println("  物品      : 转换 " + itemOk + " / 无变化 " + itemGap);
        System.out.println("  实体      : 转换 " + entOk + " / 无变化 " + entGap);
        System.out.println("  方块实体  : 转换 " + beOk + " / 无变化 " + beGap);
        System.out.println("\n  --- 无变化的用例（需要人工确认是否真的没覆盖）---");
        for (String g : gaps) System.out.println("    " + g);
    }

    static void item(String label, String id, String inner) {
        String full = "{id:\"" + id + "\",Count:1b,tag:" + inner + "}";
        try {
            CompoundTag out = (CompoundTag) FIXER.update(References.ITEM_STACK,
                new Dynamic<>(NbtOps.INSTANCE, TagParser.parseCompoundFully(full)), V1_20_4, CUR).getValue();
            var comps = out.getCompound("components");
            if (comps.isPresent() && !comps.get().isEmpty()) {
                TreeSet<String> keys = new TreeSet<>(comps.get().keySet());
                System.out.println("  [OK] " + pad(label) + " -> " + keys);
                itemOk++;
            } else {
                System.out.println("  [--] " + pad(label) + " -> 无组件产出；输出=" + out);
                itemGap++; gaps.add("ITEM " + label);
            }
        } catch (Throwable t) { System.out.println("  [XX] " + pad(label) + " EXC " + t); itemGap++; gaps.add("ITEM " + label + " EXC"); }
    }

    static void entity(String label, String id, String nbt) {
        try {
            CompoundTag in = TagParser.parseCompoundFully(nbt);
            in.putString("id", id);
            CompoundTag out = (CompoundTag) FIXER.update(References.ENTITY,
                new Dynamic<>(NbtOps.INSTANCE, in), V1_20_4, CUR).getValue();
            boolean changed = !out.equals(in);
            System.out.println("  [" + (changed ? "OK" : "--") + "] " + pad(label) + " -> " + out);
            if (changed) entOk++; else { entGap++; gaps.add("ENTITY " + label); }
        } catch (Throwable t) { System.out.println("  [XX] " + pad(label) + " EXC " + t); entGap++; gaps.add("ENTITY " + label + " EXC"); }
    }

    static void be(String label, String nbt) {
        try {
            CompoundTag in = TagParser.parseCompoundFully(nbt);
            CompoundTag out = (CompoundTag) FIXER.update(References.BLOCK_ENTITY,
                new Dynamic<>(NbtOps.INSTANCE, in), V1_20_4, CUR).getValue();
            boolean changed = !out.equals(in);
            System.out.println("  [" + (changed ? "OK" : "--") + "] " + pad(label) + " -> " + out);
            if (changed) beOk++; else { beGap++; gaps.add("BLOCK_ENTITY " + label); }
        } catch (Throwable t) { System.out.println("  [XX] " + pad(label) + " EXC " + t); beGap++; gaps.add("BLOCK_ENTITY " + label + " EXC"); }
    }

    static String pad(String s) {
        StringBuilder b = new StringBuilder(s);
        while (b.length() < 26) b.append(' ');
        return b.toString();
    }
}
