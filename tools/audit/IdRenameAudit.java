import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.datafixers.DataFixer;
import com.mojang.datafixers.DSL;
import com.mojang.serialization.Dynamic;

import net.minecraft.SharedConstants;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.util.datafix.fixes.References;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * RegainNBT 离线 ID 改名审计 / 生成器（T4）。
 *
 * 输入域分两块：
 *   A. 主域 = 1.20.4 的物品名 / 方块名清单（本模组的输入域，报告 §0「输入域封闭」）；
 *   B. 兼容域 = 1.20.2 有、1.20.4 没有的名字（实测只有 grass：它在 1.20.3 被改名成 short_grass）。
 *      报告 §2.6 的 grass 用例就落在这里 —— 它不是 1.20.4 的合法 ID，但旧指令/NBT 里真的会出现。
 *
 * 对每个名字：
 *   1. 先查目标版本（26.3）的 BuiltInRegistries；合法则什么都不用做；
 *   2. 不合法才跑原版 DFU 的 References.ITEM_NAME / BLOCK_NAME（3700 -> WORLD_VERSION）；
 *   3. DFU 也救不了的（= 报告 §2.6 说的「原版漏掉的那部分」）标出来 + 给候选建议，人工填 manual-fixes.json；
 *   4. 产出运行时内置表 src/main/resources/regainnbt/id_renames.json 与完整审计报告 tools/audit/out/。
 *
 * 注意：本类的控制台输出一律 ASCII（Windows 控制台编码不可靠）；写出的文件是显式 UTF-8。
 * 用法见 tools/audit/generate-id-renames.ps1。
 */
public final class IdRenameAudit {

    /** 1.20.4 的数据版本。 */
    static final int SOURCE_DATA_VERSION = 3700;
    static int targetDataVersion;
    static DataFixer FIXER;

    static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    public static void main(String[] args) throws Exception {
        Map<String, String> opt = parseArgs(args);
        Path itemsFile = Path.of(opt.getOrDefault("items", "tools/audit/data/names-items-1.20.4.txt"));
        Path blocksFile = Path.of(opt.getOrDefault("blocks", "tools/audit/data/names-blocks-1.20.4.txt"));
        Path extrasFile = Path.of(opt.getOrDefault("extras", "tools/audit/data/extra-legacy-names.txt"));
        Path manualFile = Path.of(opt.getOrDefault("manual", "tools/audit/manual-fixes.json"));
        Path outResource = Path.of(opt.getOrDefault("out", "src/main/resources/regainnbt/id_renames.json"));
        Path outDir = Path.of(opt.getOrDefault("outdir", "tools/audit/reports"));
        boolean strict = opt.containsKey("strict");

        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        targetDataVersion = SharedConstants.getCurrentVersion().dataVersion().version();   // WORLD_VERSION is @Deprecated in 26.3
        FIXER = DataFixers.getDataFixer();

        System.out.println("### RegainNBT ID rename audit");
        System.out.println("source data version         = " + SOURCE_DATA_VERSION + " (1.20.4)");
        System.out.println("target                      = " + SharedConstants.getCurrentVersion().name()
            + " (data version " + targetDataVersion + ")");
        System.out.println("target registries           = items:" + BuiltInRegistries.ITEM.keySet().size()
            + " blocks:" + BuiltInRegistries.BLOCK.keySet().size());

        List<String> itemNames = readNames(itemsFile);
        List<String> blockNames = readNames(blocksFile);
        List<String> extras = Files.exists(extrasFile) ? readNames(extrasFile) : List.of();
        System.out.println("domain 1.20.4               = items:" + itemNames.size() + " (" + itemsFile + ") blocks:"
            + blockNames.size() + " (" + blocksFile + ")");
        System.out.println("domain pre-1.20.3 (extras)  = " + extras.size() + " (" + extrasFile + ") " + extras);

        ManualOverrides manual = readManual(manualFile);
        System.out.println("manual fixes                = items:" + manual.items.size() + " blocks:"
            + manual.blocks.size() + " (" + manualFile + ")");

        AuditResult items = audit("item", itemNames, extras, BuiltInRegistries.ITEM, References.ITEM_NAME, manual.items);
        AuditResult blocks = audit("block", blockNames, extras, BuiltInRegistries.BLOCK, References.BLOCK_NAME, manual.blocks);

        Files.createDirectories(outDir);
        writeResource(outResource, items, blocks, manualFile);
        writeFull(outDir.resolve("id_renames.full.json"), items, blocks);
        writeReport(outDir.resolve("report.txt"), items, blocks, itemsFile, blocksFile, extrasFile, manualFile);
        writeHoles(outDir.resolve("unresolved-items.txt"), items);
        writeHoles(outDir.resolve("unresolved-blocks.txt"), blocks);

        System.out.println();
        printSummary("ITEM", items);
        printSummary("BLOCK", blocks);

        int holes = items.unresolved.size() + blocks.unresolved.size();
        System.out.println();
        if (holes == 0) {
            System.out.println(">>> no unresolved holes: every name that is invalid in the target has a target name.");
        } else {
            System.out.println(">>> UNRESOLVED holes (must be added to " + manualFile + "):");
            for (Mapping m : items.unresolved) System.out.println("      item  " + m.from + "  domain=" + m.domain
                + "  dfu=" + m.dfuResult + "  candidates=" + m.suggestions);
            for (Mapping m : blocks.unresolved) System.out.println("      block " + m.from + "  domain=" + m.domain
                + "  dfu=" + m.dfuResult + "  candidates=" + m.suggestions);
        }
        System.out.println();
        System.out.println("table written               = " + outResource
            + "  (items:" + items.resolved.size() + " blocks:" + blocks.resolved.size() + ")");
        System.out.println("report written              = " + outDir.resolve("report.txt"));
        System.out.println("detail written              = " + outDir.resolve("id_renames.full.json"));

        if (strict && holes > 0) {
            System.out.println("STRICT MODE: failing because " + holes + " holes remain");
            System.exit(2);
        }
    }

    // ---------------------------------------------------------------- audit

    static final class Mapping {
        String from;
        String to;          // 最终采用的映射（null = 无解）
        String source;      // dfu / manual
        String dfuResult;   // DFU 的原始输出（null = 没改名）
        String domain;      // "1.20.4" / "pre-1.20.3"
        boolean dfuMappedToInvalid;
        String note = "";
        List<String> suggestions = List.of();
    }

    static final class AuditResult {
        String kind;
        int total;
        int alreadyValid;
        int dfuRenamed;
        int dfuMappedToInvalid;
        int manualFilled;
        int manualInvalid;
        int extraTotal;
        int extraAlreadyValid;
        final List<Mapping> resolved = new ArrayList<>();
        final List<Mapping> unresolved = new ArrayList<>();
    }

    static AuditResult audit(String kind, List<String> names, List<String> extras, Registry<?> registry,
                             DSL.TypeReference ref, Map<String, String> manual) {
        AuditResult r = new AuditResult();
        r.kind = kind;
        r.total = names.size() + extras.size();
        r.extraTotal = extras.size();
        Set<String> known = new LinkedHashSet<>();
        for (Identifier id : registry.keySet()) known.add(id.toString());

        for (String raw : names) {
            if (isValid(known, raw)) { r.alreadyValid++; continue; }
            collect(r, check(raw, "1.20.4", known, ref, manual));
        }
        for (String raw : extras) {
            if (isValid(known, raw)) { r.extraAlreadyValid++; continue; }
            collect(r, check(raw, "pre-1.20.3", known, ref, manual));
        }
        for (Mapping m : r.resolved) {
            if ("dfu".equals(m.source)) r.dfuRenamed++;
            else r.manualFilled++;
        }
        for (Mapping m : r.unresolved) {
            if (m.dfuMappedToInvalid) r.dfuMappedToInvalid++;
            if (m.note.startsWith("manual target")) r.manualInvalid++;
        }
        r.resolved.sort(Comparator.comparing(m -> m.from));
        r.unresolved.sort(Comparator.comparing(m -> m.from));
        return r;
    }

    static void collect(AuditResult r, Mapping m) {
        if (m.source == null) r.unresolved.add(m);
        else r.resolved.add(m);
    }

    static boolean isValid(Set<String> known, String raw) {
        return known.contains(raw.contains(":") ? raw : "minecraft:" + raw);
    }

    /** 校验单个名字（调用方保证它在目标版本里不合法）。 */
    static Mapping check(String raw, String domain, Set<String> known, DSL.TypeReference ref,
                         Map<String, String> manual) {
        String from = raw.contains(":") ? raw : "minecraft:" + raw;
        Mapping map = new Mapping();
        map.from = from;
        map.domain = domain;
        map.dfuResult = dfuRename(ref, from);
        if (map.dfuResult != null) {
            if (known.contains(map.dfuResult)) {
                map.to = map.dfuResult;
                map.source = "dfu";
            } else {
                map.dfuMappedToInvalid = true;
                map.note = "dfu mapped to a name that is also invalid";
            }
        }
        String manualTo = manual.get(from);
        if (manualTo != null) {
            if (!known.contains(manualTo)) {
                map.note = "manual target " + manualTo + " is not valid in target version";
                map.to = null;
                map.source = null;
            } else {
                if (map.to != null && !map.to.equals(manualTo)) {
                    map.note = "manual overrode dfu (" + map.to + " -> " + manualTo + ")";
                    System.out.println("  !! " + from + ": " + map.note);
                } else if (map.to == null) {
                    map.note = "manual (DFU missed it)";
                }
                map.to = manualTo;
                map.source = "manual";
            }
        }
        if (map.to == null) map.suggestions = suggest(from.substring(from.indexOf(':') + 1), known);
        return map;
    }

    /** DFU ITEM_NAME / BLOCK_NAME 改名；返回 null 表示 DFU 没有改名（原样）。 */
    static String dfuRename(DSL.TypeReference ref, String id) {
        try {
            Dynamic<Tag> in = new Dynamic<>(NbtOps.INSTANCE, StringTag.valueOf(id));
            String out = FIXER.update(ref, in, SOURCE_DATA_VERSION, targetDataVersion).asString(null);
            if (out == null || out.equals(id)) return null;
            return out;
        } catch (Throwable t) {
            System.out.println("  !! DFU rename failed for " + id + ": " + t);
            return null;
        }
    }

    /** 给人工补洞用的候选建议：子串匹配优先，然后编辑距离。 */
    static List<String> suggest(String path, Set<String> known) {
        List<String> out = new ArrayList<>();
        for (String id : known) {
            String p = id.substring(id.indexOf(':') + 1);
            if (p.endsWith("_" + path) || p.startsWith(path + "_") || p.contains(path)) out.add(id + " (substring)");
        }
        if (out.size() > 6) out = new ArrayList<>(out.subList(0, 6));
        if (!out.isEmpty()) return out;
        record Scored(String id, int dist) { }
        List<Scored> scored = new ArrayList<>();
        for (String id : known) {
            String p = id.substring(id.indexOf(':') + 1);
            if (Math.abs(p.length() - path.length()) > 4) continue;
            int d = levenshtein(path, p);
            if (d <= 3) scored.add(new Scored(id, d));
        }
        scored.sort(Comparator.comparingInt(Scored::dist));
        for (int i = 0; i < Math.min(4, scored.size()); i++) out.add(scored.get(i).id() + " (dist=" + scored.get(i).dist() + ")");
        return out;
    }

    static int levenshtein(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] t = prev; prev = cur; cur = t;
        }
        return prev[b.length()];
    }

    // ---------------------------------------------------------------- io

    static void writeResource(Path out, AuditResult items, AuditResult blocks, Path manualFile) throws IOException {
        JsonObject root = new JsonObject();
        root.addProperty("format", 1);
        root.addProperty("comment", "RegainNBT 内置 ID 改名表。只收录「在目标版本里已经不合法、但有确定替代名」的名字；"
            + "运行时先查 BuiltInRegistries，再问原版 DFU（References.ITEM_NAME/BLOCK_NAME），最后才查本表。"
            + "由 tools/audit/generate-id-renames.ps1 生成，不要手改（要补条目请改 tools/audit/manual-fixes.json）。");
        root.addProperty("sourceVersion", "1.20.4 (data version " + SOURCE_DATA_VERSION + ")");
        root.addProperty("targetVersion", SharedConstants.getCurrentVersion().name() + " (data version " + targetDataVersion + ")");
        root.addProperty("generatedAt", Instant.now().toString());
        root.addProperty("generatedBy", "tools/audit/IdRenameAudit.java");
        root.addProperty("manualFixes", manualFile.toString().replace('\\', '/'));
        root.addProperty("detail", "tools/audit/reports/id_renames.full.json");

        JsonObject stats = new JsonObject();
        stats.addProperty("items", items.resolved.size());
        stats.addProperty("blocks", blocks.resolved.size());
        stats.addProperty("itemsDfuDerived", items.dfuRenamed);
        stats.addProperty("itemsManual", items.manualFilled);
        stats.addProperty("blocksDfuDerived", blocks.dfuRenamed);
        stats.addProperty("blocksManual", blocks.manualFilled);
        stats.addProperty("itemsDfuMissedNeedingManual", items.manualFilled + items.unresolved.size());
        stats.addProperty("blocksDfuMissedNeedingManual", blocks.manualFilled + blocks.unresolved.size());
        stats.addProperty("itemsUnresolved", items.unresolved.size());
        stats.addProperty("blocksUnresolved", blocks.unresolved.size());
        root.add("stats", stats);

        root.add("items", flat(items));
        root.add("blocks", flat(blocks));

        Files.createDirectories(out.getParent());
        Files.writeString(out, GSON.toJson(root) + System.lineSeparator(), StandardCharsets.UTF_8);
    }

    static JsonObject flat(AuditResult r) {
        JsonObject o = new JsonObject();
        for (Mapping m : r.resolved) o.addProperty(m.from, m.to);
        return o;
    }

    static void writeFull(Path out, AuditResult items, AuditResult blocks) throws IOException {
        JsonObject root = new JsonObject();
        root.addProperty("sourceVersion", "1.20.4 (data version " + SOURCE_DATA_VERSION + ")");
        root.addProperty("targetVersion", SharedConstants.getCurrentVersion().name() + " (data version " + targetDataVersion + ")");
        root.addProperty("generatedAt", Instant.now().toString());
        root.add("items", fullSection(items));
        root.add("blocks", fullSection(blocks));
        Files.createDirectories(out.getParent());
        Files.writeString(out, GSON.toJson(root) + System.lineSeparator(), StandardCharsets.UTF_8);
    }

    static JsonObject fullSection(AuditResult r) {
        JsonObject o = new JsonObject();
        o.addProperty("namesChecked", r.total);
        o.addProperty("domain_1_20_4_alreadyValid", r.alreadyValid);
        o.addProperty("domain_pre_1_20_3_checked", r.extraTotal);
        o.addProperty("domain_pre_1_20_3_alreadyValid", r.extraAlreadyValid);
        o.addProperty("renamedByDfu", r.dfuRenamed);
        o.addProperty("dfuRenamedToInvalidTarget", r.dfuMappedToInvalid);
        o.addProperty("filledByManual", r.manualFilled);
        o.addProperty("manualTargetInvalid", r.manualInvalid);
        o.addProperty("unresolved", r.unresolved.size());
        JsonObject table = new JsonObject();
        for (Mapping m : r.resolved) {
            JsonObject e = new JsonObject();
            e.addProperty("to", m.to);
            e.addProperty("source", m.source);
            e.addProperty("domain", m.domain);
            e.addProperty("dfuRawResult", m.dfuResult);
            if (!m.note.isEmpty()) e.addProperty("note", m.note);
            table.add(m.from, e);
        }
        o.add("table", table);
        JsonArray un = new JsonArray();
        for (Mapping m : r.unresolved) {
            JsonObject e = new JsonObject();
            e.addProperty("from", m.from);
            e.addProperty("domain", m.domain);
            e.addProperty("dfuRawResult", m.dfuResult);
            e.addProperty("dfuMappedToInvalid", m.dfuMappedToInvalid);
            if (!m.note.isEmpty()) e.addProperty("note", m.note);
            JsonArray s = new JsonArray();
            for (String x : m.suggestions) s.add(x);
            e.add("suggestions", s);
            un.add(e);
        }
        o.add("needsManualFix", un);
        return o;
    }

    static void writeReport(Path out, AuditResult items, AuditResult blocks,
                            Path itemsFile, Path blocksFile, Path extrasFile, Path manualFile) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("RegainNBT ID rename audit\n");
        sb.append("=========================\n");
        sb.append("generated      : ").append(Instant.now()).append('\n');
        sb.append("domain 1.20.4  : ").append(itemsFile).append(" (").append(items.total).append(" names)\n");
        sb.append("               : ").append(blocksFile).append(" (").append(blocks.total).append(" names)\n");
        sb.append("domain legacy  : ").append(extrasFile).append(" (").append(items.extraTotal).append(" names)\n");
        sb.append("manual fixes   : ").append(manualFile).append('\n');
        sb.append("source version : 1.20.4 / data version ").append(SOURCE_DATA_VERSION).append('\n');
        sb.append("target version : ").append(SharedConstants.getCurrentVersion().name())
          .append(" / data version ").append(targetDataVersion).append('\n');
        sb.append("target registry: items=").append(BuiltInRegistries.ITEM.keySet().size())
          .append(" blocks=").append(BuiltInRegistries.BLOCK.keySet().size()).append("\n\n");
        appendSection(sb, "ITEMS", items);
        appendSection(sb, "BLOCKS", blocks);
        Files.createDirectories(out.getParent());
        Files.writeString(out, sb.toString(), StandardCharsets.UTF_8);
    }

    static void appendSection(StringBuilder sb, String title, AuditResult r) {
        sb.append("--- ").append(title).append(" ---\n");
        sb.append("names checked (1.20.4 domain)        : ").append(r.total - r.extraTotal).append('\n');
        sb.append("  still valid in target (no-op)      : ").append(r.alreadyValid).append('\n');
        sb.append("names checked (pre-1.20.3 domain)    : ").append(r.extraTotal).append('\n');
        sb.append("  still valid in target (no-op)      : ").append(r.extraAlreadyValid).append('\n');
        sb.append("invalid -> DFU renamed it            : ").append(r.dfuRenamed).append('\n');
        sb.append("invalid -> DFU result ALSO invalid   : ").append(r.dfuMappedToInvalid).append('\n');
        sb.append("DFU missed (manual fix required)     : ").append(r.manualFilled + r.unresolved.size())
          .append("   (filled by manual-fixes.json: ").append(r.manualFilled)
          .append(", still unresolved: ").append(r.unresolved.size()).append(")\n");
        sb.append("table entries written                : ").append(r.resolved.size()).append("\n\n");
        for (Mapping m : r.resolved) {
            sb.append(String.format("  %-34s -> %-34s [%-6s domain=%s]%s%n", m.from, m.to, m.source, m.domain,
                m.note.isEmpty() ? "" : "  # " + m.note));
        }
        if (!r.unresolved.isEmpty()) {
            sb.append("\n  UNRESOLVED (add to manual-fixes.json):\n");
            for (Mapping m : r.unresolved) {
                sb.append("    ").append(m.from).append("  domain=").append(m.domain)
                  .append("  dfu=").append(m.dfuResult)
                  .append("  candidates=").append(m.suggestions).append('\n');
            }
        }
        sb.append('\n');
    }

    static void writeHoles(Path out, AuditResult r) {
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(r.kind).append(": names that are invalid in the target version and have no known target name\n");
        sb.append("# add verified entries to tools/audit/manual-fixes.json and re-run the generator\n");
        for (Mapping m : r.unresolved) {
            sb.append(m.from).append("  domain=").append(m.domain).append("  dfu=").append(m.dfuResult)
              .append("  candidates=").append(m.suggestions).append('\n');
        }
        try {
            Files.createDirectories(out.getParent());
            Files.writeString(out, sb.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.out.println("  !! could not write " + out + ": " + e);
        }
    }

    static void printSummary(String label, AuditResult r) {
        System.out.println("### " + label + "  checked " + r.total + " names (1.20.4 domain " + (r.total - r.extraTotal)
            + ", legacy domain " + r.extraTotal + ")");
        System.out.println("    1.20.4 domain still valid (no-op)    : " + r.alreadyValid);
        System.out.println("    legacy domain still valid (no-op)    : " + r.extraAlreadyValid);
        System.out.println("    invalid -> renamed by DFU            : " + r.dfuRenamed);
        System.out.println("    invalid -> DFU result ALSO invalid   : " + r.dfuMappedToInvalid);
        System.out.println("    DFU missed (needs manual fix)        : " + (r.manualFilled + r.unresolved.size())
            + "  [filled by manual-fixes.json: " + r.manualFilled + " / still unresolved: " + r.unresolved.size() + "]");
        System.out.println("    entries written to the table         : " + r.resolved.size());
        List<Mapping> sample = r.resolved.subList(0, Math.min(10, r.resolved.size()));
        for (Mapping m : sample) System.out.println("      " + m.from + " -> " + m.to + "  [" + m.source + ", " + m.domain + "]");
        if (r.resolved.size() > sample.size()) System.out.println("      ... and " + (r.resolved.size() - sample.size()) + " more");
    }

    static List<String> readNames(Path p) throws IOException {
        List<String> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String line : Files.readAllLines(p, StandardCharsets.UTF_8)) {
            String s = line.trim();
            if (!s.isEmpty() && s.charAt(0) == '\uFEFF') s = s.substring(1).trim();   // strip BOM left by PowerShell 5.1
            if (s.isEmpty() || s.startsWith("#")) continue;
            if (seen.add(s)) out.add(s);
        }
        out.sort(Comparator.naturalOrder());
        return out;
    }

    static final class ManualOverrides {
        final Map<String, String> items = new TreeMap<>();
        final Map<String, String> blocks = new TreeMap<>();
    }

    static ManualOverrides readManual(Path p) throws IOException {
        ManualOverrides m = new ManualOverrides();
        if (!Files.exists(p)) return m;
        JsonObject root = GSON.fromJson(Files.readString(p, StandardCharsets.UTF_8), JsonObject.class);
        if (root == null) return m;
        readInto(root.getAsJsonObject("items"), m.items);
        readInto(root.getAsJsonObject("blocks"), m.blocks);
        return m;
    }

    static void readInto(JsonObject o, Map<String, String> target) {
        if (o == null) return;
        for (String k : o.keySet()) {
            if (k.startsWith("_")) continue;
            String v = o.get(k).getAsString();
            if (v != null && !v.isBlank()) target.put(k, v);
        }
    }

    static Map<String, String> parseArgs(String[] args) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (a.startsWith("--")) {
                String k = a.substring(2);
                if (i + 1 < args.length && !args[i + 1].startsWith("--")) m.put(k, args[++i]);
                else m.put(k, "true");
            }
        }
        return m;
    }
}
