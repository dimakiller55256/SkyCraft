package dev.skycraft.network;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/** One Minecraft connection to one already connected upstream socket. */
public final class LoopbackBridge implements AutoCloseable {
	private final ServerSocket listener;
	private final Socket upstream;
	private final AtomicBoolean closed = new AtomicBoolean();
	private final AtomicBoolean reported = new AtomicBoolean();
	private final java.util.concurrent.atomic.LongAdder upload = new java.util.concurrent.atomic.LongAdder();
	private final java.util.concurrent.atomic.LongAdder download = new java.util.concurrent.atomic.LongAdder();
	private volatile Socket client;

	public LoopbackBridge(Socket upstream, Consumer<IOException> onFailure) throws IOException {
		this.upstream = upstream;
		listener = new ServerSocket();
		try {
			listener.bind(new InetSocketAddress("127.0.0.1", 0), 1);
			listener.setSoTimeout(30000);
		} catch (IOException e) { listener.close(); upstream.close(); throw e; }
		Thread.ofVirtual().name("SkyCraft proxy accept").start(() -> {
			try {
				Socket accepted = listener.accept();
				client = accepted;
				listener.close();
				if (closed.get()) { accepted.close(); return; }
				accepted.setTcpNoDelay(true);
				AtomicInteger directions = new AtomicInteger(2);
				Thread.ofVirtual().name("SkyCraft proxy upload").start(() -> copy(accepted, upstream, directions, onFailure));
				Thread.ofVirtual().name("SkyCraft proxy download").start(() -> copy(upstream, accepted, directions, onFailure));
			} catch (IOException e) {
				fail(e, onFailure);
			}
		});
	}

	public int port() { return listener.getLocalPort(); }
	public long uploadBytes() { return upload.sum(); }
	public long downloadBytes() { return download.sum(); }

	private void copy(Socket from, Socket to, AtomicInteger directions, Consumer<IOException> onFailure) {
		try {
			byte[] buffer = new byte[32768];
			int count;
			while ((count = from.getInputStream().read(buffer)) >= 0) {
				if (count == 0) continue;
				to.getOutputStream().write(buffer, 0, count);
				(from == upstream ? download : upload).add(count);
			}
			to.shutdownOutput(); // EOF in one direction must not truncate the other direction.
		} catch (IOException e) {
			fail(e, onFailure);
		} finally {
			if (directions.decrementAndGet() == 0) close();
		}
	}

	private void fail(IOException error, Consumer<IOException> onFailure) {
		try {
			if (!closed.get() && reported.compareAndSet(false, true)) onFailure.accept(error);
		} finally { close(); }
	}

	@Override public void close() {
		if (!closed.compareAndSet(false, true)) return;
		try { listener.close(); } catch (IOException ignored) { }
		try { upstream.close(); } catch (IOException ignored) { }
		Socket current = client;
		if (current != null) try { current.close(); } catch (IOException ignored) { }
	}
}
