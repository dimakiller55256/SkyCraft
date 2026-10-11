package dev.skycraft.network;

import com.google.gson.JsonParser;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AssistantProtocolTest {
	private static final String ID = "0123456789abcdef0123456789abcdef";
	private static final Instant NOW = Instant.parse("2026-10-03T22:00:00Z");
	@Test void acceptsShortLeaseAndValidatedCommand() {
		var lease = JsonParser.parseString("{\"schema\":1,\"session\":\"" + ID + "\",\"expiresUtc\":\"2026-10-03T22:00:15Z\"}").getAsJsonObject();
		assertEquals(ID, AssistantProtocol.session(lease, NOW));
		assertEquals(1, AssistantProtocol.request(JsonParser.parseString("{\"id\":1,\"session\":\"" + ID + "\",\"action\":\"join\"}").getAsJsonObject(), ID));
	}
	@Test void rejectsExpiredPersistentOrForeignLease() {
		for (String time : new String[] {"2026-10-03T22:00:00Z", "2026-10-03T22:01:00Z"}) {
			var lease = JsonParser.parseString("{\"schema\":1,\"session\":\"" + ID + "\",\"expiresUtc\":\"" + time + "\"}").getAsJsonObject();
			assertThrows(IllegalArgumentException.class, () -> AssistantProtocol.session(lease, NOW));
		}
		var request = JsonParser.parseString("{\"id\":1,\"session\":\"foreign\",\"action\":\"join\"}").getAsJsonObject();
		assertThrows(IllegalArgumentException.class, () -> AssistantProtocol.request(request, ID));
	}
	@Test void rejectsShellPathAndInvalidRunId() {
		for (String command : new String[] {"exec", "read_file", "delete", "powershell"}) {
			var request = JsonParser.parseString("{\"id\":1,\"session\":\"" + ID + "\",\"action\":\"" + command + "\"}").getAsJsonObject();
			assertThrows(IllegalArgumentException.class, () -> AssistantProtocol.request(request, ID));
		}
		assertThrows(IllegalArgumentException.class, () -> AssistantProtocol.runId("../secret"));
	}
}
