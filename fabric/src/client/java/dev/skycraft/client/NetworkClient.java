package dev.skycraft.client;

import dev.skycraft.SkyCraft;
import dev.skycraft.network.Endpoint;
import dev.skycraft.network.LoopbackBridge;
import dev.skycraft.network.NetworkConfig;
import dev.skycraft.network.SocketDialer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.GenericWaitingScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.chat.Component;

/** Client-thread lifecycle, with blocking network operations confined to virtual threads. */
public final class NetworkClient {
	private static long generation;
	private static SocketDialer dialer;
	private static LoopbackBridge bridge;
	private static volatile Endpoint target;
	private static volatile int bridgePort;
	private static GenericWaitingScreen waiting;
	private static volatile String failure;
	private static boolean checking;
	private static volatile long activeAttempt;
	private static long connectStarted;
	private static boolean joined;
	private static NetworkConfig testConfig;
	public static long activeAttempt() { return activeAttempt; }

	private NetworkClient() { }

	public static NetworkConfig config(Minecraft minecraft) throws IOException {
		if (testConfig != null) return testConfig;
		return NetworkConfig.load(minecraft.gameDirectory.toPath().resolve("config/skycraft.properties"));
	}

	/** A leased test session can select transport without editing the user's properties. */
	public static void testConfig(NetworkConfig value) { testConfig = value; }

	public static void register() {
		ClientLifecycleEvents.CLIENT_STOPPING.register(minecraft -> { cancel(); NetworkDiagnostics.shutdown(); });
		ClientPlayConnectionEvents.JOIN.register((handler, sender, minecraft) -> {
			joined = true;
			NetworkDiagnostics.position("world_join", minecraft);
			NetworkDiagnostics.event("world_join", java.util.Map.of("attempt_id", activeAttempt, "result", minecraft.isLocalServer() ? "local" : "remote",
				"elapsed_ms", connectStarted == 0 ? 0 : (System.nanoTime() - connectStarted) / 1_000_000L));
		});
		ClientPlayConnectionEvents.DISCONNECT.register((handler, minecraft) -> {
			NetworkDiagnostics.event("world_disconnect", java.util.Map.of("attempt_id", activeAttempt, "result", joined ? "joined" : "pending"));
			cancel();
		});
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, context) -> {
			var commands = net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal("skycraft");
			commands.then(net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal("host")
				.executes(c -> { host(Minecraft.getInstance(), null); return 1; })
				.then(net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument("port", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1, 65535))
					.executes(c -> { host(Minecraft.getInstance(), com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(c, "port")); return 1; })));
			commands.then(net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal("address")
				.then(net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument("address", com.mojang.brigadier.arguments.StringArgumentType.greedyString())
					.executes(c -> {
						try {
							var minecraft = Minecraft.getInstance();
							if (minecraft.getSingleplayerServer() == null || !minecraft.getSingleplayerServer().isPublished()) {
								throw new IllegalArgumentException("Сначала откройте свой мир: /skycraft host.");
							}
							String address = Endpoint.parse(com.mojang.brigadier.arguments.StringArgumentType.getString(c, "address")).authority();
							DiscordPresence.setHostAddress(address);
							note(minecraft, "Адрес приглашения: " + address + ". Доступность проверяется на стороне друга.");
						} catch (IllegalArgumentException e) { note(Minecraft.getInstance(), e.getMessage()); }
						return 1;
					})));
			commands.then(net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal("netcheck")
				.then(net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument("address", com.mojang.brigadier.arguments.StringArgumentType.greedyString())
					.executes(c -> { check(Minecraft.getInstance(), com.mojang.brigadier.arguments.StringArgumentType.getString(c, "address")); return 1; })));
			dispatcher.register(commands);
		});
	}

	public static void host(Minecraft minecraft, Integer portOverride) {
		try {
			NetworkConfig config = config(minecraft);
			var server = minecraft.getSingleplayerServer();
			if (server == null) { note(minecraft, "Хост можно открыть только в своём мире."); return; }
			if (server.isPublished()) { note(minecraft, "Мир уже открыт на порту " + server.getPort() + "."); return; }
			int port = portOverride == null ? config.hostPort() : portOverride;
			if (!server.publishServer(net.minecraft.server.MinecraftServer.MultiplayerScope.LAN, false, port)) {
				NetworkDiagnostics.event("host_failed", java.util.Map.of("host_port", port));
				note(minecraft, "Не удалось открыть порт " + port + ". Возможно, он занят."); return;
			}
			DiscordPresence.setHostAddress(config.advertisedAddress().isEmpty() ? null : config.advertisedAddress());
			NetworkDiagnostics.event("host_open", java.util.Map.of("host_port", port));
			note(minecraft, "Мир открыт на порту " + port + ". Друг подключается через /join адрес:" + port
				+ ". Для Discord задайте доступный другу адрес: /skycraft address адрес:" + port + ".");
		} catch (IOException | IllegalArgumentException e) { note(minecraft, "Настройки сети: " + e.getMessage()); }
	}

	public static void connect(Minecraft minecraft, TitleScreen title, Endpoint endpoint) {
		cancel();
		failure = null;
		activeAttempt = NetworkDiagnostics.nextId();
		connectStarted = System.nanoTime(); joined = false;
		long traceAttempt = activeAttempt;
		NetworkDiagnostics.event("join_requested", java.util.Map.of("attempt_id", traceAttempt, "target_host", endpoint.host(), "target_port", endpoint.port()));
		try {
			NetworkConfig config = config(minecraft);
			NetworkDiagnostics.config(config);
			if (config.mode() == NetworkConfig.Mode.DIRECT) {
				start(minecraft, title, endpoint.authority(), endpoint.authority());
				return;
			}
			long attempt = generation;
			dialer = new SocketDialer(event -> NetworkDiagnostics.dial(traceAttempt, event));
			SocketDialer attemptDialer = dialer;
			waiting = GenericWaitingScreen.createWaiting(Component.literal("SkyCraft: подключение"),
				Component.literal(endpoint.authority() + " через " + config.mode()),
				() -> MirrorWorld.connectionFailed(minecraft, "Подключение отменено."));
			GenericWaitingScreen screen = waiting;
			minecraft.gui.setScreen(screen);
			SkyCraft.LOG.info("SkyCraft network: connecting to {} using {}", endpoint, config);
			Thread.ofVirtual().name("SkyCraft proxy dial").start(() -> {
				try {
					var upstream = attemptDialer.connect(endpoint, config);
					LoopbackBridge ready = new LoopbackBridge(upstream, e -> minecraft.execute(() -> {
						NetworkDiagnostics.event("bridge_error", java.util.Map.of("attempt_id", traceAttempt, "exception_type", e.getClass().getSimpleName()));
						if (attempt == generation) failure = describe(e);
					}));
					minecraft.execute(() -> {
						if (attempt != generation || minecraft.gui.screen() != screen) { ready.close(); return; }
						bridge = ready;
						target = endpoint;
						bridgePort = ready.port();
						NetworkDiagnostics.event("bridge_ready", java.util.Map.of("attempt_id", traceAttempt, "local_port", bridgePort));
						waiting = null;
						start(minecraft, title, "127.0.0.1:" + bridgePort, endpoint.authority());
					});
				} catch (IOException | RuntimeException e) {
					NetworkDiagnostics.event("join_transport_failed", java.util.Map.of("attempt_id", traceAttempt, "exception_type", e.getClass().getSimpleName()));
					minecraft.execute(() -> {
						if (attempt != generation || minecraft.gui.screen() != screen) return;
						MirrorWorld.connectionFailed(minecraft, describe(e));
					});
				}
			});
		} catch (IOException | IllegalArgumentException e) { MirrorWorld.connectionFailed(minecraft, "Настройки сети: " + e.getMessage()); }
	}

	private static void start(Minecraft minecraft, TitleScreen title, String dialAddress, String displayAddress) {
		ConnectScreen.startConnecting(title, minecraft, ServerAddress.parseString(dialAddress),
			new ServerData("SkyCraft", displayAddress, ServerData.Type.OTHER), false, null);
	}

	/** The game handshake retains the real endpoint rather than advertising the local bridge. */
	public static Endpoint handshakeEndpoint(SocketAddress remote) {
		Endpoint current = target;
		return current != null && remote instanceof InetSocketAddress address && address.getPort() == bridgePort
			&& address.getAddress() != null && address.getAddress().isLoopbackAddress() ? current : null;
	}

	public static void tick(Minecraft minecraft) {
		NetworkDiagnostics.tick(minecraft, bridge == null ? -1 : bridge.uploadBytes(), bridge == null ? -1 : bridge.downloadBytes());
		if (waiting != null && minecraft.gui.screen() != waiting) {
			cancel(); // A different menu/world cannot be replaced by a late network callback.
		}
	}

	public static String lastFailure() { return failure; }

	public static void cancel() {
		if (activeAttempt != 0) NetworkDiagnostics.event("transport_close", java.util.Map.of("attempt_id", activeAttempt,
			"result", joined ? "joined" : "pending", "upload_bytes", bridge == null ? -1 : bridge.uploadBytes(), "download_bytes", bridge == null ? -1 : bridge.downloadBytes()));
		generation++;
		waiting = null;
		target = null;
		bridgePort = 0;
		if (bridge != null) { bridge.close(); bridge = null; }
		if (dialer != null) { dialer.close(); dialer = null; }
		activeAttempt = 0; connectStarted = 0; joined = false;
	}

	private static void check(Minecraft minecraft, String address) {
		try {
			if (checking) { note(minecraft, "Проверка соединения уже выполняется."); return; }
			Endpoint endpoint = Endpoint.parse(address);
			NetworkConfig config = config(minecraft);
			checking = true;
			long probeId = NetworkDiagnostics.nextId();
			NetworkDiagnostics.config(config);
			NetworkDiagnostics.event("probe_started", java.util.Map.of("attempt_id", probeId, "target_host", endpoint.host(), "target_port", endpoint.port()));
			note(minecraft, "Проверяю TCP до " + endpoint + " через " + config.mode() + "...");
			Thread.ofVirtual().name("SkyCraft network check").start(() -> {
				long started = System.nanoTime();
				String result;
				try (SocketDialer probe = new SocketDialer(event -> NetworkDiagnostics.dial(probeId, event)); var socket = probe.connect(endpoint, config)) {
					NetworkDiagnostics.event("probe_result", java.util.Map.of("attempt_id", probeId, "result", "tcp_ready", "elapsed_ms", (System.nanoTime() - started) / 1_000_000L));
					result = "TCP до " + endpoint + " доступен: " + (System.nanoTime() - started) / 1_000_000L
						+ " мс. Вход в Minecraft и синхронизация ещё не проверены.";
				} catch (IOException | RuntimeException e) {
					NetworkDiagnostics.event("probe_result", java.util.Map.of("attempt_id", probeId, "result", "failed", "exception_type", e.getClass().getSimpleName()));
					result = "TCP до " + endpoint + ": " + describe(e);
				}
				String message = result;
				minecraft.execute(() -> { checking = false; note(minecraft, message); });
			});
		} catch (IOException | IllegalArgumentException e) { note(minecraft, "Настройки сети: " + e.getMessage()); }
	}

	private static String describe(Exception e) {
		if (e instanceof java.net.UnknownHostException) return "DNS не разрешил адрес сервера или прокси.";
		if (e instanceof java.net.SocketTimeoutException) return "Истекло время подключения или ответа прокси.";
		if (e instanceof java.net.ConnectException) return "Соединение отклонено: проверьте хост, порт, прокси и маршрутизацию.";
		return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
	}

	private static void note(Minecraft minecraft, String message) {
		SkyCraft.LOG.info("SkyCraft network: {}", message);
		minecraft.gui.hud.getChat().addClientSystemMessage(Component.literal(message));
	}
}
