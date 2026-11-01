package com.zakius.roma;

import com.ibm.icu.text.Transliterator;
import com.ibm.icu.util.UResourceBundle;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

// :desktop:icuSlim's translit/root.res: ICU4J's 419 rule sets, inline strings in that file, with those roma's
// transliterators never load zeroed in place (about 140 KB less in the jar). Loaded are the registry's entries whose
// rules got read while the transliterators ran over sample(). It reads ICU's internals, so a new ICU4J that changes
// them fails here, not quietly. Java, not Kotlin: Compose's compiler plugin takes every Kotlin source set.
public class IcuTrim {
    // The transliterators roma uses (core's Romanize.kt). :desktop:icuCheck runs them on both jars too.
    public static final List<String> IDS = List.of("Han-Latin", "Traditional-Simplified", "Greek-Latin", "Greek-Latin/UNGEGN", "Cyrillic-Latin", "mn-mn_Latn/MNS", "Any-Latin");

    // Every assigned code point, by script in runs of 64.
    public static Collection<String> sample() {
        TreeMap<Character.UnicodeScript, StringBuilder> text = new TreeMap<>();
        for (int c = 0; c <= 0x3FFFF; c++) {
            int type = Character.getType(c);
            if (!Character.isDefined(c) || Character.isISOControl(c) || type == Character.SURROGATE || type == Character.PRIVATE_USE) continue;
            StringBuilder sb = text.computeIfAbsent(Character.UnicodeScript.of(c), k -> new StringBuilder()).appendCodePoint(c);
            if (sb.codePointCount(0, sb.length()) % 64 == 0) sb.append(' ');
        }
        return text.values().stream().map(StringBuilder::toString).toList();
    }

    static Object field(Object o, Class<?> c, String name) throws ReflectiveOperationException {
        Field f = c.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(o);
    }

    static Map<String, Object> entries() throws ReflectiveOperationException {
        Object registry = field(null, Transliterator.class, "registry");
        Map<String, Object> out = new HashMap<>();
        ((Map<?, ?>) field(registry, registry.getClass(), "registry")).forEach((k, v) -> out.put(k.toString(), ((Object[]) v)[0]));
        return out;
    }

    static boolean isRules(Object e) { return e != null && e.getClass().getSimpleName().equals("ResourceEntry"); }

    static int find(byte[] in, byte[] pattern, int from) {
        outer: for (int at = from; at <= in.length - pattern.length; at += 2) {
            for (int i = 0; i < pattern.length; i++) if (in[at + i] != pattern[i]) continue outer;
            return at;
        }
        return -1;
    }

    public static void main(String[] args) throws Exception {
        // Each entry finds its rule set by its index in RuleBasedTransliteratorIDs, held by its loader.
        Map<String, Integer> index = new HashMap<>();
        for (var e : entries().entrySet()) {
            if (!isRules(e.getValue())) continue;
            Object load = field(e.getValue(), e.getValue().getClass(), "resourceSupplier");
            Field i = Arrays.stream(load.getClass().getDeclaredFields()).filter(f -> f.getType() == int.class).findFirst().orElseThrow();
            i.setAccessible(true);
            index.put(e.getKey(), i.getInt(load));
        }
        Collection<String> sample = sample();
        for (String id : IDS) {
            Transliterator t = Transliterator.getInstance(id);
            sample.forEach(t::transliterate);
        }
        Map<String, Object> after = entries();
        Set<Integer> used = new HashSet<>();
        for (var e : index.entrySet()) {
            Object now = after.get(e.getKey());
            if (!isRules(now) || field(now, now.getClass(), "resource") != null) used.add(e.getValue());
        }
        UResourceBundle ids = UResourceBundle.getBundleInstance("com/ibm/icu/impl/data/icudata/translit", "root").get("RuleBasedTransliteratorIDs");
        Set<String> keep = new HashSet<>();
        for (int i : used) keep.add(ids.get(i).get(0).getString("resource"));
        byte[] res;
        try (var in = Transliterator.class.getResourceAsStream("/com/ibm/icu/impl/data/icudata/translit/root.res")) { res = in.readAllBytes(); }
        // Where the used rules are, wherever their text occurs: an unused set may be one, or part of one.
        boolean[] kept = new boolean[res.length];
        for (String rules : keep) {
            byte[] bytes = rules.getBytes(StandardCharsets.UTF_16BE);
            for (int at = find(res, bytes, 0); at >= 0; at = find(res, bytes, at + 2)) Arrays.fill(kept, at, at + bytes.length, true);
        }
        int zeroed = 0;
        for (int i : new TreeSet<>(index.values())) {
            if (used.contains(i)) continue;
            byte[] bytes = ids.get(i).get(0).getString("resource").getBytes(StandardCharsets.UTF_16BE);
            int at = find(res, bytes, 0);
            if (at < 0 || java.util.stream.IntStream.range(at, at + bytes.length).anyMatch(k -> kept[k])) continue;
            Arrays.fill(res, at, at + bytes.length, (byte) 0);
            zeroed++;
        }
        if (used.size() < 20 || used.size() > 100 || zeroed < 200) throw new IllegalStateException("ICU's transliteration data changed: " + used.size() + " rule sets used, " + zeroed + " zeroed");
        Path out = Path.of(args[0]);
        Files.createDirectories(out.getParent());
        Files.write(out, res);
        System.out.println("translit/root.res: " + index.size() + " rule sets, " + used.size() + " used, " + zeroed + " zeroed");
    }
}
