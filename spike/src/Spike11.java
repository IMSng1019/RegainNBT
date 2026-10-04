import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Pattern;

/** Spike11: extract the 1.20.4 NBT vocabulary (yarn names) and cross-classify against 26.1.2. */
public class Spike11 {
    static final Pattern KEY = Pattern.compile("^[A-Za-z][A-Za-z0-9_]{2,49}$");

    public static void main(String[] args) throws Exception {
        String home = System.getProperty("user.home");
        String oldJar = home + "\\.gradle\\caches\\fabric-loom\\minecraftMaven\\net\\minecraft\\minecraft-common\\"
            + "1.20.4-net.fabricmc.yarn.1_20_4.1.20.4+build.3-v2\\"
            + "minecraft-common-1.20.4-net.fabricmc.yarn.1_20_4.1.20.4+build.3-v2.jar";
        String newJar = home + "\\.gradle\\caches\\fabric-loom\\minecraftMaven\\net\\minecraft\\minecraft-common-deobf\\"
            + "26.1.2\\minecraft-common-deobf-26.1.2.jar";

        // yarn-style package prefixes in 1.20.4
        String[] pkgs = {
            "net/minecraft/entity/",
            "net/minecraft/block/entity/",
            "net/minecraft/item/",
            "net/minecraft/inventory/",
            "net/minecraft/nbt/"
        };

        TreeMap<String, Integer> perPkg = new TreeMap<>();
        Set<String> oldAll = new TreeSet<>();
        try (JarFile jf = new JarFile(oldJar)) {
            Enumeration<JarEntry> en = jf.entries();
            while (en.hasMoreElements()) {
                JarEntry e = en.nextElement();
                String n = e.getName();
                if (!n.endsWith(".class")) continue;
                String pkg = null;
                for (String p : pkgs) if (n.startsWith(p)) { pkg = p; break; }
                if (pkg == null) continue;
                byte[] b;
                try (InputStream in = jf.getInputStream(e)) { b = in.readAllBytes(); }
                // 精确度过滤：只统计真正做 NBT 读写的类（常量池里引用了 NbtCompound/NbtElement/NbtList）
                Set<String> pool = allUtf8(b);
                boolean nbtClass = pool.contains("net/minecraft/nbt/NbtCompound")
                        || pool.contains("net/minecraft/nbt/NbtElement")
                        || pool.contains("net/minecraft/nbt/NbtList");
                if (!nbtClass) continue;
                Set<String> lits = stringLiterals(b);
                perPkg.merge(pkg, 1, Integer::sum);
                oldAll.addAll(lits);
            }
        }
        System.out.println("scanned 1.20.4 classes per package: " + perPkg);
        System.out.println("raw string literals collected: " + oldAll.size());

        Set<String> newLits = new HashSet<>();
        Set<String> newFixerUtf8 = new HashSet<>();
        try (JarFile jf = new JarFile(newJar)) {
            Enumeration<JarEntry> en = jf.entries();
            while (en.hasMoreElements()) {
                JarEntry e = en.nextElement();
                String n = e.getName();
                if (!n.endsWith(".class")) continue;
                byte[] b;
                try (InputStream in = jf.getInputStream(e)) { b = in.readAllBytes(); }
                if (n.contains("/datafix/")) newFixerUtf8.addAll(allUtf8(b));
                else newLits.addAll(stringLiterals(b));
            }
        }
        System.out.println("26.1.2 non-datafixer literals: " + newLits.size() + ", datafixer utf8: " + newFixerUtf8.size());
        System.out.println();

        List<String> a = new ArrayList<>(), b = new ArrayList<>(), c = new ArrayList<>();
        for (String k : oldAll) {
            if (!KEY.matcher(k).matches()) continue;
            if (newLits.contains(k)) a.add(k);
            else if (newFixerUtf8.contains(k)) b.add(k);
            else c.add(k);
        }
        System.out.println("key-shaped candidates: " + (a.size() + b.size() + c.size()));
        System.out.println("  A still-literal-in-26.1.2 (probably still read) : " + a.size());
        System.out.println("  B known to vanilla datafixer (auto-converted)   : " + b.size());
        System.out.println("  C neither  -> SUSPECTED GAP (we must handle)    : " + c.size());
        System.out.println();
        System.out.println("=== C: suspected gaps (full list) ===");
        int i = 0;
        for (String k : c) { System.out.print(pad(k, 34)); if (++i % 3 == 0) System.out.println(); }
        if (i % 3 != 0) System.out.println();
        System.out.println();
        System.out.println("=== B: sample of keys the vanilla fixer knows (first 45) ===");
        int j = 0;
        for (String k : b) { System.out.print(pad(k, 34)); if (++j % 3 == 0) System.out.println(); if (j >= 45) break; }
        System.out.println();
    }

    static String pad(String s, int w) { StringBuilder sb = new StringBuilder(s); while (sb.length() < w) sb.append(' '); return sb.toString(); }

    static Set<String> stringLiterals(byte[] cls) {
        Set<String> out = new HashSet<>();
        List<Integer> refs = stringRefs(cls);
        if (refs.isEmpty()) return out;
        Object[] cp = parsePool(cls);
        if (cp == null) return out;
        for (int r : refs) if (r > 0 && r < cp.length && cp[r] instanceof String s) out.add(s);
        return out;
    }

    static Set<String> allUtf8(byte[] cls) {
        Set<String> out = new HashSet<>();
        Object[] cp = parsePool(cls);
        if (cp == null) return out;
        for (Object o : cp) if (o instanceof String s) out.add(s);
        return out;
    }

    static Object[] parsePool(byte[] cls) {
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(cls));
            in.readInt(); in.readUnsignedShort(); in.readUnsignedShort();
            int count = in.readUnsignedShort();
            Object[] cp = new Object[count];
            for (int i = 1; i < count; i++) {
                int tag = in.readUnsignedByte();
                switch (tag) {
                    case 1 -> cp[i] = in.readUTF();
                    case 3, 4 -> in.skipBytes(4);
                    case 5, 6 -> { in.skipBytes(8); i++; }
                    case 7, 16, 19, 20 -> in.skipBytes(2);
                    case 8 -> cp[i] = new int[]{in.readUnsignedShort()};
                    case 9, 10, 11, 12, 17, 18 -> in.skipBytes(4);
                    case 15 -> in.skipBytes(3);
                    default -> { return null; }
                }
            }
            return cp;
        } catch (Exception e) { return null; }
    }

    static List<Integer> stringRefs(byte[] cls) {
        List<Integer> out = new ArrayList<>();
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(cls));
            in.readInt(); in.readUnsignedShort(); in.readUnsignedShort();
            int count = in.readUnsignedShort();
            for (int i = 1; i < count; i++) {
                int tag = in.readUnsignedByte();
                switch (tag) {
                    case 1 -> in.readUTF();
                    case 3, 4 -> in.skipBytes(4);
                    case 5, 6 -> { in.skipBytes(8); i++; }
                    case 7, 16, 19, 20 -> in.skipBytes(2);
                    case 8 -> out.add(in.readUnsignedShort());
                    case 9, 10, 11, 12, 17, 18 -> in.skipBytes(4);
                    case 15 -> in.skipBytes(3);
                    default -> { return out; }
                }
            }
        } catch (Exception e) { /* ignore */ }
        return out;
    }
}
