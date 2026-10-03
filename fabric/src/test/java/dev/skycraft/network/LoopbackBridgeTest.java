package dev.skycraft.network;

import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import java.net.Socket;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.junit.jupiter.api.Test;

class LoopbackBridgeTest {
	@Test void streamsLargePayloadAndPreservesReplyAfterHalfClose() throws Exception {
		byte[] payload = new byte[1024 * 1024 + 17];
		new java.util.Random(12).nextBytes(payload);
		ConcurrentLinkedQueue<IOException> failures = new ConcurrentLinkedQueue<>();
		try (var server = new SocketDialerTest.Fixture(socket -> {
			assertArrayEquals(payload, socket.getInputStream().readAllBytes());
			socket.getOutputStream().write(payload);
			socket.shutdownOutput();
		}); var dialer = new SocketDialer();
			var bridge = new LoopbackBridge(dialer.connect(server.endpoint(), server.config(NetworkConfig.Mode.DIRECT)), failures::add);
			var client = new Socket("127.0.0.1", bridge.port())) {
			client.setSoTimeout(5000);
			client.getOutputStream().write(payload);
			client.shutdownOutput();
			assertArrayEquals(payload, client.getInputStream().readAllBytes());
			server.finished();
			assertTrue(failures.isEmpty(), failures.toString());
		}
	}

	@Test void closeDisconnectsBothEndsIncludingBeforeAccept() throws Exception {
		try (var server = new SocketDialerTest.Fixture(socket -> assertEquals(-1, socket.getInputStream().read()));
			var dialer = new SocketDialer();
			var bridge = new LoopbackBridge(dialer.connect(server.endpoint(), server.config(NetworkConfig.Mode.DIRECT)), e -> { });
			var client = new Socket("127.0.0.1", bridge.port())) {
			client.setSoTimeout(2000);
			bridge.close();
			try { assertEquals(-1, client.getInputStream().read()); }
			catch (java.net.SocketException expectedReset) { /* Closing a not-yet-accepted socket may reset it. */ }
			server.finished();
			assertTrue(client.getInetAddress().isLoopbackAddress());
		}
	}
}
