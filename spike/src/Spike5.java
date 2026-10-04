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

public class Spike5 {
    static final int V1_20_4 = 3700;
    static DataFixer FIXER;
    static int CUR;
    static CommandBuildContext BUILD;
    static CommandDispatcher<CommandSourceStack> D = new CommandDispatcher<>();
    static CommandSourceStack SRC;

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
                    System.out.println("      >>> give EXECUTED: item=" + in.item().value() + "  components=" + in.components());
                    return 1;
                }))));

        System.out.println("### Q1  /give legacy -> inject id+Count -> DFU -> modern command syntax -> vanilla parse");
        give("give Steve diamond_sword{Enchantments:[{id:\"minecraft:sharpness\",lvl:5}],display:{Name:'{\"text\":\"Excalibur\",\"italic\":false}'},Unbreakable:1b,CustomModelData:7}");
        give("give Steve paper{my_custom_flag:1,my_data:{a:1.5d}}");
        give("give Steve stone{display:{Lore:['{\"text\":\"old lore\"}']}}");

        System.out.println("\n### Q2  /summon legacy -> inject id -> DFU ENTITY");
        String e1 = "{CustomName:'{\"text\":\"Bob\"}',HandItems:[{id:\"minecraft:diamond_sword\",Count:1b,tag:{Enchantments:[{id:\"minecraft:sharpness\",lvl:2}]}},{}]}";
        entityFix("with id injected", e1, "minecraft:zombie");
        entityFix("control: no id injected", e1, null);

        System.out.println("\n### Q3  does DFU rename item ids (grass -> short_grass)?");
        itemFix("{id:\"minecraft:grass\",Count:1b}");
        itemFix("{id:\"minecraft:grass\",Count:1b,tag:{display:{Name:'\"g\"'}}}");
        itemFix("{id:\"minecraft:shulker_box\",Count:1b,tag:{BlockEntityTag:{Items:[{Slot:0b,id:\"minecraft:grass\",Count:1b,tag:{display:{Name:'\"g\"'}}}]}}}");
        itemFix("{id:\"minecraft:scute\",Count:1b}");
        itemFix("{id:\"minecraft:chain\",Count:1b}");

        System.out.println("\n### Q4  type-aware legacy detection");
        detect("CustomName legacy (string)", "{CustomName:'{\"text\":\"Bob\"}'}");
        detect("CustomName modern (compound)", "{CustomName:{text:\"Bob\"}}");
        detect("HandItems", "{HandItems:[{}]}");
        detect("equipment modern", "{equipment:{mainhand:{id:\"minecraft:stone\",count:1}}}");
        detect("plain modern", "{NoAI:1b,Health:20.0f}");

        System.out.println("\n=== DONE ===");
    }

    static void give(String cmd) {
        System.out.println("\n  cmd  : " + cmd);
        int brace = cmd.indexOf('{');
        int sp = cmd.lastIndexOf(' ', brace);
        String token = cmd.substring(sp + 1, brace);
        String itemId = token.contains(":") ? token : "minecraft:" + token;
        try {
            CompoundTag legacy = TagParser.parseCompoundFully(cmd.substring(brace, cmd.lastIndexOf('}') + 1));
            legacy.putString("id", itemId);
            if (!legacy.contains("Count")) legacy.putByte("Count", (byte) 1);
            Tag fixed = FIXER.update(References.ITEM_STACK, new Dynamic<>(NbtOps.INSTANCE, legacy), V1_20_4, CUR).getValue();
            String translated = cmd.substring(0, sp + 1) + renderItem((CompoundTag) fixed);
            System.out.println("  fixed: " + translated);
            var pr = D.parse(translated, SRC);
            Commands.validateParseResults(pr);
            System.out.println("  parse: OK");
            D.execute(pr);
        } catch (Throwable t) {
            System.out.println("  !!! " + t);
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

    static void entityFix(String label, String snbt, String id) {
        try {
            CompoundTag in = TagParser.parseCompoundFully(snbt);
            if (id != null) in.putString("id", id);
            Tag out = FIXER.update(References.ENTITY, new Dynamic<>(NbtOps.INSTANCE, in), V1_20_4, CUR).getValue();
            System.out.println("  [" + label + "]");
            System.out.println("    in  : " + in);
            System.out.println("    out : " + out);
        } catch (Throwable t) { System.out.println("  [" + label + "] FAILED " + t); }
    }

    static void itemFix(String snbt) {
        try {
            Tag out = FIXER.update(References.ITEM_STACK, new Dynamic<>(NbtOps.INSTANCE, TagParser.parseCompoundFully(snbt)), V1_20_4, CUR).getValue();
            System.out.println("    " + snbt);
            System.out.println("      -> " + out);
        } catch (Throwable t) { System.out.println("    " + snbt + "  FAILED " + t); }
    }

    static void detect(String label, String snbt) {
        try {
            CompoundTag t = TagParser.parseCompoundFully(snbt);
            boolean legacy = false;
            StringBuilder why = new StringBuilder();
            if (t.contains("HandItems") || t.contains("ArmorItems") || t.contains("AttributeModifiers")) { legacy = true; why.append("legacy-equipment-keys "); }
            if (t.get("CustomName") != null && t.get("CustomName").getId() == 8) { legacy = true; why.append("CustomName-as-JSON-string "); }
            if (t.get("Text1") != null || t.get("Text2") != null) { legacy = true; why.append("sign-Text1 "); }
            if (t.get("tag") != null) { legacy = true; why.append("item-tag "); }
            System.out.println("    " + label + " -> " + (legacy ? "LEGACY" : "MODERN") + "   (" + why.toString().trim() + ")");
        } catch (Throwable t) { System.out.println("    " + label + " FAILED " + t); }
    }
}
