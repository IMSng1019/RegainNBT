import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.datafixers.DataFixer;
import com.mojang.serialization.Dynamic;

import net.minecraft.SharedConstants;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.item.ItemArgument;
import net.minecraft.commands.arguments.item.ItemInput;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.TagParser;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.permissions.PermissionSet;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.util.datafix.fixes.References;
import net.minecraft.world.flag.FeatureFlags;

import java.util.LinkedHashMap;
import java.util.Map;

/** Spike6: the FULL, correct pipeline - legacy command string -> translated -> vanilla parser accepts it. */
public class Spike6 {
    static final int V1_20_4 = 3700;
    static DataFixer FIXER;
    static int CUR;
    static CommandBuildContext BUILD;
    static CommandDispatcher<CommandSourceStack> D = new CommandDispatcher<>();
    static CommandSourceStack SRC;
    static int pass = 0, fail = 0;

    public static void main(String[] a) throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        CUR = SharedConstants.WORLD_VERSION;
        FIXER = DataFixers.getDataFixer();
        Map<ResourceKey<? extends Registry<?>>, Registry<?>> map = new LinkedHashMap<>();
        for (Registry<?> r : BuiltInRegistries.REGISTRY) map.put(r.key(), r);
        BUILD = CommandBuildContext.simple(new RegistryAccess.ImmutableRegistryAccess((Map) map), FeatureFlags.DEFAULT_FLAGS);
        SRC = Commands.createCompilationContext(PermissionSet.ALL_PERMISSIONS);
        D.register(Commands.literal("give")
            .then(Commands.argument("targets", net.minecraft.commands.arguments.EntityArgument.players())
                .then(Commands.argument("item", ItemArgument.item(BUILD)).executes(ctx -> {
                    ItemInput in = ItemArgument.getItem(ctx, "item");
                    System.out.println("      >>> give -> item=" + in.item().value() + "  components=" + in.components());
                    return 1;
                }))));

        System.out.println("### P1  name + unbreakable + custom_model_data + damage");
        give("give Steve diamond_sword{display:{Name:'{\"text\":\"Excalibur\",\"italic\":false}'},Unbreakable:1b,CustomModelData:7,Damage:10}");

        System.out.println("\n### P2  unknown custom tag -> custom_data");
        give("give Steve paper{my_custom_flag:1,my_data:{a:1.5d}}");

        System.out.println("\n### P3  lore only");
        give("give Steve stone{display:{Lore:['{\"text\":\"old lore\"}']}}");

        System.out.println("\n### P4  enchantments (expected: harness lacks the dynamic enchantment registry)");
        give("give Steve diamond_sword{Enchantments:[{id:\"minecraft:sharpness\",lvl:5}]}");

        System.out.println("\n### P5  block entity container inside item");
        give("give Steve shulker_box{BlockEntityTag:{Items:[{Slot:0b,id:\"minecraft:diamond\",Count:3b}]}}");

        System.out.println("\n=== RESULT: pass=" + pass + " fail=" + fail + " ===");
    }

    static void give(String cmd) {
        System.out.println("  legacy  : " + cmd);
        int brace = cmd.indexOf('{');
        int sp = cmd.lastIndexOf(' ', brace);
        String token = cmd.substring(sp + 1, brace);
        String itemId = token.contains(":") ? token : "minecraft:" + token;
        try {
            CompoundTag inner = TagParser.parseCompoundFully(cmd.substring(brace, cmd.lastIndexOf('}') + 1));
            CompoundTag legacy = new CompoundTag();
            legacy.putString("id", itemId);
            legacy.putByte("Count", (byte) 1);
            legacy.put("tag", inner);
            Tag fixed = FIXER.update(References.ITEM_STACK, new Dynamic<>(NbtOps.INSTANCE, legacy), V1_20_4, CUR).getValue();
            CompoundTag fc = (CompoundTag) fixed;
            boolean hasComponents = fc.getCompound("components").isPresent() && !fc.getCompound("components").get().isEmpty();
            String translated = cmd.substring(0, sp + 1) + renderItem(fc);
            System.out.println("  normalized legacy = " + legacy);
            System.out.println("  translated: " + translated);
            System.out.println("  components present? " + hasComponents);
            var pr = D.parse(translated, SRC);
            Commands.validateParseResults(pr);
            D.execute(pr);
            pass++;
        } catch (Throwable t) {
            System.out.println("  >>> FAILED: " + t);
            fail++;
        }
    }

    static String renderItem(CompoundTag fixed) {
        String id = fixed.getString("id").orElseThrow();
        CompoundTag comps = fixed.getCompound("components").orElse(null);
        if (comps == null || comps.isEmpty()) return id;
        StringBuilder sb = new StringBuilder(id).append('[');
        boolean first = true;
        for (String k : comps.keySet()) {
            if (!first) sb.append(',');
            first = false;
            sb.append(k).append('=').append(comps.get(k));
        }
        return sb.append(']').toString();
    }
}
