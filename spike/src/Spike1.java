import com.mojang.datafixers.DataFixer;
import com.mojang.serialization.Dynamic;
import net.minecraft.SharedConstants;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.TagParser;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.Bootstrap;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.util.datafix.fixes.References;
import net.minecraft.world.item.ItemStack;

public class Spike1 {
    static final int V1_20_4 = 3700;
    static DataFixer FIXER;
    static RegistryOps<Tag> OPS;
    static int CUR;

    public static void main(String[] a) throws Exception {
        try { SharedConstants.tryDetectVersion(); System.out.println("[boot] tryDetectVersion ok"); }
        catch (Throwable t) { System.out.println("[boot] tryDetectVersion failed (ignored): " + t); }
        long t0 = System.currentTimeMillis();
        Bootstrap.bootStrap();
        CUR = SharedConstants.WORLD_VERSION;
        System.out.println("[boot] bootstrap " + (System.currentTimeMillis() - t0) + "ms, currentDataVersion=" + CUR + ", legacy(1.20.4)=" + V1_20_4);
        FIXER = DataFixers.getDataFixer();
        OPS = RegistryOps.create(NbtOps.INSTANCE, RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY));

        item("ench + name + unbreakable + cmd + damage", "{id:\"minecraft:diamond_sword\",Count:1b,tag:{Enchantments:[{id:\"minecraft:sharpness\",lvl:5},{id:\"minecraft:unbreaking\",lvl:3}],display:{Name:'{\"text\":\"Excalibur\",\"italic\":false}',Lore:['{\"text\":\"line1\"}']},Unbreakable:1b,CustomModelData:7,RepairCost:3,Damage:10}}");
        item("simple stack (no tag)", "{id:\"minecraft:stone\",Count:64b}");
        item("unknown custom tag", "{id:\"minecraft:paper\",Count:1b,tag:{my_custom_flag:1,my_data:{a:1.5d}}}");
        item("potion", "{id:\"minecraft:potion\",Count:1b,tag:{Potion:\"minecraft:strong_strength\"}}");
        item("attribute modifiers", "{id:\"minecraft:diamond_chestplate\",Count:1b,tag:{AttributeModifiers:[{AttributeName:\"minecraft:generic.armor\",Name:\"boost\",Amount:5.0d,Operation:0,UUID:[I;1,2,3,4],Slot:\"chest\"}]}}");
        item("block entity tag (shulker)", "{id:\"minecraft:shulker_box\",Count:1b,tag:{BlockEntityTag:{Items:[{Slot:0b,id:\"minecraft:diamond\",Count:3b}]}}}");
        item("raw legacy count byte int mix", "{id:\"minecraft:apple\",Count:1b,tag:{display:{Name:'\"Plain\"'}}}");

        entity("zombie w/ legacy hand+armor items", "{id:\"minecraft:zombie\",CustomName:'{\"text\":\"Bob\"}',HandItems:[{id:\"minecraft:diamond_sword\",Count:1b,tag:{Enchantments:[{id:\"minecraft:sharpness\",lvl:2}]}},{}],ArmorItems:[{id:\"minecraft:diamond_boots\",Count:1b,tag:{Unbreakable:1b}},{},{},{}]}");
        entity("legacy attributes on mob", "{id:\"minecraft:pig\",Attributes:[{Name:\"minecraft:generic.max_health\",Base:20.0d}]}");
        blockEntity("chest with legacy items", "{id:\"minecraft:chest\",Items:[{Slot:0b,id:\"minecraft:diamond\",Count:3b,tag:{display:{Name:'{\"text\":\"Gem\"}'}}}]}");
        blockEntity("sign legacy text", "{id:\"minecraft:sign\",Text1:'{\"text\":\"hello\"}',Text2:'{\"text\":\"world\"}'}");

        System.out.println("\n=== DONE ===");
    }

    static void item(String label, String snbt) {
        System.out.println("\n### ITEM  " + label);
        System.out.println("  legacy : " + snbt);
        try {
            CompoundTag in = TagParser.parseCompoundFully(snbt);
            Dynamic<Tag> out = FIXER.update(References.ITEM_STACK, new Dynamic<>(NbtOps.INSTANCE, in), V1_20_4, CUR);
            Tag res = out.getValue();
            System.out.println("  fixed  : " + res);
            boolean hasComp = (res instanceof CompoundTag ct) && ct.contains("components");
            System.out.println("  has components key? " + hasComp);
            validate(res);
        } catch (Throwable t) {
            System.out.println("  !!! FAILED: " + t);
        }
    }

    static void entity(String label, String snbt) {
        System.out.println("\n### ENTITY  " + label);
        System.out.println("  legacy : " + snbt);
        try {
            CompoundTag in = TagParser.parseCompoundFully(snbt);
            Dynamic<Tag> out = FIXER.update(References.ENTITY, new Dynamic<>(NbtOps.INSTANCE, in), V1_20_4, CUR);
            System.out.println("  fixed  : " + out.getValue());
        } catch (Throwable t) {
            System.out.println("  !!! FAILED: " + t);
        }
    }

    static void blockEntity(String label, String snbt) {
        System.out.println("\n### BLOCK_ENTITY  " + label);
        System.out.println("  legacy : " + snbt);
        try {
            CompoundTag in = TagParser.parseCompoundFully(snbt);
            Dynamic<Tag> out = FIXER.update(References.BLOCK_ENTITY, new Dynamic<>(NbtOps.INSTANCE, in), V1_20_4, CUR);
            System.out.println("  fixed  : " + out.getValue());
        } catch (Throwable t) {
            System.out.println("  !!! FAILED: " + t);
        }
    }

    static void validate(Tag res) {
        try {
            var r = ItemStack.CODEC.parse(OPS, res);
            if (r.result().isPresent()) {
                System.out.println("  vanilla codec: OK -> " + r.result().get());
            } else {
                System.out.println("  vanilla codec: ERROR -> " + (r.error().isPresent() ? r.error().get().message() : "empty"));
            }
        } catch (Throwable t) {
            System.out.println("  vanilla codec EXC: " + t);
        }
    }
}
