package dev.skycraft.client;

import dev.skycraft.SkyCraft;
import dev.skycraft.link.SkyLink;
import dev.skycraft.network.JsonLineTrace;
import dev.skycraft.network.NetworkConfig;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/** Logs transport metadata locally; disk work never runs on Minecraft's networking thread. */
public final class NetworkDiagnostics {
	private static volatile JsonLineTrace trace;
	private static Path gameDirectory;
	private static long nextSample;
	private static final AtomicLong ids = new AtomicLong();
	private NetworkDiagnostics() { }

	public static void initialize(Minecraft minecraft) {
		gameDirectory = minecraft.gameDirectory.toPath();
		if (!"false".equalsIgnoreCase(System.getProperty("skycraft.networkTrace", "true"))) start("AUTO", "auto");
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, context) -> {
			var debug = net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal("debug");
			debug.then(net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal("start")
				.then(net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument("run_id", com.mojang.brigadier.arguments.StringArgumentType.word())
					.then(net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument("role", com.mojang.brigadier.arguments.StringArgumentType.word())
						.suggests((c, b) -> net.minecraft.commands.SharedSuggestionProvider.suggest(java.util.List.of("host", "client"), b))
						.executes(c -> {
							try {
								start(com.mojang.brigadier.arguments.StringArgumentType.getString(c, "run_id"),
									com.mojang.brigadier.arguments.StringArgumentType.getString(c, "role"));
								status(minecraft);
							} catch (IllegalArgumentException e) { note(minecraft, "ID: 1–64 латинских букв/цифр, '-' или '_'; роль host/client."); }
							return 1;
						}))));
			debug.then(net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal("mark")
				.then(net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument("label", com.mojang.brigadier.arguments.StringArgumentType.greedyString())
					.executes(c -> {
						event("marker", Map.of("marker", com.mojang.brigadier.arguments.StringArgumentType.getString(c, "label")));
						note(minecraft, "Метка записана, если логгер включён; /skycraft debug status показывает состояние."); return 1;
					})));
			debug.then(net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal("status").executes(c -> { status(minecraft); return 1; }));
			debug.then(net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal("stop").executes(c -> {
				stop(); note(minecraft, "Сетевой логгер остановлен. Подождите 2 секунды перед сбором отчёта."); return 1;
			}));
			dispatcher.register(net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal("skycraft").then(debug));
		});
	}

	public static void start(String runId, String role) {
		if (!runId.matches("[A-Za-z0-9_-]{1,64}") || !java.util.Set.of("host", "client", "auto").contains(role)) throw new IllegalArgumentException();
		JsonLineTrace old = trace;
		String stamp = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC).format(Instant.now());
		trace = new JsonLineTrace(gameDirectory.resolve("logs/skycraft-network/" + runId + "-" + role + "-" + stamp + "-" + UUID.randomUUID().toString().substring(0, 8) + ".jsonl"), runId, role);
		if (old != null) old.close();
		nextSample = 0;
		event("environment", Map.of("mc_version", version("minecraft"), "mod_version", version("skycraft"),
			"fabric_version", version("fabricloader"), "java_version", System.getProperty("java.version"),
			"os_name", System.getProperty("os.name"), "os_version", System.getProperty("os.version")));
		try { config(NetworkClient.config(Minecraft.getInstance())); }
		catch (Exception e) { event("config_error", Map.of("exception_type", e.getClass().getSimpleName())); }
	}

	private static String version(String mod) {
		return FabricLoader.getInstance().getModContainer(mod).map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("unknown");
	}

	public static long nextId() { return ids.incrementAndGet(); }
	public static void event(String name, Map<String, ?> fields) {
		JsonLineTrace current = trace;
		if (current != null) current.event(name, fields);
	}
	public static void config(NetworkConfig config) {
		Map<String, Object> fields = new LinkedHashMap<>();
		fields.put("mode", config.mode().name()); fields.put("timeout_ms", config.timeoutMillis());
		fields.put("host_port", config.hostPort()); fields.put("proxy_credentials_set", !config.username().isEmpty() || !config.password().isEmpty());
		if (config.proxy() != null) { fields.put("proxy_host", config.proxy().host()); fields.put("proxy_port", config.proxy().port()); }
		event("config", fields);
	}
	public static void channel(String name, long id, SocketAddress remote, SocketAddress local, String receiving, Throwable error) {
		Map<String, Object> fields = new LinkedHashMap<>();
		fields.put("connection_id", id); fields.put("receiving", receiving);
		fields.put("attempt_id", NetworkClient.activeAttempt());
		address(fields, "remote", remote); address(fields, "local", local);
		if (error != null) fields.put("exception_type", error.getClass().getSimpleName());
		event(name, fields);
	}
	public static void dial(long attempt, dev.skycraft.network.SocketDialer.DialEvent detail) {
		Map<String, Object> fields = new LinkedHashMap<>();
		fields.put("attempt_id", attempt); fields.put("phase", detail.phase()); fields.put("elapsed_ms", detail.elapsedMillis());
		fields.put("peer_host", detail.peerHost()); fields.put("peer_port", detail.peerPort());
		if (detail.statusCode() != null) fields.put("status_code", detail.statusCode());
		if (detail.exceptionType() != null) fields.put("exception_type", detail.exceptionType());
		event("dial_phase", fields);
	}
	private static void address(Map<String, Object> fields, String prefix, SocketAddress address) {
		if (address instanceof InetSocketAddress socket && socket.getAddress() != null) {
			fields.put(prefix + "_address", socket.getAddress().getHostAddress()); fields.put(prefix + "_port", socket.getPort());
		}
	}
	public static void tick(Minecraft minecraft, long upload, long download) {
		if (trace == null || System.nanoTime() < nextSample) return;
		nextSample = System.nanoTime() + 5_000_000_000L;
		Map<String, Object> fields = new LinkedHashMap<>();
		fields.put("skyrim_linked", SkyLink.active());
		var server = minecraft.getSingleplayerServer();
		fields.put("published", server != null && server.isPublished());
		if (server != null && server.isPublished()) fields.put("host_port", server.getPort());
		if (minecraft.getConnection() != null && minecraft.player != null) {
			fields.put("player_count", minecraft.getConnection().getOnlinePlayers().size());
			var info = minecraft.getConnection().getPlayerInfo(minecraft.player.getUUID());
			if (info != null && !minecraft.isLocalServer()) fields.put("ping_ms", info.getLatency());
		}
		if (upload >= 0) { fields.put("upload_bytes", upload); fields.put("download_bytes", download); }
		event("sample", fields);
	}
	public static void position(String reason, Minecraft minecraft) {
		Map<String, Object> fields = new LinkedHashMap<>();
		fields.put("reason", reason);
		fields.put("skyrim_linked", SkyLink.active());
		if (minecraft.player != null) {
			fields.put("mc_x", minecraft.player.getX()); fields.put("mc_y", minecraft.player.getY()); fields.put("mc_z", minecraft.player.getZ());
		}
		if (SkyLink.active()) {
			var sky = SkyClient.sky();
			fields.put("sky_x", sky.x); fields.put("sky_y", sky.y); fields.put("sky_z", sky.z);
			fields.put("teleport_seq", sky.teleportSeq);
		}
		event("position", fields);
	}
	public static void stop() { JsonLineTrace old = trace; trace = null; if (old != null) old.close(); }
	public static String traceFileName() { JsonLineTrace current = trace; return current == null ? "" : current.file().getFileName().toString(); }
	public static void shutdown() {
		JsonLineTrace old = trace; stop();
		if (old != null) try { old.awaitClosed(Duration.ofSeconds(2)); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
	}
	private static void status(Minecraft minecraft) {
		JsonLineTrace current = trace;
		note(minecraft, current == null ? "Сетевой логгер выключен." : "Лог: " + current.file() + "; пропущено событий: "
			+ current.dropped() + (current.errorType() == null ? "" : "; ошибка записи: " + current.errorType()));
	}
	private static void note(Minecraft minecraft, String message) {
		minecraft.gui.hud.getChat().addClientSystemMessage(Component.literal(message));
		SkyCraft.LOG.info("SkyCraft diagnostics: {}", message);
	}
}
