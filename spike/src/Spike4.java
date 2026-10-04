import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.datafixers.DataFixer;
import com.mojang.serialization.Dynamic;

import net.minecraft.SharedConstants;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.CompoundTagArgument;
import net.minecraft.commands.arguments.ResourceArgument;
import net.minecraft.commands.arguments.item.ItemArgument;
import net.minecraft.commands.arguments.item.ItemInput;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
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
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class Spike4 {
    static final int V1_20_4 = 3700;
    static DataFixer FIXER;
    static int CUR;
    static CommandBuildContext BUILD;
    static CommandDispatcher<CommandSourceStack> D = new CommandDispatcher<>();
    static CommandSourceStack SRC;

    static final String[] LEGACY_ITEM_KEYS = {
        "tag","Count","Damage","RepairCost","Unbreakable","CustomModelData","display","Enchantments",
        "StoredEnchantments","AttributeModifiers","HideFlags","CanDestroy","CanPlaceOn","BlockEntityTag",
        "EntityTag","BlockStateTag","Potion","Fireworks","ChargedProjectiles","BundleItems","SkullOwner",
        "Recipes","AttributeModifiers"
    };
    static final String[] LEGACY_ENTITY_KEYS = {
        "HandItems","ArmorItems","Attributes","ActiveEffects","CustomName","Text1","Text2","Text3","Text4",
        "PersistenceRequired","equipment"
    };

    public static void main(String[] a) throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        CUR = SharedConstants.WORLD_VERSION;
        FIXER = DataFixers.getDataFixer();
        Map<ResourceKey<? extends Registry<?>>, Registry<?>> map = new LinkedHashMap<>();
        for (Registry<?> r : BuiltInRegistries.REGISTRY) map.put(r.key(), r);
        RegistryAccess access = new RegistryAccess.ImmutableRegistryAccess((Map) map);
        BUILD = CommandBuildContext.simple(access, FeatureFlags.DEFAULT_FLAGS);
        SRC = Commands.createCompilationContext(PermissionSet.ALL_PERMISSIONS);
        register();

        System.out.println("### C0. control: does a plain modern ItemStack materialize in this harness?");
        try { System.out.println("   new ItemStack(Items.STONE) = " + new ItemStack(Items.STONE)); }
        catch (Throwable t) { System.out.println("   new ItemStack(Items.STONE) -> " + t); }

        System.out.println("\n### T1. /give legacy -> translate (with item-id injection) -> vanilla parse");
        give("give Steve diamond_sword{Enchantments:[{id:\"minecraft:sharpness\",lvl:5}],display:{Name:'{\"text\":\"Excalibur\",\"italic\":false}'},Unbreakable:1b,CustomModelData:7}");
        give("give Steve paper{my_custom_flag:1,my_data:{a:1.5d}}");

        System.out.println("\n### T2. item ID rename gap in the COMMAND TOKEN (not covered by DFU)");
        idProbe("minecraft:grass");
        idProbe("minecraft:short_grass");

        System.out.println("\n### T3. does DFU rename ids INSIDE nbt (nested item stacks)?");
        itemFix("container with legacy id grass", "{id:\"minecraft:shulker_box\",Count:1b,tag:{BlockEntityTag:{Items:[{Slot:0b,id:\"minecraft:grass\",Count:1b}]}}}");

        System.out.println("\n### T4. /summon: modern parse SUCCEEDS on legacy nbt -> silent trap; intent detector needed");
        String summonLegacy = "summon minecraft:zombie ~ ~ ~ {CustomName:'{\"text\":\"Bob\"}',HandItems:[{id:\"minecraft:diamond_sword\",Count:1b,tag:{Enchantments:[{id:\"minecraft:sharpness\",lvl:2}]}},{}],ArmorItems:[{},{},{},{}]}";
        String summonModern = "summon minecraft:zombie ~ ~ ~ {CustomName:{text:\"Bob\"}}";
        summon(summonLegacy);
        summon(summonModern);

        System.out.println("\n=== DONE ===");
    }

    static void register() {
        D.register(Commands.literal("give")
            .then(Commands.argument("targets", net.minecraft.commands.arguments.EntityArgument.players())
                .then(Commands.argument("item", ItemArgument.item(BUILD))
                    .executes(ctx -> {
                        ItemInput in = ItemArgument.getItem(ctx, "item");
                        System.out.println("      >>> EXECUTED give -> item=" + in.item().value() + "  components=" + in.components());
                        return 1;
                    }))));
        D.register(Commands.literal("summon")
            .then(Commands.argument("entity", ResourceArgument.resource(BUILD, Registries.ENTITY_TYPE))
                .then(Commands.argument("pos", net.minecraft.commands.arguments.coordinates.Vec3Argument.vec3())
                    .then(Commands.argument("nbt", CompoundTagArgument.compoundTag())
                        .executes(ctx -> {
                            System.out.println("      >>> EXECUTED summon -> nbt = " + CompoundTagArgument.getCompoundTag(ctx, "nbt"));
                            return 1;
                        })))));
    }

    static void give(String cmd) {
        System.out.println("\n  cmd    : " + cmd);
        try {
            var pr = D.parse(cmd, SRC);
            Commands.validateParseResults(pr);
            System.out.println("  step1  : modern parse OK (no fallback)");
            D.execute(pr);
        } catch (CommandSyntaxException e) {
            System.out.println("  step1  : modern parse FAILED -> " + e.getMessage());
        }
        try {
            String translated = translateGive(cmd);
            System.out.println("  step2  : translated -> " + translated);
            var pr = D.parse(translated, SRC);
            Commands.validateParseResults(pr);
            System.out.println("  step3  : reparse OK");
            D.execute(pr);
        } catch (Throwable t) {
            System.out.println("  !!! translate/reparse FAILED -> " + t);
        }
    }

    static String translateGive(String cmd) throws Exception {
        int brace = cmd.indexOf('{');
        int sp = cmd.lastIndexOf(' ', brace);
        String token = cmd.substring(sp + 1, brace);
        String itemId = token.contains(":") ? token : "minecraft:" + token;
        CompoundTag legacy = TagParser.parseCompoundFully(cmd.substring(brace, cmd.lastIndexOf('}') + 1));
        legacy.putString("id", itemId);
        Tag fixed = FIXER.update(References.ITEM_STACK, new Dynamic<>(NbtOps.INSTANCE, legacy), V1_20_4, CUR).getValue();
        return cmd.substring(0, sp + 1) + renderItemCommandForm((CompoundTag) fixed);
    }

    static String renderItemCommandForm(CompoundTag fixed) {
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

    static void idProbe(String id) {
        try {
            ItemInput in = new ItemArgument(BUILD).parse(new StringReader(id));
            System.out.println("  OK   " + id + " -> " + in.item().value());
        } catch (Throwable t) {
            System.out.println("  FAIL " + id + " -> " + t.getMessage());
        }
    }

    static void itemFix(String label, String snbt) {
        try {
            CompoundTag in = TagParser.parseCompoundFully(snbt);
            Tag out = FIXER.update(References.ITEM_STACK, new Dynamic<>(NbtOps.INSTANCE, in), V1_20_4, CUR).getValue();
            System.out.println("  " + label);
            System.out.println("    legacy : " + snbt);
            System.out.println("    fixed  : " + out);
        } catch (Throwable t) { System.out.println("  FAILED " + t); }
    }

    static void summon(String cmd) {
        System.out.println("\n  cmd    : " + cmd);
        CompoundTag nbt = null;
        try {
            var pr = D.parse(cmd, SRC);
            Commands.validateParseResults(pr);
            System.out.println("  step1  : modern parse OK  <-- NOTE: no exception, so retry-based fallback never fires");
            nbt = CompoundTagArgument.getCompoundTag(pr.getContext().build(cmd), "nbt");
        } catch (Throwable t) {
            System.out.println("  step1  : parse failed -> " + t);
            return;
        }
        List<String> hits = new ArrayList<>();
        for (String k : LEGACY_ENTITY_KEYS) if (nbt.contains(k)) hits.add(k);
        System.out.println("  legacy-intent detector hits = " + hits);
        if (hits.isEmpty()) { System.out.println("  -> treated as MODERN, pass through unchanged"); return; }
        try {
            Tag fixed = FIXER.update(References.ENTITY, new Dynamic<>(NbtOps.INSTANCE, nbt), V1_20_4, CUR).getValue();
            System.out.println("  -> treated as LEGACY, translated nbt = " + fixed);
        } catch (Throwable t) { System.out.println("  translate failed -> " + t); }
    }
}
