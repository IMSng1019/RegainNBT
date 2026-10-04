import com.mojang.datafixers.DataFixer;
import com.mojang.serialization.Dynamic;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.TagParser;
import net.minecraft.server.Bootstrap;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.util.datafix.fixes.References;

public class Spike10 {
    static final int V1_20_4 = 3700;
    static DataFixer FIXER; static int CUR;
    public static void main(String[] a) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        CUR = SharedConstants.WORLD_VERSION; FIXER = DataFixers.getDataFixer();
        System.out.println("=== CustomPotionEffects 的各种组合（物品层）===");
        item("potion + CustomPotionEffects 单独", "minecraft:potion", "{CustomPotionEffects:[{Id:1b,Amplifier:2b,Duration:200}]}");
        item("potion + Potion + CustomPotionEffects", "minecraft:potion", "{Potion:\"minecraft:water\",CustomPotionEffects:[{Id:1b,Amplifier:2b,Duration:200}]}");
        item("potion + Potion 单独", "minecraft:potion", "{Potion:\"minecraft:strong_strength\"}");
        item("tipped_arrow + CustomPotionEffects", "minecraft:tipped_arrow", "{CustomPotionEffects:[{Id:1b,Amplifier:2b,Duration:200}]}");
        System.out.println("\n=== 实体层的洞：把这些 NBT 喂给 ENTITY ===");
        ent("ActiveEffects", "minecraft:zombie", "{ActiveEffects:[{Id:1b,Amplifier:1b,Duration:200,ShowParticles:1b}]}");
        ent("CustomNameVisible", "minecraft:armor_stand", "{CustomNameVisible:1b}");
        ent("PersistenceRequired", "minecraft:zombie", "{PersistenceRequired:1b}");
        ent("Color(sheep)", "minecraft:sheep", "{Color:5b}");
        ent("NoAI/Silent/Health", "minecraft:zombie", "{NoAI:1b,Silent:1b,Health:20.0f}");
        System.out.println("\n=== 方块实体层的洞 ===");
        be("spawner SpawnData", "{id:\"minecraft:mob_spawner\",SpawnData:{entity:{id:\"minecraft:zombie\"}},SpawnCount:1s}");
        be("skull owner", "{id:\"minecraft:skull\",SkullOwner:{Id:[I;1,2,3,4],Name:\"Notch\"}}");
        be("banner patterns", "{id:\"minecraft:banner\",Base:15,Patterns:[{Pattern:\"cre\",Color:1}]}");
        be("beacon", "{id:\"minecraft:beacon\",Levels:2,Primary:1,Secondary:0}");
    }
    static void item(String label, String id, String inner) {
        try {
            CompoundTag out = (CompoundTag) FIXER.update(References.ITEM_STACK, new Dynamic<>(NbtOps.INSTANCE,
                TagParser.parseCompoundFully("{id:\"" + id + "\",Count:1b,tag:" + inner + "}")), V1_20_4, CUR).getValue();
            System.out.println("  " + pad(label) + " -> " + out);
        } catch (Throwable t) { System.out.println("  " + pad(label) + " EXC " + t); }
    }
    static void ent(String label, String id, String nbt) {
        try {
            CompoundTag in = TagParser.parseCompoundFully(nbt); in.putString("id", id);
            CompoundTag out = (CompoundTag) FIXER.update(References.ENTITY, new Dynamic<>(NbtOps.INSTANCE, in), V1_20_4, CUR).getValue();
            System.out.println("  " + pad(label) + " " + (out.equals(in) ? "[无变化]" : "[已转换]") + " -> " + out);
        } catch (Throwable t) { System.out.println("  " + pad(label) + " EXC " + t); }
    }
    static void be(String label, String nbt) {
        try {
            CompoundTag in = TagParser.parseCompoundFully(nbt);
            CompoundTag out = (CompoundTag) FIXER.update(References.BLOCK_ENTITY, new Dynamic<>(NbtOps.INSTANCE, in), V1_20_4, CUR).getValue();
            System.out.println("  " + pad(label) + " " + (out.equals(in) ? "[无变化]" : "[已转换]") + " -> " + out);
        } catch (Throwable t) { System.out.println("  " + pad(label) + " EXC " + t); }
    }
    static String pad(String s) { StringBuilder b = new StringBuilder(s); while (b.length() < 30) b.append(' '); return b.toString(); }
}
