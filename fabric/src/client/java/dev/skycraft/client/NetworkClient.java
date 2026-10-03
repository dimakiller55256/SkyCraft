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

	private NetworkClient() { }

	public static NetworkConfig config(Minecraft minecraft) throws IOException {
		return NetworkConfig.load(minecraft.gameDirectory.toPath().resolve("config/skycraft.properties"));
	}

	public static void register() {
		ClientLifecycleEvents.CLIENT_STOPPING.register(minecraft -> cancel());
		ClientPlayConnectionEvents.DISCONNECT.register((handler, minecraft) -> cancel());
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

	private static void host(Minecraft minecraft, Integer portOverride) {
		try {
			NetworkConfig config = config(minecraft);
			var server = minecraft.getSingleplayerServer();
			if (server == null) { note(minecraft, "Хост можно открыть только в своём мире."); return; }
			if (server.isPublished()) { note(minecraft, "Мир уже открыт на порту " + server.getPort() + "."); return; }
			int port = portOverride == null ? config.hostPort() : portOverride;
			if (!server.publishServer(net.minecraft.server.MinecraftServer.MultiplayerScope.LAN, false, port)) {
				note(minecraft, "Не удалось открыть порт " + port + ". Возможно, он занят."); return;
			}
			DiscordPresence.setHostAddress(config.advertisedAddress().isEmpty() ? null : config.advertisedAddress());
			note(minecraft, "Мир открыт на порту " + port + ". Друг подключается через /join адрес:" + port
				+ ". Для Discord задайте доступный другу адрес: /skycraft address адрес:" + port + ".");
		} catch (IOException | IllegalArgumentException e) { note(minecraft, "Настройки сети: " + e.getMessage()); }
	}

	public static void connect(Minecraft minecraft, TitleScreen title, Endpoint endpoint) {
		cancel();
		failure = null;
		try {
			NetworkConfig config = config(minecraft);
			if (config.mode() == NetworkConfig.Mode.DIRECT) {
				start(minecraft, title, endpoint.authority(), endpoint.authority());
				return;
			}
			long attempt = generation;
			dialer = new SocketDialer();
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
						if (attempt == generation) failure = describe(e);
					}));
					minecraft.execute(() -> {
						if (attempt != generation || minecraft.gui.screen() != screen) { ready.close(); return; }
						bridge = ready;
						target = endpoint;
						bridgePort = ready.port();
						waiting = null;
						start(minecraft, title, "127.0.0.1:" + bridgePort, endpoint.authority());
					});
				} catch (IOException | RuntimeException e) {
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
		if (waiting != null && minecraft.gui.screen() != waiting) {
			cancel(); // A different menu/world cannot be replaced by a late network callback.
		}
	}

	public static String lastFailure() { return failure; }

	public static void cancel() {
		generation++;
		waiting = null;
		target = null;
		bridgePort = 0;
		if (bridge != null) { bridge.close(); bridge = null; }
		if (dialer != null) { dialer.close(); dialer = null; }
	}

	private static void check(Minecraft minecraft, String address) {
		try {
			if (checking) { note(minecraft, "Проверка соединения уже выполняется."); return; }
			Endpoint endpoint = Endpoint.parse(address);
			NetworkConfig config = config(minecraft);
			checking = true;
			note(minecraft, "Проверяю TCP до " + endpoint + " через " + config.mode() + "...");
			Thread.ofVirtual().name("SkyCraft network check").start(() -> {
				long started = System.nanoTime();
				String result;
				try (SocketDialer probe = new SocketDialer(); var socket = probe.connect(endpoint, config)) {
					result = "TCP до " + endpoint + " доступен: " + (System.nanoTime() - started) / 1_000_000L
						+ " мс. Вход в Minecraft и синхронизация ещё не проверены.";
				} catch (IOException | RuntimeException e) { result = "TCP до " + endpoint + ": " + describe(e); }
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
