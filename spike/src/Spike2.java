import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.datafixers.DataFixer;
import com.mojang.serialization.Dynamic;

import net.minecraft.SharedConstants;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.CompoundTagArgument;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.item.ItemArgument;
import net.minecraft.commands.arguments.item.ItemInput;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.TagParser;
import net.minecraft.server.Bootstrap;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.util.datafix.fixes.References;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

public class Spike2 {
    static final int V1_20_4 = 3700;
    static DataFixer FIXER;
    static int CUR;
    static CommandBuildContext BUILD;
    static CommandDispatcher<CommandSourceStack> D = new CommandDispatcher<>();

    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        CUR = SharedConstants.WORLD_VERSION;
        FIXER = DataFixers.getDataFixer();
        long t0 = System.currentTimeMillis();
        HolderLookup.Provider lookup = VanillaRegistries.createLookup();
        System.out.println("[boot] VanillaRegistries.createLookup() " + (System.currentTimeMillis() - t0) + "ms");
        BUILD = CommandBuildContext.simple(lookup, FeatureFlags.DEFAULT_FLAGS);

        D.register(Commands.literal("give")
            .then(Commands.argument("targets", EntityArgument.players())
                .then(Commands.argument("item", ItemArgument.item(BUILD))
                    .executes(ctx -> {
                        ItemInput in = ItemArgument.getItem(ctx, "item");
                        ItemStack st = in.createItemStack(1);
                        System.out.println("      >>> EXECUTED give -> ItemStack = " + st);
                        System.out.println("      >>> components = " + st.getComponents());
                        return 1;
                    }))));
        D.register(Commands.literal("summon")
            .then(Commands.argument("entity", net.minecraft.commands.arguments.ResourceArgument.resource(BUILD, net.minecraft.core.registries.Registries.ENTITY_TYPE))
                .then(Commands.argument("nbt", CompoundTagArgument.compoundTag())
                    .executes(ctx -> {
                        CompoundTag t = CompoundTagArgument.getCompoundTag(ctx, "nbt");
                        System.out.println("      >>> EXECUTED summon -> nbt = " + t);
                        return 1;
                    }))));

        pipeline("A. give + legacy item nbt", "give @p diamond_sword{Enchantments:[{id:\"minecraft:sharpness\",lvl:5}],display:{Name:'{\"text\":\"Excalibur\",\"italic\":false}'},Unbreakable:1b,CustomModelData:7}");
        pipeline("B. give + unknown custom tag", "give @p paper{my_custom_flag:1,my_data:{a:1.5d}}");
        pipeline("C. summon + legacy entity nbt", "summon minecraft:zombie ~ ~ ~ {CustomName:'{\"text\":\"Bob\"}',HandItems:[{id:\"minecraft:diamond_sword\",Count:1b,tag:{Enchantments:[{id:\"minecraft:sharpness\",lvl:2}]}},{}]}");

        System.out.println("\n### D. sign / block-entity text-component conversion (BLOCK_ENTITY fix)");
        String sign = "{id:\"minecraft:sign\",front_text:{messages:['{\"text\":\"hello\"}','{\"text\":\"\"}','{\"text\":\"\"}','{\"text\":\"\"}'],color:\"black\"},back_text:{messages:['{\"text\":\"\"}','{\"text\":\"\"}','{\"text\":\"\"}','{\"text\":\"\"}']}}";
        System.out.println("  legacy : " + sign);
        CompoundTag in = TagParser.parseCompoundFully(sign);
        Tag out = FIXER.update(References.BLOCK_ENTITY, new Dynamic<>(NbtOps.INSTANCE, in), V1_20_4, CUR).getValue();
        System.out.println("  fixed  : " + out);

        System.out.println("\n=== DONE ===");
    }

    static void pipeline(String label, String legacyCmd) {
        System.out.println("\n### " + label);
        System.out.println("  legacy cmd : " + legacyCmd);

        try {
            D.execute(legacyCmd, null);
            System.out.println("  step1: legacy parsed DIRECTLY (no fallback needed!)");
            return;
        } catch (CommandSyntaxException e) {
            System.out.println("  step1: new-syntax parse FAILED -> " + e.getMessage());
        } catch (Throwable t) {
            System.out.println("  step1: new-syntax parse FAILED (other) -> " + t);
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
            ParseResults<CommandSourceStack> pr = D.parse(translated, null);
            Commands.validateParseResults(pr);
            System.out.println("  step3: reparse OK");
            D.execute(pr);
        } catch (Throwable t) {
            System.out.println("  step3: reparse FAILED -> " + t);
        }
    }

    static String translate(String cmd) throws Exception {
        List<int[]> regions = braceRegions(cmd);
        if (regions.isEmpty()) throw new IllegalStateException("no NBT region found");
        StringBuilder sb = new StringBuilder();
        int prev = 0;
        for (int[] rg : regions) {
            sb.append(cmd, prev, rg[0]);
            String raw = cmd.substring(rg[0], rg[1]);
            CompoundTag legacy = TagParser.parseCompoundFully(raw);
            boolean itemLike = legacy.contains("Count") || legacy.contains("tag") || legacy.contains("components");
            if (itemLike) {
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
