package dev.skycraft.network;

import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class SocketDialerTest {
	@FunctionalInterface interface Session { void run(Socket socket) throws Exception; }

	static final class Fixture implements AutoCloseable {
		final ServerSocket server = new ServerSocket();
		final java.util.concurrent.ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
		final Future<?> task;
		volatile Socket accepted;
		Fixture(Session session) throws IOException {
			server.bind(new InetSocketAddress("127.0.0.1", 0));
			task = executor.submit(() -> {
				try (var socket = server.accept()) {
					accepted = socket;
					socket.setSoTimeout(5000);
					session.run(socket);
				} catch (Exception e) { throw new RuntimeException(e); }
			});
		}
		Endpoint endpoint() { return new Endpoint("127.0.0.1", server.getLocalPort()); }
		NetworkConfig config(NetworkConfig.Mode mode) { return new NetworkConfig("", mode, endpoint(), "", "", 1000, 25565, ""); }
		void finished() throws Exception { task.get(5, TimeUnit.SECONDS); }
		@Override public void close() throws IOException {
			server.close();
			if (accepted != null) accepted.close();
			executor.shutdownNow();
		}
	}

	@Test void directConnectionIgnoresGlobalJavaProxy() throws Exception {
		String previous = System.getProperty("socksProxyHost");
		System.setProperty("socksProxyHost", "unreachable.invalid");
		try (Fixture server = new Fixture(socket -> socket.getOutputStream().write(42)); SocketDialer dialer = new SocketDialer()) {
			try (Socket socket = dialer.connect(server.endpoint(), server.config(NetworkConfig.Mode.DIRECT))) {
				assertEquals(42, socket.getInputStream().read());
				assertEquals(0, socket.getSoTimeout());
			}
			server.finished();
		} finally {
			if (previous == null) System.clearProperty("socksProxyHost"); else System.setProperty("socksProxyHost", previous);
		}
	}

	@Test void socks5UsesRemoteDnsAndConsumesOnlyHandshake() throws Exception {
		try (Fixture proxy = new Fixture(socket -> {
			var in = socket.getInputStream(); var out = socket.getOutputStream();
			assertArrayEquals(new byte[] {5, 1, 0}, in.readNBytes(3));
			out.write(5); out.flush(); out.write(0); out.flush();
			assertArrayEquals(new byte[] {5, 1, 0, 3}, in.readNBytes(4));
			assertEquals("unresolvable.invalid", new String(in.readNBytes(in.read()), StandardCharsets.US_ASCII));
			assertEquals(25570, (in.read() << 8) | in.read());
			for (byte value : new byte[] {5, 0, 0, 4, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 31, (byte)144, 42}) {
				out.write(value); out.flush();
			}
		}); SocketDialer dialer = new SocketDialer()) {
			try (Socket socket = dialer.connect(new Endpoint("unresolvable.invalid", 25570), proxy.config(NetworkConfig.Mode.SOCKS5))) {
				assertEquals(42, socket.getInputStream().read());
			}
			proxy.finished();
		}
	}

	@Test void socks5AuthenticatesAndRejectsDowngrade() throws Exception {
		try (Fixture proxy = new Fixture(socket -> {
			assertArrayEquals(new byte[] {5, 1, 2}, socket.getInputStream().readNBytes(3));
			socket.getOutputStream().write(new byte[] {5, 0});
		}); SocketDialer dialer = new SocketDialer()) {
			var config = new NetworkConfig("", NetworkConfig.Mode.SOCKS5, proxy.endpoint(), "user", "password", 1000, 25565, "");
			assertThrows(IOException.class, () -> dialer.connect(new Endpoint("host.invalid", 25565), config));
			proxy.finished();
		}
		try (Fixture proxy = new Fixture(socket -> {
			var in = socket.getInputStream(); var out = socket.getOutputStream();
			assertArrayEquals(new byte[] {5, 1, 2}, in.readNBytes(3)); out.write(new byte[] {5, 2});
			assertEquals(1, in.read());
			assertEquals("user", new String(in.readNBytes(in.read()), StandardCharsets.UTF_8));
			assertEquals("password", new String(in.readNBytes(in.read()), StandardCharsets.UTF_8));
			out.write(new byte[] {1, 0});
			assertArrayEquals(new byte[] {5, 1, 0, 3}, in.readNBytes(4));
			in.readNBytes(in.read()); in.readNBytes(2);
			out.write(new byte[] {5, 0, 0, 1, 127, 0, 0, 1, 31, (byte)144});
		}); SocketDialer dialer = new SocketDialer()) {
			var config = new NetworkConfig("", NetworkConfig.Mode.SOCKS5, proxy.endpoint(), "user", "password", 1000, 25565, "");
			try (var socket = dialer.connect(new Endpoint("host.invalid", 25565), config)) { assertTrue(socket.isConnected()); }
			proxy.finished();
		}
	}

	static String header(Socket socket) throws IOException {
		StringBuilder text = new StringBuilder();
		while (!text.toString().endsWith("\r\n\r\n")) {
			int next = socket.getInputStream().read();
			if (next < 0 || text.length() > 16384) throw new IOException("Bad fixture request");
			text.append((char)next);
		}
		return text.toString();
	}

	@Test void httpConnectKeepsBannerAndUsesRemoteDns() throws Exception {
		try (Fixture proxy = new Fixture(socket -> {
			assertEquals("CONNECT host.invalid:25570 HTTP/1.1\r\nHost: host.invalid:25570\r\n\r\n", header(socket));
			socket.getOutputStream().write("HTTP/1.1 200 Connection established\r\nX-Test: yes\r\n\r\nBANNER".getBytes(StandardCharsets.US_ASCII));
		}); SocketDialer dialer = new SocketDialer()) {
			try (Socket socket = dialer.connect(new Endpoint("host.invalid", 25570), proxy.config(NetworkConfig.Mode.HTTP_CONNECT))) {
				assertEquals("BANNER", new String(socket.getInputStream().readNBytes(6), StandardCharsets.US_ASCII));
			}
			proxy.finished();
		}
	}

	@Test void httpAuthAndFailureAreReportedWithoutLeakingPassword() throws Exception {
		try (Fixture proxy = new Fixture(socket -> {
			assertTrue(header(socket).contains("Proxy-Authorization: Basic dXNlcjpwYXNzd29yZA==\r\n"));
			socket.getOutputStream().write("HTTP/1.1 407 Proxy Authentication Required\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
		}); SocketDialer dialer = new SocketDialer()) {
			var config = new NetworkConfig("", NetworkConfig.Mode.HTTP_CONNECT, proxy.endpoint(), "user", "password", 1000, 25565, "");
			IOException error = assertThrows(IOException.class, () -> dialer.connect(new Endpoint("host.invalid", 25565), config));
			assertTrue(error.getMessage().contains("407"));
			assertFalse(error.getMessage().contains("password"));
			proxy.finished();
		}
	}

	@Test void timeoutAndCancellationCloseStalledHandshake() throws Exception {
		CountDownLatch greeted = new CountDownLatch(1);
		try (Fixture proxy = new Fixture(socket -> { socket.getInputStream().readNBytes(3); greeted.countDown(); assertEquals(-1, socket.getInputStream().read()); });
			SocketDialer dialer = new SocketDialer(); var executor = Executors.newVirtualThreadPerTaskExecutor()) {
			Future<?> pending = executor.submit(() -> assertThrows(IOException.class,
				() -> dialer.connect(new Endpoint("host.invalid", 25565), proxy.config(NetworkConfig.Mode.SOCKS5))));
			assertTrue(greeted.await(2, TimeUnit.SECONDS));
			dialer.close();
			pending.get(2, TimeUnit.SECONDS);
			proxy.finished();
		}
		try (Fixture proxy = new Fixture(socket -> { socket.getInputStream().readNBytes(3); assertEquals(-1, socket.getInputStream().read()); }); SocketDialer dialer = new SocketDialer()) {
			assertTimeoutPreemptively(Duration.ofSeconds(3), () -> assertThrows(SocketTimeoutException.class,
				() -> dialer.connect(new Endpoint("host.invalid", 25565), proxy.config(NetworkConfig.Mode.SOCKS5))));
			proxy.finished();
		}
	}

	@Test void rejectsOversizedHttpHeaders() throws Exception {
		try (Fixture proxy = new Fixture(socket -> {
			header(socket);
			socket.getOutputStream().write(("HTTP/1.1 200 OK\r\nX: " + "a".repeat(16400) + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
		}); SocketDialer dialer = new SocketDialer()) {
			assertThrows(IOException.class, () -> dialer.connect(new Endpoint("host.invalid", 25565), proxy.config(NetworkConfig.Mode.HTTP_CONNECT)));
			proxy.finished();
		}
	}
}
