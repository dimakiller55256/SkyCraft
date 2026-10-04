package dev.skycraft.network;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JsonLineTraceTest {
	@TempDir Path directory;
	@Test void writesUtf8AndDropsCredentialsAndUnknownFields() throws Exception {
		Path file = directory.resolve("test.jsonl");
		var trace = new JsonLineTrace(file, "A01_1", "client");
		trace.event("marker", Map.of("marker", "Блок виден\nсо второй стороны", "password", "never-write-password",
			"username", "never-write-user", "token", "never-write-token", "config", Map.of("secret", "never-write-config"),
			"mc_x", 123.25, "sky_x", 124.25, "request_id", 7));
		trace.close(); assertTrue(trace.awaitClosed(Duration.ofSeconds(3)));
		String text = Files.readString(file);
		assertFalse(text.contains("never-write"));
		var first = JsonParser.parseString(text.lines().findFirst().orElseThrow()).getAsJsonObject();
		assertEquals("A01_1", first.get("run_id").getAsString());
		assertEquals("Блок виден\nсо второй стороны", first.get("marker").getAsString());
		assertEquals(123.25, first.get("mc_x").getAsDouble());
		assertEquals(124.25, first.get("sky_x").getAsDouble());
		assertEquals(7, first.get("request_id").getAsInt());
		assertEquals(2, text.lines().count());
		assertNull(trace.errorType());
	}
	@Test void rotatesFilesAndRetainsValidJsonRecords() throws Exception {
		Path file = directory.resolve("test.jsonl");
		var trace = new JsonLineTrace(file, "A02", "host", 512, 2);
		for (int i = 0; i < 30; i++) trace.event("sample", Map.of("upload_bytes", i * 1000, "marker", "a".repeat(100)));
		trace.close(); assertTrue(trace.awaitClosed(Duration.ofSeconds(3)));
		try (var files = Files.list(directory)) {
			var paths = files.toList();
			assertEquals(3, paths.size());
			for (Path path : paths) {
				assertTrue(Files.size(path) <= 512);
				for (String line : Files.readAllLines(path)) assertEquals("A02", JsonParser.parseString(line).getAsJsonObject().get("run_id").getAsString());
			}
		}
		assertEquals(0, trace.dropped());
	}
	@Test void invalidOutputDoesNotThrowOnNetworkThread() throws Exception {
		Path parent = directory.resolve("not-a-directory"); Files.writeString(parent, "fixture");
		var trace = new JsonLineTrace(parent.resolve("test.jsonl"), "A03", "client");
		trace.event("sample", Map.of()); trace.close();
		assertTrue(trace.awaitClosed(Duration.ofSeconds(3)));
		assertNotNull(trace.errorType());
		assertDoesNotThrow(() -> trace.event("sample", Map.of()));
	}
}
