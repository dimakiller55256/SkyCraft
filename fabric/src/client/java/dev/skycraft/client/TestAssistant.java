package dev.skycraft.client;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import dev.skycraft.link.SkyLink;
import dev.skycraft.network.AssistantProtocol;
import dev.skycraft.network.Endpoint;
import dev.skycraft.network.NetworkConfig;
import dev.skycraft.network.SocketDialer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.SocketTimeoutException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.minecraft.client.Minecraft;

/** Commands arrive only while the local desktop assistant renews a short-lived lease. */
public final class TestAssistant {
	private static final Gson JSON = new Gson();
	private static Path directory;
	private static String session;
	private static long lastId, nextPoll;
	private static String action = "", result = "idle", error = "";
	private static Map<String, Object> detail = Map.of();
	private static CompletableFuture<Map<String, Object>> pending;
	private TestAssistant() { }
	public static void initialize() {
		directory = Minecraft.getInstance().gameDirectory.toPath().resolve("config/skycraft-test");
		ClientTickEvents.END_CLIENT_TICK.register(TestAssistant::tick);
		ClientLifecycleEvents.CLIENT_STOPPING.register(mc -> NetworkClient.testConfig(null));
	}
	private static JsonObject read(String file) throws IOException {
		Path path = directory.resolve(file);
		if (Files.size(path) > 16384) throw new IOException("oversize");
		return JSON.fromJson(Files.readString(path), JsonObject.class);
	}
	private static void tick(Minecraft mc) {
		if (System.nanoTime() < nextPoll) return;
		nextPoll = System.nanoTime() + 500_000_000L;
		String lease;
		try { lease = AssistantProtocol.session(read("lease.json"), Instant.now()); }
		catch (Exception e) {
			if (session != null) endLease(mc);
			return;
		}
		if (!lease.equals(session)) {
			if (session != null) endLease(mc);
			session = lease; lastId = 0; result = "idle"; error = ""; detail = Map.of();
		}
		// A tester may keep the desktop assistant foreground while Skyrim is paused.
		// Ordinary opening otherwise waits for an active Skyrim frame.
		if (SkyClient.tookOver() && mc.level == null) MirrorWorld.openWhenReady(mc);
		if (pending != null && pending.isDone()) {
			try { detail = pending.join(); result = Boolean.FALSE.equals(detail.get("success")) ? "failed" : "ok"; }
			catch (Exception e) { result = "failed"; error = e.getClass().getSimpleName(); }
			pending = null;
		}
		if (pending == null) {
			try {
				JsonObject request = read("request.json");
				long id = AssistantProtocol.request(request, session);
				if (id > lastId) {
					lastId = id; action = request.get("action").getAsString(); result = "ok"; error = ""; detail = Map.of();
					try { execute(mc, request); }
					catch (Exception e) { result = "failed"; error = dev.skycraft.network.DiagnosticText.safe(e.getClass().getSimpleName() + ": " + e.getMessage()); }
				}
			} catch (Exception ignored) { /* Missing, malformed and foreign requests are not executed. */ }
		}
		writeState(mc);
	}
	private static void endLease(Minecraft mc) {
		NetworkClient.testConfig(null);
		dev.skycraft.net.SkyNet.testRunId = "";
		NetworkDiagnostics.event("assistant_lease_end", Map.of());
		NetworkDiagnostics.stop();
		if (pending != null) pending.cancel(true);
		pending = null; session = null;
	}
	private static void execute(Minecraft mc, JsonObject request) throws Exception {
		switch (action) {
			case "start" -> {
				String role = request.get("role").getAsString();
				if (!java.util.Set.of("host", "client").contains(role)) throw new IllegalArgumentException("role");
				NetworkDiagnostics.start(AssistantProtocol.runId(request.get("runId").getAsString()), role);
				dev.skycraft.net.SkyNet.testRunId = role.equals("host") ? request.get("runId").getAsString() : "";
				NetworkDiagnostics.event("assistant_start", Map.of("session", session));
			}
			case "host" -> {
				if (!mc.isLocalServer() || mc.player == null) throw new IllegalStateException("own world required");
				int port = request.get("port").getAsInt();
				if (port < 1 || port > 65535) throw new IllegalArgumentException("port");
				var authentication = dev.skycraft.network.HostAuthentication.requested(
					request.has("authentication") ? request.get("authentication").getAsString() : null,
					request.has("privateNetwork") && request.get("privateNetwork").getAsBoolean());
				NetworkClient.host(mc, port, authentication);
				if (!mc.getSingleplayerServer().isPublished()) throw new IOException("host failed");
				if (mc.getSingleplayerServer().usesAuthentication() != (authentication == dev.skycraft.network.HostAuthentication.ONLINE)) {
					throw new IOException("Мир уже открыт с другим режимом входа. Перезапустите Skyrim/Minecraft и повторите с нужным режимом хоста.");
				}
			}
			case "probe" -> {
				Endpoint endpoint = Endpoint.parse(request.get("target").getAsString());
				NetworkConfig config = NetworkClient.config(mc);
				result = "running";
				pending = CompletableFuture.supplyAsync(() -> probe(endpoint, config), task -> Thread.ofVirtual().start(task));
			}
			case "transport" -> {
				result = "running";
				pending = CompletableFuture.supplyAsync(TestAssistant::transportCheck, task -> Thread.ofVirtual().start(task));
			}
			case "join" -> {
				if (mc.player == null || mc.level == null) throw new IllegalStateException("world required");
				MirrorWorld.joinFriend(mc, Endpoint.parse(request.get("target").getAsString()).authority());
				detail = Map.of("joinRequest", MirrorWorld.joinRequest());
			}
			case "leave" -> MirrorWorld.leaveFriend(mc);
			case "items-world" -> MirrorWorld.itemTestWorld(mc);
			case "items-profile" -> {
				if(mc.getSingleplayerServer()==null||!mc.getSingleplayerServer().getWorldData().getLevelName().equals("SkyCraft-Items-Test")||!SkyClient.positionReady())throw new IllegalStateException("ready local item test world required");
				String control=java.util.UUID.randomUUID().toString();SkyLink.beginItemTestProfile(control);detail=Map.of("control",control);
			}
			case "configure" -> {
				var props = new java.util.Properties();
				props.setProperty("network.mode", request.get("mode").getAsString());
				if (request.has("proxy")) props.setProperty("network.proxy", request.get("proxy").getAsString());
				props.setProperty("network.timeoutMillis", "10000");
				NetworkConfig selected = NetworkConfig.fromProperties(props);
				// Reuse existing credentials only for exactly the same explicit proxy.
				NetworkConfig current = NetworkConfig.load(mc.gameDirectory.toPath().resolve("config/skycraft.properties"));
				if (selected.proxy() != null && selected.proxy().equals(current.proxy())) selected = new NetworkConfig("", selected.mode(), selected.proxy(), current.username(), current.password(), 10000, selected.hostPort(), "");
				NetworkClient.testConfig(selected);
				NetworkDiagnostics.config(selected);
			}
			case "restore" -> NetworkClient.testConfig(null);
			case "stop" -> { NetworkClient.testConfig(null); dev.skycraft.net.SkyNet.testRunId = ""; NetworkDiagnostics.stop(); }
			default -> throw new IllegalArgumentException("action");
		}
		NetworkDiagnostics.event("assistant_command", Map.of("command", action, "request_id", lastId, "result", result));
	}
	private static Map<String, Object> probe(Endpoint endpoint, NetworkConfig config) {
		long id = NetworkDiagnostics.nextId(), started = System.nanoTime();
		NetworkDiagnostics.config(config);
		NetworkDiagnostics.event("probe_started", Map.of("attempt_id", id, "target_host", endpoint.host(), "target_port", endpoint.port()));
		Map<String, Object> data = new LinkedHashMap<>();
		data.put("mode", config.mode().name());
		try (var dialer = new SocketDialer(e -> {
			NetworkDiagnostics.dial(id, e);
			if (!e.phase().equals("dial_failed")) data.put("phase", e.phase());
			data.put("peer_host", e.peerHost()); data.put("peer_port", e.peerPort());
			if (e.statusCode() != null) data.put("status_code", e.statusCode());
		}); var socket = dialer.connect(endpoint, config)) {
			data.put("success", true); data.put("result", "tcp_ready");
		} catch (Exception e) { data.put("success", false); data.put("result", "failed"); data.put("exception_type", e.getClass().getSimpleName()); }
		data.put("elapsed_ms", (System.nanoTime() - started) / 1_000_000L); data.put("attempt_id", id);
		NetworkDiagnostics.event("probe_result", data);
		return data;
	}
	/** Real sockets and the release transport; no game/world/config changes. */
	private static Map<String, Object> transportCheck() {
		Map<String, Object> checks = new LinkedHashMap<>();
		try (ServerSocket target = new ServerSocket(); ServerSocket reserved = new ServerSocket()) {
			target.bind(new InetSocketAddress("127.0.0.1", 0)); target.setSoTimeout(400);
			reserved.bind(new InetSocketAddress("127.0.0.1", 0));
			int proxyPort = reserved.getLocalPort();
			// ServerSocket.bind listens; close before use. A rare reuse cannot cause a false PASS: acceptance is checked below.
			reserved.close();
			Endpoint endpoint = new Endpoint("127.0.0.1", target.getLocalPort());
			for (var mode : new NetworkConfig.Mode[] {NetworkConfig.Mode.DIRECT, NetworkConfig.Mode.SOCKS5, NetworkConfig.Mode.HTTP_CONNECT, NetworkConfig.Mode.DIRECT}) {
				boolean expected = mode == NetworkConfig.Mode.DIRECT;
				String label = checks.isEmpty() ? "DIRECT_BASELINE" : expected ? "DIRECT_RESTORED" : mode.name() + "_MISSING_PROXY";
				NetworkConfig config = new NetworkConfig("", mode, expected ? null : new Endpoint("127.0.0.1", proxyPort), "", "", 1000, 25565, "");
				boolean connected = false;
				try (var dialer = new SocketDialer(); var socket = dialer.connect(endpoint, config)) {
					socket.getOutputStream().write(42);
					try (var accepted = target.accept()) { accepted.setSoTimeout(1000); connected = accepted.getInputStream().read() == 42; }
				} catch (IOException e) { if (expected) throw e; }
				boolean bypass = false;
				if (!expected) try (var unexpected = target.accept()) { bypass = true; } catch (SocketTimeoutException correct) { }
				boolean pass = expected ? connected : !connected && !bypass;
				checks.put(label, pass ? "PASS" : "FAIL");
				NetworkDiagnostics.event("transport_self_check", Map.of("check", label, "result", pass ? "PASS" : "FAIL"));
			}
		} catch (Exception e) { checks.put("error", e.getClass().getSimpleName()); }
		boolean success = checks.size() == 4 && checks.values().stream().allMatch("PASS"::equals);
		return Map.of("success", success, "checks", checks);
	}
	private static void writeState(Minecraft mc) {
		Map<String, Object> data = new LinkedHashMap<>();
		data.put("schema", 1); data.put("session", session); data.put("utc", Instant.now().toString());
		data.put("modVersion", net.fabricmc.loader.api.FabricLoader.getInstance().getModContainer("skycraft").orElseThrow().getMetadata().getVersion().getFriendlyString());
		data.put("id", lastId); data.put("action", action); data.put("result", result); data.put("error", error); data.put("detail", detail);
		data.put("traceFile", NetworkDiagnostics.traceFileName());
		data.put("joinRequest", MirrorWorld.joinRequest());
		data.put("lastFailure", NetworkClient.failureDetails());
		data.put("peerSession", NetworkClient.peerSession());
		data.put("positionReady", SkyClient.positionReady());
		data.put("skyrimWorldId", Integer.toHexString(SkyClient.sky().worldId));
		data.put("hostSkyrimWorldId",Integer.toHexString(ActorSyncClient.hostWorldId()));
		data.put("worldCompatible",ActorSyncClient.compatible());
		data.put("shieldRaised",mc.player!=null && mc.player.isBlocking());
		data.put("health",mc.player==null?0:mc.player.getHealth());
		data.put("armor",mc.player==null?0:mc.player.getArmorValue());
		data.put("fallDistance",mc.player==null?0:mc.player.fallDistance);
		data.put("skyrimTakeover",SkyClient.sky().takingOver());
		data.put("collisionKnown",mc.player!=null && dev.skycraft.world.SkyCollision.movementKnown(mc.player.getBoundingBox()));
		data.put("actorHitsReceived",ActorSyncClient.receivedHits);
		data.put("collisionEpoch", SkyClient.sky().collisionEpoch);
		data.put("inputFresh", dev.skycraft.link.SkyLink.inputFresh());
		data.put("skyPosition", Map.of("x", SkyClient.sky().x, "y", SkyClient.sky().y, "z", SkyClient.sky().z));
		data.put("teleportSeq", SkyClient.sky().teleportSeq);
		data.put("world", mc.level == null || mc.player == null ? "none" : mc.isLocalServer() ? "local" : "remote");
		data.put("skyrimLinked", SkyLink.active());
		data.put("skyrimReady", SkyClient.tookOver() && SkyClient.sky().inGame() && !SkyClient.sky().loading());
		var server = mc.getSingleplayerServer();
		data.put("mirrorWorldName",server==null?"":server.getWorldData().getLevelName());
		var exchange=SkyLink.readItemRequest();
		if(exchange!=null)data.put("itemExchange",Map.of("action",exchange.action(),"id",exchange.id(),"count",exchange.count(),"item",exchange.item()));
		data.put("published", server != null && server.isPublished());
		if (server != null) data.put("hostAuthentication", server.usesAuthentication() ? "ONLINE" : "OFFLINE");
		data.put("movementInput", Map.of("forward", mc.options.keyUp.isDown(), "back", mc.options.keyDown.isDown(),
			"left", mc.options.keyLeft.isDown(), "right", mc.options.keyRight.isDown(), "jump", mc.options.keyJump.isDown()));
		try {
			var config = NetworkClient.config(mc); data.put("transportMode", config.mode().name());
			if (config.proxy() != null) data.put("proxyEndpoint", config.proxy().authority());
		} catch (IOException | IllegalArgumentException e) { data.put("transportMode", "INVALID"); }
		if (server != null && server.isPublished()) data.put("hostPort", server.getPort());
		if (mc.player != null) {
			data.put("position", Map.of("x", mc.player.getX(), "y", mc.player.getY(), "z", mc.player.getZ()));
			data.put("players", mc.getConnection() == null ? 0 : mc.getConnection().getOnlinePlayers().size());
			var info = mc.getConnection() == null ? null : mc.getConnection().getPlayerInfo(mc.player.getUUID());
			if (info != null && !mc.isLocalServer()) data.put("ping_ms", info.getLatency());
		}
		try {
			Path file = directory.resolve("state.json"), temp = directory.resolve("state.tmp");
			Files.writeString(temp, JSON.toJson(data));
			try { Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
			catch (java.nio.file.AtomicMoveNotSupportedException e) { Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING); }
		} catch (IOException ignored) { }
	}
}
