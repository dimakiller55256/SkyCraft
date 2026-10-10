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
	@Test void retainsBoundedCollisionEvidenceWithoutNestedSecrets() throws Exception {
		Path file=directory.resolve("collision.jsonl");
		var trace=new JsonLineTrace(file,"COLLISION","client");
		var rows=new java.util.ArrayList<Object>();
		rows.add(Map.of("password","never-write-nested-secret"));
		for(int i=0;i<50;i++)rows.add(new double[]{i,1,2,3,4,5,6,7,8,1});
		trace.event("collision_guard",Map.of("x",-12.5,"health",20,"fall_distance",4.5,"feet_known",true,"geometry",rows));
		trace.close();assertTrue(trace.awaitClosed(Duration.ofSeconds(3)));
		String text=Files.readString(file);assertFalse(text.contains("never-write"));
		var record=JsonParser.parseString(text.lines().findFirst().orElseThrow()).getAsJsonObject();
		assertEquals(-12.5,record.get("x").getAsDouble());assertEquals(20,record.get("health").getAsInt());
		assertEquals(4.5,record.get("fall_distance").getAsDouble());assertTrue(record.get("feet_known").getAsBoolean());
		var triangles=record.getAsJsonArray("geometry");assertEquals(31,triangles.size());
		assertEquals(10,triangles.get(0).getAsJsonArray().size());assertNull(trace.errorType());
	}
	@Test void invalidGeometryAndNonFiniteNumbersCannotBreakTrace() throws Exception {
		Path file=directory.resolve("invalid-geometry.jsonl");
		var trace=new JsonLineTrace(file,"INVALID_GEOMETRY","client");
		trace.event("collision_guard",Map.of("x",Double.NaN,"y",Double.POSITIVE_INFINITY,
			"geometry",java.util.List.of(new double[]{1,2},new double[]{Double.NaN,1,2,3,4,5,6,7,8,1},"never-write-geometry-secret"),"reason","fall_probe"));
		trace.event("collision_guard",Map.of("geometry","never-write-top-level-geometry-secret"));
		trace.close();assertTrue(trace.awaitClosed(Duration.ofSeconds(3)));
		String text=Files.readString(file);assertFalse(text.contains("never-write"));
		var record=JsonParser.parseString(text.lines().findFirst().orElseThrow()).getAsJsonObject();
		assertFalse(record.has("x"));assertFalse(record.has("y"));assertFalse(record.has("geometry"));
		assertEquals("fall_probe",record.get("reason").getAsString());assertNull(trace.errorType());
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
