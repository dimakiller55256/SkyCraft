package dev.skycraft.network;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** One cancellable dial. SOCKS5 and CONNECT pass target DNS names to the proxy. */
public final class SocketDialer implements AutoCloseable {
	private Socket socket;
	private boolean closed;
	private long deadline;
	private long began;
	private Endpoint peer;
	private final java.util.function.Consumer<DialEvent> observer;
	public record DialEvent(String phase, long elapsedMillis, String peerHost, int peerPort, Integer statusCode, String exceptionType) { }
	public SocketDialer() { this(event -> { }); }
	public SocketDialer(java.util.function.Consumer<DialEvent> observer) { this.observer = observer; }
	private void emit(String phase, Integer code, Throwable error) {
		try {
			observer.accept(new DialEvent(phase, began == 0 ? 0 : (System.nanoTime() - began) / 1_000_000L,
				peer == null ? "" : peer.host(), peer == null ? 0 : peer.port(), code, error == null ? null : error.getClass().getSimpleName()));
		} catch (RuntimeException ignored) { /* Diagnostics must never break a connection. */ }
	}

	public Socket connect(Endpoint target, NetworkConfig config) throws IOException {
		Socket current = new Socket(Proxy.NO_PROXY);
		synchronized (this) {
			if (closed || socket != null) { current.close(); throw new IOException("Соединение отменено."); }
			socket = current;
		}
		began = System.nanoTime();
		deadline = began + config.timeoutMillis() * 1_000_000L;
		try {
			peer = config.mode() == NetworkConfig.Mode.DIRECT ? target : config.proxy();
			emit("dns_started", null, null);
			InetSocketAddress address = new InetSocketAddress(peer.host(), peer.port());
			if (address.isUnresolved()) throw new java.net.UnknownHostException(peer.host());
			emit("dns_resolved", null, null);
			emit("tcp_connect_started", null, null);
			current.connect(address, remainingMillis());
			emit("tcp_connected", null, null);
			current.setTcpNoDelay(true);
			switch (config.mode()) {
				case DIRECT -> { }
				case SOCKS5 -> socks5(current, target, config);
				case HTTP_CONNECT -> httpConnect(current, target, config);
			}
			current.setSoTimeout(0); // Gameplay may remain idle; handshake timeout must not leak into it.
			emit("dial_ready", null, null);
			return current;
		} catch (IOException | RuntimeException e) {
			emit("dial_failed", null, e);
			close();
			throw e;
		}
	}

	private void socks5(Socket current, Endpoint target, NetworkConfig config) throws IOException {
		var out = current.getOutputStream();
		InputStream in = current.getInputStream();
		boolean auth = !config.username().isEmpty() || !config.password().isEmpty();
		emit("socks_greeting", null, null);
		// Offer only the requested authentication mode; never silently downgrade credentials.
		out.write(new byte[] { 5, 1, (byte)(auth ? 2 : 0) });
		out.flush();
		if (read(current, in) != 5) throw new IOException("Прокси не отвечает по SOCKS5.");
		int method = read(current, in);
		emit("socks_method", method, null);
		if (method != (auth ? 2 : 0)) throw new IOException("SOCKS5: прокси отклонил способ авторизации.");
		if (auth) {
			byte[] user = config.username().getBytes(StandardCharsets.UTF_8);
			byte[] pass = config.password().getBytes(StandardCharsets.UTF_8);
			if (user.length == 0 || user.length > 255 || pass.length == 0 || pass.length > 255) {
				throw new IOException("SOCKS5: длина имени и пароля должна быть от 1 до 255 байт.");
			}
			out.write(1); out.write(user.length); out.write(user); out.write(pass.length); out.write(pass); out.flush();
			int authVersion = read(current, in), authStatus = read(current, in);
			emit("socks_auth", authStatus, null);
			if (authVersion != 1 || authStatus != 0) throw new IOException("SOCKS5: неверные данные авторизации.");
		}
		out.write(new byte[] { 5, 1, 0 });
		if (target.host().contains(":")) {
			out.write(4); out.write(InetAddress.getByName(target.host()).getAddress());
		} else {
			byte[] name = target.host().getBytes(StandardCharsets.US_ASCII);
			out.write(3); out.write(name.length); out.write(name);
		}
		out.write(target.port() >>> 8); out.write(target.port() & 255); out.flush();
		int version = read(current, in), status = read(current, in), reserved = read(current, in), type = read(current, in);
		emit("socks_connect_result", status, null);
		if (version != 5 || reserved != 0) throw new IOException("SOCKS5: повреждён ответ прокси.");
		if (status != 0) throw new IOException("SOCKS5: соединение отклонено (код " + status + ").");
		int length = switch (type) {
			case 1 -> 4;
			case 4 -> 16;
			case 3 -> read(current, in);
			default -> throw new IOException("SOCKS5: неизвестный тип адреса в ответе.");
		};
		for (int i = 0; i < length + 2; i++) read(current, in);
	}

	private void httpConnect(Socket current, Endpoint target, NetworkConfig config) throws IOException {
		emit("http_connect_started", null, null);
		String authority = target.authority();
		String request = "CONNECT " + authority + " HTTP/1.1\r\nHost: " + authority + "\r\n";
		if (!config.username().isEmpty() || !config.password().isEmpty()) {
			if (config.username().contains(":")) throw new IOException("HTTP-прокси: имя пользователя не должно содержать двоеточие.");
			request += "Proxy-Authorization: Basic " + Base64.getEncoder().encodeToString(
				(config.username() + ":" + config.password()).getBytes(StandardCharsets.UTF_8)) + "\r\n";
		}
		current.getOutputStream().write((request + "\r\n").getBytes(StandardCharsets.US_ASCII));
		current.getOutputStream().flush();
		ByteArrayOutputStream header = new ByteArrayOutputStream();
		int end = 0;
		while (end != 4) {
			int value = read(current, current.getInputStream());
			header.write(value);
			if (header.size() > 16384) throw new IOException("HTTP-прокси: слишком большой заголовок ответа.");
			end = switch (end) {
				case 0 -> value == '\r' ? 1 : 0;
				case 1 -> value == '\n' ? 2 : value == '\r' ? 1 : 0;
				case 2 -> value == '\r' ? 3 : 0;
				default -> value == '\n' ? 4 : value == '\r' ? 1 : 0;
			};
		}
		// Unbuffered header reads deliberately leave any first gameplay bytes in the socket.
		String statusLine = header.toString(StandardCharsets.US_ASCII).split("\r\n", 2)[0];
		var match = java.util.regex.Pattern.compile("HTTP/1\\.[01] ([0-9]{3})(?: .*)?").matcher(statusLine);
		if (!match.matches()) throw new IOException("HTTP-прокси: некорректный ответ CONNECT.");
		int status = Integer.parseInt(match.group(1));
		emit("http_connect_result", status, null);
		if (status == 407) throw new IOException("HTTP-прокси: требуется авторизация или неверный пароль (407).");
		if (status < 200 || status >= 300) throw new IOException("HTTP-прокси отклонил CONNECT (код " + status + ").");
	}

	private int read(Socket current, InputStream in) throws IOException {
		current.setSoTimeout(remainingMillis());
		int value = in.read();
		if (value < 0) throw new EOFException("Прокси закрыл соединение во время согласования.");
		return value;
	}

	private int remainingMillis() throws SocketTimeoutException {
		long remaining = (deadline - System.nanoTime()) / 1_000_000L;
		if (remaining <= 0) throw new SocketTimeoutException("Истекло время подключения.");
		return (int)Math.min(Integer.MAX_VALUE, Math.max(1, remaining));
	}

	@Override public synchronized void close() {
		if (closed) return;
		closed = true;
		if (socket != null) try { socket.close(); } catch (IOException ignored) { }
		emit("dial_closed", null, null);
	}
}
