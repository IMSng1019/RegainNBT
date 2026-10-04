import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import net.minecraft.SharedConstants;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.ComponentArgument;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.selector.EntitySelector;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.permissions.PermissionSet;
import net.minecraft.world.flag.FeatureFlags;

import java.util.LinkedHashMap;
import java.util.Map;

/** Spike7: text-component arguments and entity-selector nbt= - which of them fail loudly vs silently? */
public class Spike7 {
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

        D.register(Commands.literal("tellraw")
            .then(Commands.argument("targets", EntityArgument.players())
                .then(Commands.argument("message", ComponentArgument.textComponent(BUILD)).executes(ctx -> {
                    Component c = ComponentArgument.getRawComponent(ctx, "message");
                    System.out.println("      >>> parsed Component = " + c + "   getString()=[" + c.getString() + "]");
                    return 1;
                }))));
        D.register(Commands.literal("kill")
            .then(Commands.argument("targets", EntityArgument.entities()).executes(ctx -> {
                System.out.println("      >>> kill parsed, selector = " + ctx.getArgument("targets", EntitySelector.class));
                return 1;
            })));

        System.out.println("### 文本组件参数（1.21.5 起 JSON -> SNBT）");
        probe("tellraw Steve {\"text\":\"hi\"}", true);
        probe("tellraw Steve '{\"text\":\"hi\"}'", true);
        probe("tellraw Steve \"hi\"", true);

        System.out.println("\n### 实体选择器里的 nbt= （旧实体 NBT 匹配）");
        probe("kill @e[nbt={HandItems:[{}]}]", false);
        probe("kill @e[nbt={equipment:{}}]", false);
        probe("kill @e[type=minecraft:zombie]", false);

        System.out.println("\n=== DONE ===");
    }

    static void probe(String cmd, boolean execute) {
        System.out.println("\n  cmd: " + cmd);
        try {
            var pr = D.parse(cmd, SRC);
            Commands.validateParseResults(pr);
            System.out.println("  -> parse OK" + (execute ? "  (看下面实际解析结果)" : "   <-- 语法通过，旧写法不会被拦下"));
            if (execute) D.execute(pr);
        } catch (Throwable t) {
            System.out.println("  -> FAILED: " + t);
        }
    }
}
