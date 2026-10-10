package dev.skycraft.network;

import com.google.gson.Gson;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** Bounded asynchronous metadata log. No packet payloads or unapproved configuration fields. */
public final class JsonLineTrace implements AutoCloseable {
	private static final Gson JSON = new Gson();
	private static final Set<String> FIELDS = Set.of("attempt_id", "connection_id", "phase", "result", "mode",
		"target_host", "target_port", "proxy_host", "proxy_port", "proxy_credentials_set", "timeout_ms",
		"remote_address", "remote_port", "local_address", "local_port", "receiving", "exception_type",
		"status_code", "elapsed_ms", "ping_ms", "upload_bytes", "download_bytes", "player_count",
		"skyrim_linked", "published", "host_port", "marker", "mc_version", "mod_version", "fabric_version",
		"java_version", "os_name", "os_version", "reason_code", "peer_host", "peer_port",
		"reason", "mc_x", "mc_y", "mc_z", "sky_x", "sky_y", "sky_z", "teleport_seq",
		"session", "command", "request_id", "check", "success", "host_authentication",
		"x", "y", "z", "health", "fall_distance", "on_ground", "shield", "takeover", "collision_known",
		"world", "epoch", "dy", "known_regions", "triangles", "feet_known", "below_known",
		"dug_at_feet", "nearby_triangles", "surface", "geometry");
	private final Path file;
	private final String runId, role;
	private final int maxBytes, backups;
	private final ArrayBlockingQueue<String> queue = new ArrayBlockingQueue<>(512);
	private final AtomicBoolean closing = new AtomicBoolean();
	private final AtomicLong dropped = new AtomicLong(), sequence = new AtomicLong();
	private final CompletableFuture<Void> finished = new CompletableFuture<>();
	private final long started = System.nanoTime();
	private volatile String errorType;

	public JsonLineTrace(Path file, String runId, String role) {
		this(file, runId, role, 4 * 1024 * 1024, 3);
	}

	public JsonLineTrace(Path file, String runId, String role, int maxBytes, int backups) {
		if (!runId.matches("[A-Za-z0-9_-]{1,64}") || !Set.of("host", "client", "auto").contains(role)
			|| maxBytes < 256 || backups < 0 || backups > 10) throw new IllegalArgumentException("Invalid trace settings");
		this.file = file; this.runId = runId; this.role = role; this.maxBytes = maxBytes; this.backups = backups;
		Thread.ofVirtual().name("SkyCraft network log").start(this::write);
	}

	public void event(String event, Map<String, ?> fields) {
		if (closing.get() || errorType != null) return;
		Map<String, Object> record = new LinkedHashMap<>();
		record.put("schema", 1);
		record.put("utc", Instant.now().toString());
		record.put("since_start_ms", (System.nanoTime() - started) / 1_000_000L);
		record.put("seq", sequence.incrementAndGet());
		record.put("run_id", runId); record.put("role", role);
		String eventName = event.replaceAll("[^A-Za-z0-9_]", "_");
		record.put("event", eventName.substring(0, Math.min(64, eventName.length())));
		for (var entry : fields.entrySet()) {
			Object value = entry.getValue();
			if (!FIELDS.contains(entry.getKey()) || value == null) continue;
			if (entry.getKey().equals("geometry")) {
				if (!(value instanceof Iterable<?> rows)) continue;
				// Only bounded finite numeric triangles; arbitrary nested maps/strings
				// must not provide another path for credentials or packet contents.
				var triangles = new java.util.ArrayList<double[]>();
				var iterator = rows.iterator();
				for (int i=0;i<32 && iterator.hasNext();i++) {
					Object row=iterator.next();
					if (!(row instanceof double[] vertices) || vertices.length!=10) continue;
					if (java.util.Arrays.stream(vertices).allMatch(Double::isFinite)) triangles.add(vertices.clone());
				}
				if (!triangles.isEmpty()) record.put(entry.getKey(),triangles);
				continue;
			}
			if (value instanceof String text) record.put(entry.getKey(), text.substring(0, Math.min(240, text.length())));
			else if ((value instanceof Number number && Double.isFinite(number.doubleValue())) || value instanceof Boolean) record.put(entry.getKey(), value);
		}
		if (!queue.offer(JSON.toJson(record))) dropped.incrementAndGet();
	}

	public Path file() { return file; }
	public long dropped() { return dropped.get(); }
	public String errorType() { return errorType; }
	@Override public void close() { closing.set(true); }
	public boolean awaitClosed(Duration timeout) throws InterruptedException {
		try { finished.get(timeout.toMillis(), TimeUnit.MILLISECONDS); return true; }
		catch (java.util.concurrent.TimeoutException e) { return false; }
		catch (java.util.concurrent.ExecutionException e) { return true; }
	}

	private void write() {
		try {
			Files.createDirectories(file.getParent());
			long size = Files.exists(file) ? Files.size(file) : 0;
			while (!closing.get() || !queue.isEmpty()) {
				String line = queue.poll(200, TimeUnit.MILLISECONDS);
				if (line == null) continue;
				byte[] bytes = (line + "\n").getBytes(StandardCharsets.UTF_8);
				if (size > 0 && size + bytes.length > maxBytes) { rotate(); size = 0; }
				Files.write(file, bytes, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
				size += bytes.length;
			}
			Map<String, Object> end = new LinkedHashMap<>();
			end.put("schema", 1); end.put("utc", Instant.now().toString()); end.put("run_id", runId); end.put("role", role);
			end.put("event", "trace_closed"); end.put("dropped_events", dropped.get());
			byte[] bytes = (JSON.toJson(end) + "\n").getBytes(StandardCharsets.UTF_8);
			if (size > 0 && size + bytes.length > maxBytes) rotate();
			Files.write(file, bytes, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
			finished.complete(null);
		} catch (IOException | InterruptedException | RuntimeException e) {
			errorType = e.getClass().getSimpleName();
			finished.completeExceptionally(e);
		}
	}

	private void rotate() throws IOException {
		if (backups == 0) { Files.deleteIfExists(file); return; }
		Files.deleteIfExists(Path.of(file + "." + backups));
		for (int number = backups - 1; number >= 1; number--) {
			Path older = Path.of(file + "." + number);
			if (Files.exists(older)) Files.move(older, Path.of(file + "." + (number + 1)), StandardCopyOption.REPLACE_EXISTING);
		}
		if (Files.exists(file)) Files.move(file, Path.of(file + ".1"), StandardCopyOption.REPLACE_EXISTING);
	}
}
