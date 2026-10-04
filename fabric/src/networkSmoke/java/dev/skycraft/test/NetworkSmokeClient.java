package dev.skycraft.test;

import dev.skycraft.SkyCraft;
import dev.skycraft.client.NetworkClient;
import dev.skycraft.network.Endpoint;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;

/** Real Fabric/Netty/Mixin login handshake against a loopback HTTP proxy fixture. No game saves. */
public final class NetworkSmokeClient implements ClientModInitializer {
	private ServerSocket proxy;
	private final CompletableFuture<Void> result = new CompletableFuture<>();
	private boolean started, finished;
	private long deadline;
	private final String assistantSession = java.util.UUID.randomUUID().toString().replace("-", "");
	private int assistantStep;
	private long assistantDeadline;

	@Override public void onInitializeClient() {
		if (Boolean.getBoolean("skycraft.assistantWorldSmoke")) { new AssistantWorldSmoke().initialize(); return; }
		deadline = System.nanoTime() + 90_000_000_000L;
		ClientTickEvents.END_CLIENT_TICK.register(this::tick);
	}

	private void tick(Minecraft minecraft) {
		if (finished) return;
		if (System.nanoTime() > deadline) result.completeExceptionally(new IOException("Timed out waiting for the real Minecraft login handshake"));
		if (result.isDone()) {
			try { result.join(); if (!assistantSmoke(minecraft)) return; }
			catch (Exception e) { result.obtrudeException(e); }
			finished = true;
			String report;
			try {
				result.join();
				report = "PASS";
				SkyCraft.LOG.info("NETWORK SMOKE PASS: real Minecraft connection retained host.invalid:25570 through HTTP CONNECT");
			} catch (Exception e) {
				report = "FAIL: " + e;
				SkyCraft.LOG.error("NETWORK SMOKE FAILED", e);
			}
			try {
				Files.writeString(minecraft.gameDirectory.toPath().resolve("network-smoke-result.txt"), report);
				if (proxy != null) proxy.close();
			} catch (IOException e) { throw new RuntimeException(e); }
			NetworkClient.cancel();
			minecraft.stop();
			return;
		}
		if (started || minecraft.gui.overlay() != null) return;
		if (minecraft.gui.screen() != null && minecraft.gui.screen().getClass().getName().contains("Onboarding")) {
			minecraft.gui.setScreen(new TitleScreen());
			return;
		}
		if (!(minecraft.gui.screen() instanceof TitleScreen title)) return;
		started = true;
		try {
			proxy = new ServerSocket();
			proxy.bind(new InetSocketAddress("127.0.0.1", 0));
			proxy.setSoTimeout(20000);
			var config = minecraft.gameDirectory.toPath().resolve("config/skycraft.properties");
			Files.createDirectories(config.getParent());
			Files.writeString(config, "network.mode=HTTP_CONNECT\nnetwork.proxy=127.0.0.1:" + proxy.getLocalPort() + "\n");
			Thread.ofVirtual().name("SkyCraft smoke proxy").start(this::capture);
			NetworkClient.connect(minecraft, title, new Endpoint("host.invalid", 25570));
		} catch (IOException e) { result.completeExceptionally(e); }
	}

	/** Verify release file IPC, asynchronous real-socket checks and lease restoration in a real client. */
	private boolean assistantSmoke(Minecraft minecraft) throws Exception {
		Path directory = minecraft.gameDirectory.toPath().resolve("config/skycraft-test");
		Files.createDirectories(directory);
		var gson = new com.google.gson.Gson();
		if (assistantStep < 4) Files.writeString(directory.resolve("lease.json"), gson.toJson(java.util.Map.of("schema", 1, "session", assistantSession, "expiresUtc", java.time.Instant.now().plusSeconds(15).toString())));
		if (assistantStep == 0) {
			assistantDeadline = System.nanoTime() + 20_000_000_000L;
			assistantRequest(directory, 1, "start", java.util.Map.of("runId", "ASSISTANT_SMOKE", "role", "client"));
			assistantStep = 1; return false;
		}
		if (System.nanoTime() > assistantDeadline) throw new IOException("Assistant smoke timed out at " + assistantStep);
		if (assistantStep == 4) {
			if (NetworkClient.config(minecraft).mode() != dev.skycraft.network.NetworkConfig.Mode.HTTP_CONNECT) return false;
			SkyCraft.LOG.info("ASSISTANT SMOKE PASS: file commands, real socket suite, runtime override and lease restoration");
			return true;
		}
		if (!Files.exists(directory.resolve("state.json"))) return false;
		var state = com.google.gson.JsonParser.parseString(Files.readString(directory.resolve("state.json"))).getAsJsonObject();
		if (!assistantSession.equals(state.get("session").getAsString()) || state.get("id").getAsLong() != assistantStep || state.get("result").getAsString().equals("running")) return false;
		if (!state.get("result").getAsString().equals("ok")) throw new IOException("Assistant command failed");
		if (assistantStep == 1) assistantRequest(directory, 2, "transport", java.util.Map.of());
		else if (assistantStep == 2) {
			if (!state.getAsJsonObject("detail").get("success").getAsBoolean()) throw new IOException("Real socket suite failed");
			assistantRequest(directory, 3, "configure", java.util.Map.of("mode", "DIRECT"));
		} else if (assistantStep == 3) {
			if (!state.get("transportMode").getAsString().equals("DIRECT")) throw new IOException("Runtime override missing");
			Files.delete(directory.resolve("lease.json"));
		}
		assistantStep++; return false;
	}
	private void assistantRequest(java.nio.file.Path directory, int id, String action, java.util.Map<String, Object> fields) throws IOException {
		var request = new java.util.LinkedHashMap<String, Object>(fields);
		request.put("id", id); request.put("action", action); request.put("session", assistantSession);
		Files.writeString(directory.resolve("request.json"), new com.google.gson.Gson().toJson(request));
	}

	private void capture() {
		try (var socket = proxy.accept()) {
			socket.setSoTimeout(15000);
			StringBuilder header = new StringBuilder();
			while (!header.toString().endsWith("\r\n\r\n")) {
				int next = socket.getInputStream().read();
				if (next < 0 || header.length() > 16384) throw new IOException("Invalid CONNECT request");
				header.append((char)next);
			}
			if (!header.toString().startsWith("CONNECT host.invalid:25570 HTTP/1.1\r\n")) throw new IOException("Wrong proxy destination");
			socket.getOutputStream().write("HTTP/1.1 200 OK\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
			int packetLength = varInt(socket.getInputStream());
			if (packetLength < 1 || packetLength > 1024) throw new IOException("Unexpected handshake packet length");
			var packet = new DataInputStream(new java.io.ByteArrayInputStream(socket.getInputStream().readNBytes(packetLength)));
			if (varInt(packet) != 0) throw new IOException("Expected handshake packet id 0");
			varInt(packet); // Minecraft protocol version.
			int hostLength = varInt(packet);
			if (hostLength < 1 || hostLength > 255) throw new IOException("Invalid handshake host length");
			String host = new String(packet.readNBytes(hostLength), StandardCharsets.UTF_8);
			int port = packet.readUnsignedShort();
			if (!host.equals("host.invalid") || port != 25570 || varInt(packet) != 2 || packet.available() != 0) {
				throw new IOException("Incorrect Minecraft handshake endpoint: " + host + ":" + port);
			}
			result.complete(null);
		} catch (Throwable e) { result.completeExceptionally(e); }
	}

	private static int varInt(InputStream input) throws IOException {
		int result = 0;
		for (int index = 0; index < 5; index++) {
			int value = input.read();
			if (value < 0) throw new java.io.EOFException();
			result |= (value & 127) << (index * 7);
			if ((value & 128) == 0) return result;
		}
		throw new IOException("Invalid VarInt");
	}
}
