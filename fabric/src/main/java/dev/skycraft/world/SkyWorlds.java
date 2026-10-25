package dev.skycraft.world;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import dev.skycraft.SkyCraft;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

/**
 * Every Skyrim world (a worldspace or an interior cell) gets its own square of the Minecraft map, so
 * interiors no longer lie on top of each other or of Tamriel, and players can be in different places
 * at once. The server hands out the squares in a spiral around Tamriel's, in the order worlds are
 * first visited, and keeps them in the world save: what was built in a world stays in its square.
 */
public final class SkyWorlds {
	/** Side of each world's square. Tamriel is about 7200 blocks across; interiors are far smaller. */
	public static final int SLOT_BLOCKS = 16384;
	/** Tamriel keeps the middle square, so builds made before worlds had squares stay where they were. */
	public static final String TAMRIEL = "skyrim.esm:00003c";
	private static final int MAX_KEY = 96;
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	private static MinecraftServer loadedFor;
	private static final Map<String, Integer> SLOTS = new LinkedHashMap<>();
	private static int next = 1;

	private SkyWorlds() {
	}

	/** A world's key from its plugin and its FormID within it; the runtime FormID if Skyrim gave no plugin. */
	public static String key(String plugin, int localId, int worldId) {
		if (plugin == null || plugin.isBlank()) {
			return String.format(Locale.ROOT, "id:%08x", worldId);
		}
		return plugin.trim().toLowerCase(Locale.ROOT) + ":" + String.format(Locale.ROOT, "%06x", localId & 0xFFFFFF);
	}

	/** Keys a client may send: what {@link #key} makes, nothing that could name a file or flood the save. */
	public static boolean validKey(String key) {
		if (key == null || key.isEmpty() || key.length() > MAX_KEY || key.indexOf(':') <= 0) {
			return false;
		}
		for (int i = 0; i < key.length(); i++) {
			char c = key.charAt(i);
			if (c < 0x20 || c == '"' || c == '\\') {
				return false;
			}
		}
		return true;
	}

	/** Square {@code index} of the spiral, in squares: 0 is the middle, then ring after ring around it. */
	public static int[] spiral(int index) {
		if (index <= 0) {
			return new int[] { 0, 0 };
		}
		int k = (int) Math.ceil((Math.sqrt(index + 1.0) - 1.0) / 2.0);
		while ((2L * k + 1) * (2L * k + 1) <= index) {
			k++;
		}
		while (k > 1 && (2L * k - 1) * (2L * k - 1) > index) {
			k--;
		}
		int offset = index - (2 * k - 1) * (2 * k - 1);
		int side = offset / (2 * k), along = offset % (2 * k);
		return switch (side) {
			case 0 -> new int[] { k, -k + 1 + along };
			case 1 -> new int[] { k - 1 - along, k };
			case 2 -> new int[] { -k, k - 1 - along };
			default -> new int[] { -k + 1 + along, -k };
		};
	}

	/** The block origin (x, z) of {@code key}'s square on this server, giving it the next square if new. */
	public static synchronized int[] originFor(MinecraftServer server, String key) {
		load(server);
		Integer index = SLOTS.get(key);
		if (index == null) {
			index = next++;
			SLOTS.put(key, index);
			save(server);
			SkyCraft.LOG.info("SkyCraft: Skyrim world {} gets map square {} ({} worlds placed)", key, index, SLOTS.size());
		}
		int[] square = spiral(index);
		return new int[] { square[0] * SLOT_BLOCKS, square[1] * SLOT_BLOCKS };
	}

	/** The server stopped: the next one loads its own save's squares. */
	public static synchronized void forget() {
		loadedFor = null;
		SLOTS.clear();
		next = 1;
	}

	private static Path file(MinecraftServer server) {
		return server.getWorldPath(LevelResource.ROOT).resolve("skycraft-worlds.json");
	}

	private static void load(MinecraftServer server) {
		if (loadedFor == server) {
			return;
		}
		loadedFor = server;
		SLOTS.clear();
		SLOTS.put(TAMRIEL, 0);
		next = 1;
		Path path = file(server);
		if (!Files.exists(path)) {
			return;
		}
		try {
			JsonObject root = GSON.fromJson(Files.readString(path, StandardCharsets.UTF_8), JsonObject.class);
			var slots = root != null ? root.getAsJsonObject("slots") : null;
			if (slots == null) {
				return;
			}
			// Squares are indices in visiting order; keep each where the save put it.
			Map<Integer, String> byIndex = new java.util.TreeMap<>();
			for (var entry : slots.entrySet()) {
				if (validKey(entry.getKey()) && entry.getValue().isJsonPrimitive()) {
					byIndex.put(entry.getValue().getAsInt(), entry.getKey());
				}
			}
			for (var entry : byIndex.entrySet()) {
				if (entry.getKey() > 0 && !SLOTS.containsKey(entry.getValue())) {
					SLOTS.put(entry.getValue(), entry.getKey());
					next = Math.max(next, entry.getKey() + 1);
				}
			}
			SkyCraft.LOG.info("SkyCraft: {} Skyrim worlds have map squares in this save", SLOTS.size());
		} catch (IOException | RuntimeException e) {
			SkyCraft.LOG.error("SkyCraft: couldn't read {}; Skyrim worlds get squares anew", path, e);
		}
	}

	private static void save(MinecraftServer server) {
		JsonObject slots = new JsonObject();
		SLOTS.forEach(slots::addProperty);
		JsonObject root = new JsonObject();
		root.addProperty("version", 1);
		root.addProperty("slotBlocks", SLOT_BLOCKS);
		root.add("slots", slots);
		Path path = file(server);
		try {
			Path temp = path.resolveSibling(path.getFileName() + ".tmp");
			Files.writeString(temp, GSON.toJson(root), StandardCharsets.UTF_8);
			Files.move(temp, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException e) {
			SkyCraft.LOG.error("SkyCraft: couldn't save {}", path, e);
		}
	}
}
