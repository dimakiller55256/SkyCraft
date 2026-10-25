package dev.skycraft.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.skycraft.SkyCraft;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.TimeUnit;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * SkyCraft Link, the helper that carries Minecraft and Skyrim Together between Steam friends
 * through Spacewar's relays (no VPN, no open ports). The Steam menu runs one at a time and it ends
 * with this game. Its JSON lines become the menu's state; their texts are already in Russian.
 * Client thread only, apart from the process reader.
 */
public final class SteamLink {
	public enum Role { NONE, HOST, FRIENDS, GUEST }

	public record Friend(String id, String name) { }

	/** Skyrim Together's usual server port: the host's server listens there, a guest's game connects there. */
	public static final int TOGETHER_PORT = 10578;
	private static final String RESOURCE = "/skycraft/SkyCraftLink.exe";
	// Steam IDs (Valve's DLL prints ours too) stay out of the log, which may end up in a report.
	private static final java.util.regex.Pattern STEAM_ID = java.util.regex.Pattern.compile("7656119\\d{10}");

	private static Process process;
	private static long generation;
	private static int version;
	private static Role role = Role.NONE;
	private static boolean running;
	private static boolean reported;
	private static String status = "";
	private static List<Friend> friends = List.of();
	private static boolean friendsKnown;
	private static final List<String> guests = new ArrayList<>();
	private static int togetherPort;
	private static String joined;       // guest: the local address Minecraft joined through the link
	private static String pendingNote;  // guest: said once the player is in the friend's world

	private SteamLink() { }

	/** Changes with everything the menu shows. */
	public static int version() { return version; }
	public static Role role() { return role; }
	public static boolean running() { return running; }
	public static String status() { return status; }
	public static List<Friend> friends() { return friends; }
	public static boolean friendsKnown() { return friendsKnown; }
	public static List<String> guests() { return List.copyOf(guests); }
	public static int togetherPort() { return togetherPort; }
	public static String joinedAddress() { return joined; }

	/** Our open world for friends in Steam: Minecraft's LAN port, Skyrim Together's server port. */
	public static void host(Minecraft minecraft, int lanPort) {
		start(minecraft, Role.HOST, List.of("host", "--minecraft-port", Integer.toString(lanPort), "--together-port", Integer.toString(TOGETHER_PORT)));
	}

	/** Which friends have their world open through SkyCraft Link (the program lists them and ends). */
	public static void findFriends(Minecraft minecraft) {
		friends = List.of();
		friendsKnown = false;
		start(minecraft, Role.FRIENDS, List.of("friends"));
	}

	/** Into a friend's world (a Steam ID from findFriends, or "auto"); Minecraft follows once the link is up. */
	public static void join(Minecraft minecraft, String friendId) {
		start(minecraft, Role.GUEST, List.of("join", friendId, "--minecraft-port", "25565", "--together-port", Integer.toString(TOGETHER_PORT)));
	}

	public static void stop() {
		Process ending = process;
		process = null;
		generation++;
		role = Role.NONE;
		running = false;
		joined = null;
		pendingNote = null;
		togetherPort = 0;
		guests.clear();
		changed();
		if (ending != null) end(ending);
	}

	/** Every client tick. */
	public static void tick(Minecraft minecraft) {
		if (role == Role.GUEST && joined != null && minecraft.level != null) {
			if (!joined.equals(MirrorWorld.friendAddress(minecraft))) {
				stop();  // back in our own world (left, or the friend's world closed): the link is done
				return;
			}
			if (pendingNote != null && minecraft.player != null) {
				minecraft.gui.hud.getChat().addClientSystemMessage(Component.literal(pendingNote));
				pendingNote = null;
			}
		}
		if (role == Role.HOST && running && (minecraft.getSingleplayerServer() == null || !minecraft.getSingleplayerServer().isPublished())) {
			stop();  // our world closed: nothing left to carry
		}
	}

	private static void start(Minecraft minecraft, Role newRole, List<String> args) {
		stop();
		role = newRole;
		reported = false;
		status = "Запускаю SkyCraft Link…";
		Path exe;
		try {
			exe = executable(minecraft);
		} catch (IOException e) {
			fail("SkyCraft Link недоступен: " + e.getMessage());
			return;
		}
		List<String> command = new ArrayList<>();
		command.add(exe.toString());
		command.addAll(args);
		// Ends with this game even if it crashes; "stop" on stdin lets it say goodbye first.
		command.addAll(List.of("--parent-pid", Long.toString(ProcessHandle.current().pid()), "--stdin-control"));
		String extra = System.getProperty("skycraft.linkExtraArgs", "");  // tests: "--loopback <port>" instead of Steam
		if (!extra.isBlank()) command.addAll(Arrays.asList(extra.trim().split("\\s+")));
		Process started;
		try {
			started = new ProcessBuilder(command).directory(exe.getParent().toFile()).redirectErrorStream(true).start();
		} catch (IOException e) {
			fail("Не удалось запустить SkyCraft Link: " + e.getMessage());
			return;
		}
		process = started;
		running = true;
		long attempt = ++generation;
		changed();
		SkyCraft.LOG.info("SkyCraft Link: started {}", masked(String.join(" ", command)));
		Thread.ofVirtual().name("SkyCraft Link").start(() -> read(minecraft, started, attempt));
	}

	private static void read(Minecraft minecraft, Process from, long attempt) {
		try (var reader = new BufferedReader(new InputStreamReader(from.getInputStream(), StandardCharsets.UTF_8))) {
			for (String line; (line = reader.readLine()) != null;) {
				SkyCraft.LOG.info("SkyCraft Link: {}", masked(line));
				JsonObject event;
				try {
					event = JsonParser.parseString(line).getAsJsonObject();
				} catch (RuntimeException e) {
					continue;  // not one of its events
				}
				minecraft.execute(() -> { if (attempt == generation) onEvent(minecraft, event); });
			}
		} catch (IOException e) {
			SkyCraft.LOG.info("SkyCraft Link: output closed: {}", e.toString());
		}
		int code;
		try { code = from.waitFor(); } catch (InterruptedException e) { code = -1; Thread.currentThread().interrupt(); }
		int exit = code;
		minecraft.execute(() -> {
			if (attempt != generation) return;
			process = null;
			running = false;
			if (exit != 0 && !reported) status = "SkyCraft Link завершился с кодом " + exit + ".";
			changed();
		});
	}

	private static void onEvent(Minecraft minecraft, JsonObject event) {
		switch (text(event, "event")) {
			case "steam" -> status = "Steam: " + text(event, "name") + ".";
			case "hosting" -> {
				status = "Мир открыт друзьям в Steam: им нужно «SkyCraft: сеть» → «Steam» → «Найти друзей».";
				note(minecraft, "SkyCraft: мир открыт друзьям в Steam. Для Skyrim Together запустите у себя его сервер (порт "
					+ TOGETHER_PORT + "): друзья подключатся к нему через Steam.");
			}
			case "peer" -> {
				String name = text(event, "name");
				switch (text(event, "state")) {
					case "joined" -> { guests.add(name); note(minecraft, "SkyCraft: " + name + " подключился через Steam."); }
					case "left" -> { guests.remove(name); note(minecraft, "SkyCraft: " + name + " отключился."); }
					case "refused" -> note(minecraft, "SkyCraft: у " + name + " другая версия SkyCraft Link. Обновите мод у обоих.");
					default -> { }
				}
			}
			case "friends" -> {
				List<Friend> found = new ArrayList<>();
				if (event.get("hosts") instanceof JsonArray hosts) {
					for (JsonElement host : hosts) {
						if (host instanceof JsonObject o && !text(o, "id").isEmpty()) found.add(new Friend(text(o, "id"), text(o, "name")));
					}
				}
				friends = List.copyOf(found);
				friendsKnown = true;
				status = found.isEmpty() ? "Никто из друзей в Steam сейчас не открыл мир через SkyCraft."
					: "Друзей с открытым миром: " + found.size() + ".";
			}
			case "joining" -> status = "Вхожу в лобби " + text(event, "host") + "…";
			case "joined" -> status = "Я в лобби друга, жду ответа его SkyCraft Link…";
			case "connected" -> status = "Связь с " + text(event, "host") + " через Steam есть.";
			case "listening" -> {
				int port = event.get("port") instanceof JsonElement p && p.isJsonPrimitive() ? p.getAsInt() : 0;
				if (port <= 0) break;
				if ("minecraft".equals(text(event, "service"))) {
					joined = "127.0.0.1:" + port;
					status = "Вхожу в мир друга через Steam…";
					MirrorWorld.joinFriend(minecraft, joined);
				} else if ("together".equals(text(event, "service"))) {
					togetherPort = port;
					pendingNote = "SkyCraft: в Skyrim Together подключайтесь к серверу 127.0.0.1:" + port + " (это друг через Steam).";
				}
			}
			case "refused", "warning" -> status = text(event, "text");
			case "lost", "error" -> {
				status = text(event, "text");
				reported = true;
				note(minecraft, "SkyCraft: " + status);
			}
			default -> { }
		}
		changed();
	}

	/** The program shipped inside the mod, unpacked once per version into the game folder. */
	private static Path executable(Minecraft minecraft) throws IOException {
		String override = System.getProperty("skycraft.linkExe", "");
		if (!override.isBlank()) return Path.of(override);
		byte[] bytes;
		try (InputStream in = SteamLink.class.getResourceAsStream(RESOURCE)) {
			if (in == null) throw new IOException("в этой сборке мода его нет");
			bytes = in.readAllBytes();
		}
		String hash;
		try {
			hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)).substring(0, 16);
		} catch (NoSuchAlgorithmException e) {
			throw new IOException(e);
		}
		Path folder = minecraft.gameDirectory.toPath().resolve("skycraft").resolve("link");
		Path exe = folder.resolve("SkyCraftLink-" + hash + ".exe");
		if (!Files.isRegularFile(exe) || !Arrays.equals(Files.readAllBytes(exe), bytes)) {
			Files.createDirectories(folder);
			Path part = Files.createTempFile(folder, "SkyCraftLink", ".part");
			Files.write(part, bytes);
			Files.move(part, exe, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		}
		try (var old = Files.newDirectoryStream(folder, "SkyCraftLink-*")) {
			for (Path file : old) {
				if (!file.equals(exe)) {
					try { Files.delete(file); } catch (IOException e) { /* an earlier version still running */ }
				}
			}
		}
		return exe;
	}

	private static void end(Process ending) {
		// Asked to stop, it says goodbye to the other side; if it's still there after two seconds, it's ended.
		Thread.ofVirtual().name("SkyCraft Link stop").start(() -> {
			try (OutputStream in = ending.getOutputStream()) {
				in.write("stop\n".getBytes(StandardCharsets.US_ASCII));
			} catch (IOException e) { /* already gone */ }
			try {
				if (!ending.waitFor(2, TimeUnit.SECONDS)) ending.destroyForcibly();
			} catch (InterruptedException e) {
				ending.destroyForcibly();
				Thread.currentThread().interrupt();
			}
		});
	}

	private static void fail(String text) {
		status = text;
		reported = true;
		running = false;
		changed();
		SkyCraft.LOG.warn("SkyCraft Link: {}", text);
	}

	private static void note(Minecraft minecraft, String text) {
		SkyCraft.LOG.info("SkyCraft Link: {}", text);
		if (minecraft.player != null) minecraft.gui.hud.getChat().addClientSystemMessage(Component.literal(text));
	}

	private static String text(JsonObject event, String key) {
		return event.get(key) instanceof JsonElement value && value.isJsonPrimitive() ? value.getAsString() : "";
	}

	private static String masked(String text) { return STEAM_ID.matcher(text).replaceAll("7656119…"); }

	private static void changed() { version++; }
}
