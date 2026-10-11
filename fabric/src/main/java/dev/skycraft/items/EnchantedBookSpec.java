package dev.skycraft.items;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/** Deterministic book contents: the same key survives prepare, replay and overflow recovery. */
public final class EnchantedBookSpec {
    private static final Pattern ID = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");
    private EnchantedBookSpec() {}

    public static Map<String,Integer> parse(String text) {
        if (text == null || text.isEmpty() || text.length() > 95)
            throw new IllegalArgumentException("Missing or oversized book enchantments");
        var result = new LinkedHashMap<String,Integer>();
        for (String part : text.split(",", -1)) {
            String[] pair = part.split("=", -1);
            if (pair.length != 2 || !pair[1].matches("[1-9][0-9]{0,2}"))
                throw new IllegalArgumentException("Expected enchantment=level");
            String id = pair[0].contains(":") ? pair[0] : "minecraft:" + pair[0];
            int level = Integer.parseInt(pair[1]);
            if (!ID.matcher(id).matches() || level > 255 || result.putIfAbsent(id, level) != null)
                throw new IllegalArgumentException("Invalid or repeated book enchantment");
        }
        return java.util.Collections.unmodifiableMap(result);
    }
}
