package dev.skycraft.test;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.skycraft.client.MirrorWorld;
import dev.skycraft.client.NetworkClient;
import dev.skycraft.SkyCraft;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;

/** Isolated real-world lifecycle test; does not use Skyrim or installed Prism data. */
final class AssistantWorldSmoke {
	private final Gson json = new Gson();
	private final String session = java.util.UUID.randomUUID().toString().replace("-", "");
	private long deadline, issuedAt;
	private int step, hostPort, closedPort;
	private boolean finished;
	private int recoveries;
	private Path directory;
	private net.minecraft.client.player.LocalPlayer before;
	void initialize() {
		deadline = System.nanoTime() + 180_000_000_000L;
		ClientTickEvents.END_CLIENT_TICK.register(this::tick);
	}
	private void request(int id, String action, Map<String, Object> fields) throws Exception {
		var data = new LinkedHashMap<String, Object>(fields);
		data.put("id", id); data.put("session", session); data.put("action", action);
		Files.writeString(directory.resolve("request.json"), json.toJson(data));
		issuedAt = System.nanoTime();
	}
	private void tick(Minecraft mc) {
		if (finished) return;
		try {
			if (System.nanoTime() > deadline) throw new java.io.IOException("World smoke timeout at step " + step);
			directory = mc.gameDirectory.toPath().resolve("config/skycraft-test");
			Files.createDirectories(directory);
			Files.writeString(directory.resolve("lease.json"), json.toJson(Map.of("schema", 1, "session", session, "expiresUtc", Instant.now().plusSeconds(15).toString())));
			if (mc.gui.overlay() != null) return;
			if (mc.gui.screen() != null && mc.gui.screen().getClass().getName().contains("Onboarding")) mc.gui.setScreen(new TitleScreen());
			if (mc.level == null) MirrorWorld.openWhenReady(mc);
			if (step == 0) {
				if (mc.player == null || !mc.isLocalServer()) return;
				before = mc.player;
				try (var free = new ServerSocket()) { free.bind(new InetSocketAddress("127.0.0.1", 0)); hostPort = free.getLocalPort(); }
				try (var free = new ServerSocket()) { free.bind(new InetSocketAddress("127.0.0.1", 0)); closedPort = free.getLocalPort(); }
				request(1, "start", Map.of("runId", "WORLD_SMOKE", "role", "client")); step = 1; return;
			}
			if (!Files.exists(directory.resolve("state.json"))) return;
			JsonObject state = JsonParser.parseString(Files.readString(directory.resolve("state.json"))).getAsJsonObject();
			if (!session.equals(state.get("session").getAsString()) || state.get("id").getAsInt() != step || state.get("result").getAsString().equals("running")) return;
			if (!state.get("result").getAsString().equals("ok") && step != 5) throw new java.io.IOException("Command failed at " + step);
			if (step >= 6 && step < 15) {
				switch ((step - 6) % 3) {
					case 0 -> {
						if (mc.player == null || mc.player == before || !mc.isLocalServer() || !dev.skycraft.client.SkyClient.positionReady()) return;
						var reason = state.getAsJsonObject("lastFailure");
						if (!reason.has("reason") || reason.get("reason").getAsString().isEmpty()) throw new java.io.IOException("Lost failed-join reason");
						if (Math.abs(mc.player.getX() - 100000.5) > .5 || Math.abs(mc.player.getY() - 100) > .5 || Math.abs(mc.player.getZ() - .5) > .5) throw new java.io.IOException("Recovery reset player position");
						recoveries++;
						request(step + 1, "host", Map.of("port", hostPort));
					}
					case 1 -> request(step + 1, "probe", Map.of("target", "127.0.0.1:" + hostPort));
					case 2 -> {
						if (!state.getAsJsonObject("detail").get("success").getAsBoolean()) throw new java.io.IOException("Restored server unavailable");
						if (recoveries == 3) request(step + 1, "stop", Map.of());
						else { before = mc.player; request(step + 1, "join", Map.of("target", "127.0.0.1:" + closedPort)); }
					}
				}
				step++; return;
			}
			if (step == 15) {Files.delete(directory.resolve("lease.json"));finish(mc, "PASS");return;}
			switch (step) {
				case 1 -> request(2, "configure", Map.of("mode", "DIRECT"));
				case 2 -> request(3, "host", Map.of("port", hostPort));
				case 3 -> {
					if (!state.get("published").getAsBoolean() || state.get("hostPort").getAsInt() != hostPort) throw new java.io.IOException("Host not published");
					request(4, "probe", Map.of("target", "127.0.0.1:" + hostPort));
				}
				case 4 -> {
					if (!state.getAsJsonObject("detail").get("success").getAsBoolean()) throw new java.io.IOException("Host probe failed");
					if (!state.getAsJsonObject("peerSession").has("version") || !state.getAsJsonObject("peerSession").get("version").getAsString().equals("0.1.2-ys.network.4")) throw new java.io.IOException("Server session metadata missing");
					request(5, "probe", Map.of("target", "127.0.0.1:" + closedPort));
				}
				case 5 -> {
					if (state.getAsJsonObject("detail").get("success").getAsBoolean()) throw new java.io.IOException("Closed port unexpectedly open");
					request(6, "join", Map.of("target", "127.0.0.1:" + closedPort));
				}
				case 6 -> {
					if (mc.player == null || mc.player == before || !mc.isLocalServer()) return;
					request(7, "host", Map.of("port", hostPort));
				}
				case 7 -> request(8, "probe", Map.of("target", "127.0.0.1:" + hostPort));
				case 8 -> {
					if (!state.getAsJsonObject("detail").get("success").getAsBoolean()) throw new java.io.IOException("Restored world probe failed");
					request(9, "stop", Map.of());
				}
				case 9 -> {
					Files.delete(directory.resolve("lease.json"));
					finish(mc, "PASS"); return;
				}
				default -> throw new IllegalStateException();
			}
			step++;
		} catch (Exception e) { finish(mc, "FAIL: " + e); }
	}
	private void finish(Minecraft mc, String result) {
		finished = true;
		try { Files.writeString(mc.gameDirectory.toPath().resolve("assistant-world-smoke-result.txt"), result); }
		catch (Exception e) { throw new RuntimeException(e); }
		SkyCraft.LOG.info("ASSISTANT WORLD SMOKE {}: host/probe/closed port/join/recovery/re-publish", result);
		NetworkClient.cancel(); mc.stop();
	}
}
