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
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.ResourceArgument;
import net.minecraft.commands.arguments.item.ItemArgument;
import net.minecraft.commands.arguments.item.ItemInput;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class Spike3 {
    static final int V1_20_4 = 3700;
    static DataFixer FIXER;
    static int CUR;
    static CommandBuildContext BUILD;
    static CommandDispatcher<CommandSourceStack> D = new CommandDispatcher<>();
    static CommandSourceStack SRC;

    @SuppressWarnings({"unchecked", "rawtypes"})
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        CUR = SharedConstants.WORLD_VERSION;
        FIXER = DataFixers.getDataFixer();

        Map<ResourceKey<? extends Registry<?>>, Registry<?>> map = new LinkedHashMap<>();
        for (Registry<?> r : BuiltInRegistries.REGISTRY) map.put(r.key(), r);
        int staticCount = map.size();
        int dynamicAdded = 0;
        try {
            HolderLookup.Provider dyn = VanillaRegistries.createLookup();
            for (Object o : dyn.listRegistries().toArray()) {
                HolderLookup.RegistryLookup<?> lk = (HolderLookup.RegistryLookup<?>) o;
                if (lk instanceof Registry<?> reg) {
                    if (map.put((ResourceKey) lk.key(), reg) == null) dynamicAdded++;
                }
            }
        } catch (Throwable t) {
            System.out.println("[reg] dynamic merge failed: " + t);
        }
        RegistryAccess access = new RegistryAccess.ImmutableRegistryAccess((Map) map);
        System.out.println("[reg] static=" + staticCount + " dynamicAdded=" + dynamicAdded
            + "  ITEM=" + access.lookup(Registries.ITEM).isPresent()
            + "  ENTITY_TYPE=" + access.lookup(Registries.ENTITY_TYPE).isPresent()
            + "  ENCHANTMENT=" + access.lookup(Registries.ENCHANTMENT).isPresent());

        BUILD = CommandBuildContext.simple(access, FeatureFlags.DEFAULT_FLAGS);
        SRC = Commands.createCompilationContext(PermissionSet.ALL_PERMISSIONS);
        System.out.println("[reg] source = " + (SRC != null));

        register();

        System.out.println("\n### T1. direct ItemArgument parse of GENERATED modern syntax");
        direct("minecraft:stone");
        direct("minecraft:diamond_sword[custom_name=\"Excalibur\",unbreakable={},custom_model_data={floats:[7.0f]},damage=10]");
        direct("minecraft:diamond_sword[minecraft:enchantments={levels:{\"minecraft:sharpness\":5}}]");
        direct("minecraft:potion[minecraft:potion_contents={potion:\"minecraft:strong_strength\"}]");

        pipeline("T2. give (legacy nbt -> translate -> execute)", "give Steve diamond_sword{Enchantments:[{id:\"minecraft:sharpness\",lvl:5}],display:{Name:'{\"text\":\"Excalibur\",\"italic\":false}'},Unbreakable:1b,CustomModelData:7}");
        pipeline("T3. give (unknown custom tag -> custom_data)", "give Steve paper{my_custom_flag:1,my_data:{a:1.5d}}");
        pipeline("T4. give (legacy, but no fallback needed / modern syntax)", "give Steve stone");
        pipeline("T5. summon (legacy entity nbt)", "summon minecraft:zombie ~ ~ ~ {CustomName:'{\"text\":\"Bob\"}',HandItems:[{id:\"minecraft:diamond_sword\",Count:1b,tag:{Enchantments:[{id:\"minecraft:sharpness\",lvl:2}]}},{}]}");

        System.out.println("\n### T6. block entity: sign text components (1.20.4 format)");
        String sign = "{id:\"minecraft:sign\",front_text:{messages:['{\"text\":\"hello\"}','{\"text\":\"\"}','{\"text\":\"\"}','{\"text\":\"\"}'],color:\"black\"}}";
        CompoundTag in = TagParser.parseCompoundFully(sign);
        Tag out = FIXER.update(References.BLOCK_ENTITY, new Dynamic<>(NbtOps.INSTANCE, in), V1_20_4, CUR).getValue();
        System.out.println("  legacy : " + sign);
        System.out.println("  fixed  : " + out);

        System.out.println("\n=== DONE ===");
    }

    static void register() {
        D.register(Commands.literal("give")
            .then(Commands.argument("targets", EntityArgument.players())
                .then(Commands.argument("item", ItemArgument.item(BUILD))
                    .executes(ctx -> {
                        ItemInput in = ItemArgument.getItem(ctx, "item");
                        ItemStack st = in.createItemStack(1);
                        System.out.println("      >>> EXECUTED give -> " + st);
                        System.out.println("      >>> item components = " + st.getComponents());
                        return 1;
                    }))));
        D.register(Commands.literal("summon")
            .then(Commands.argument("entity", ResourceArgument.resource(BUILD, Registries.ENTITY_TYPE))
                .then(Commands.argument("pos", net.minecraft.commands.arguments.coordinates.Vec3Argument.vec3())
                    .then(Commands.argument("nbt", CompoundTagArgument.compoundTag())
                        .executes(ctx -> {
                            CompoundTag t = CompoundTagArgument.getCompoundTag(ctx, "nbt");
                            System.out.println("      >>> EXECUTED summon -> nbt = " + t);
                            return 1;
                        })))));
    }

    static void direct(String itemStr) {
        try {
            ItemInput in = new ItemArgument(BUILD).parse(new StringReader(itemStr));
            System.out.println("  OK   " + itemStr);
            System.out.println("       -> " + in.createItemStack(1) + "   comps=" + in.createItemStack(1).getComponents());
        } catch (Throwable t) {
            System.out.println("  FAIL " + itemStr);
            System.out.println("       -> " + t);
        }
    }

    static void pipeline(String label, String legacyCmd) {
        System.out.println("\n### " + label);
        System.out.println("  legacy : " + legacyCmd);
        try {
            var pr0 = D.parse(legacyCmd, SRC);
            Commands.validateParseResults(pr0);
            System.out.println("  step1: parsed as-is (modern syntax OK, no fallback needed)");
            D.execute(pr0);
            return;
        } catch (CommandSyntaxException e) {
            System.out.println("  step1: modern parse FAILED -> " + e.getMessage());
        } catch (Throwable t) {
            System.out.println("  step1: modern parse FAILED (other) -> " + t);
            return;
        }
        String translated;
        try {
            translated = translate(legacyCmd);
        } catch (Throwable t) {
            System.out.println("  step2: TRANSLATION FAILED -> " + t);
            return;
        }
        System.out.println("  step2: translated -> " + translated);
        try {
            var pr = D.parse(translated, SRC);
            Commands.validateParseResults(pr);
            System.out.println("  step3: reparse OK");
            D.execute(pr);
        } catch (Throwable t) {
            System.out.println("  step3: reparse FAILED -> " + t);
        }
    }

    static String translate(String cmd) throws Exception {
        boolean itemCmd = cmd.trim().startsWith("give ");
        String head = cmd.substring(0, cmd.indexOf('{'));
        List<int[]> regions = braceRegions(cmd);
        StringBuilder sb = new StringBuilder();
        int prev = 0;
        for (int[] rg : regions) {
            sb.append(cmd, prev, rg[0]);
            CompoundTag legacy = TagParser.parseCompoundFully(cmd.substring(rg[0], rg[1]));
            if (itemCmd) {
                Tag fixed = FIXER.update(References.ITEM_STACK, new Dynamic<>(NbtOps.INSTANCE, legacy), V1_20_4, CUR).getValue();
                sb.append(renderItemCommandForm((CompoundTag) fixed));
            } else {
                Tag fixed = FIXER.update(References.ENTITY, new Dynamic<>(NbtOps.INSTANCE, legacy), V1_20_4, CUR).getValue();
                sb.append(fixed);
            }
            prev = rg[1];
        }
        sb.append(cmd.substring(prev));
        return sb.toString();
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

    static List<int[]> braceRegions(String s) {
        List<int[]> out = new ArrayList<>();
        int depth = 0, start = -1;
        char quote = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (quote != 0) {
                if (c == '\\') { i++; continue; }
                if (c == quote) quote = 0;
                continue;
            }
            if (c == '\'' || c == '"') { quote = c; continue; }
            if (c == '{') { if (depth == 0) start = i; depth++; }
            else if (c == '}') { depth--; if (depth == 0 && start >= 0) { out.add(new int[]{start, i + 1}); start = -1; } }
        }
        return out;
    }
}
