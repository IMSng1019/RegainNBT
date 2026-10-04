import com.mojang.brigadier.CommandDispatcher;
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
import net.minecraft.world.item.ItemStack;

import java.util.LinkedHashMap;
import java.util.Map;

/** Spike8: prove WHICH jar/version is running, and that the runtime accepts modern-only syntax while rejecting legacy syntax. */
public class Spike8 {
    static final int V1_20_4 = 3700;
    static CommandBuildContext BUILD;
    static CommandDispatcher<CommandSourceStack> D = new CommandDispatcher<>();
    static CommandSourceStack SRC;

    public static void main(String[] a) throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        Map<ResourceKey<? extends Registry<?>>, Registry<?>> map = new LinkedHashMap<>();
        for (Registry<?> r : BuiltInRegistries.REGISTRY) map.put(r.key(), r);
        BUILD = CommandBuildContext.simple(new RegistryAccess.ImmutableRegistryAccess((Map) map), FeatureFlags.DEFAULT_FLAGS);
        SRC = Commands.createCompilationContext(PermissionSet.ALL_PERMISSIONS);

        System.out.println("========== A. JVM 自己报告它在跑哪个 jar / 哪个版本 ==========");
        System.out.println("  ItemStack     加载自 : " + origin(ItemStack.class));
        System.out.println("  DataFixers    加载自 : " + origin(DataFixers.class));
        System.out.println("  ItemStackComponentizationFix 加载自 : " + origin(Class.forName("net.minecraft.util.datafix.fixes.ItemStackComponentizationFix")));
        System.out.println("  Bootstrap     加载自 : " + origin(Bootstrap.class));
        System.out.println("  ---");
        System.out.println("  SharedConstants.getCurrentVersion().name() = " + SharedConstants.getCurrentVersion().name());
        try { System.out.println("  SharedConstants.getCurrentVersion().id()   = " + SharedConstants.getCurrentVersion().id()); } catch (Throwable t) {}
        System.out.println("  SharedConstants.WORLD_VERSION              = " + SharedConstants.WORLD_VERSION);
        Object wv = SharedConstants.getCurrentVersion();
        for (var m : wv.getClass().getMethods()) {
            if (m.getParameterCount() == 0 && m.getName().toLowerCase().contains("dataversion")) {
                System.out.println("  WorldVersion." + m.getName() + "() = " + m.invoke(wv));
            }
        }
        System.out.println("  1.20.4 的数据版本                          = " + V1_20_4);

        D.register(Commands.literal("give")
            .then(Commands.argument("targets", net.minecraft.commands.arguments.EntityArgument.players())
                .then(Commands.argument("item", ItemArgument.item(BUILD)).executes(ctx -> {
                    ItemInput in = ItemArgument.getItem(ctx, "item");
                    System.out.println("        -> item=" + in.item().value() + "  components=" + in.components());
                    return 1;
                }))));

        System.out.println("\n========== B. 同一运行时：现代专有语法 vs 1.20.4 旧语法 ==========");
        probe("give Steve diamond_sword[custom_name=\"ModernOnly\"]", "现代专有语法（1.20.4 上必然报错）");
        probe("give Steve diamond_sword{display:{Name:'\"Legacy\"'}}", "1.20.4 旧语法（若本运行时是 1.20.4，这里必然通过）");
        probe("give Steve minecraft:grass", "已被改名的旧 ID");
        probe("give Steve minecraft:short_grass", "改名后的新 ID");

        System.out.println("\n========== C. 这个 jar 认识 1.20.4 是「历史版本」吗 ==========");
        CompoundTag legacy = TagParser.parseCompoundFully("{id:\"minecraft:diamond_sword\",Count:1b,tag:{Unbreakable:1b}}");
        DataFixer fixer = DataFixers.getDataFixer();
        Tag fixed = fixer.update(References.ITEM_STACK, new Dynamic<>(NbtOps.INSTANCE, legacy), V1_20_4, SharedConstants.WORLD_VERSION).getValue();
        System.out.println("  3700 -> " + SharedConstants.WORLD_VERSION + " 结果: " + fixed);
        System.out.println("  (只有「当前版本 > 3700」的新版代码才会做这个升级；如果是 1.20.4 的代码，这里什么都不会发生)");

        System.out.println("\n=== DONE ===");
    }

    static String origin(Class<?> c) {
        try {
            var cs = c.getProtectionDomain().getCodeSource();
            return cs == null ? "<bootstrap>" : cs.getLocation().toString();
        } catch (Throwable t) { return "<err " + t + ">"; }
    }

    static void probe(String cmd, String note) {
        System.out.println("\n  [" + note + "]");
        System.out.println("  cmd: " + cmd);
        try {
            var pr = D.parse(cmd, SRC);
            Commands.validateParseResults(pr);
            System.out.println("  -> 解析通过");
            D.execute(pr);
        } catch (Throwable t) {
            System.out.println("  -> 解析失败: " + t.getMessage());
        }
    }
}
