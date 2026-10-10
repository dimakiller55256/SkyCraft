package dev.skycraft.network;

import com.google.gson.JsonObject;
import java.time.Instant;
import java.util.Set;

/** Small local, opt-in file protocol. No listener, shell, arbitrary path or remote commands. */
public final class AssistantProtocol {
	private static final Set<String> ACTIONS = Set.of("start", "host", "probe", "join", "leave", "transport", "configure", "restore", "stop", "items-world", "items-profile");
	private AssistantProtocol() { }
	public static String session(JsonObject lease, Instant now) {
		if (lease.get("schema").getAsInt() != 1) throw new IllegalArgumentException("schema");
		String id = lease.get("session").getAsString();
		if (!id.matches("[a-f0-9]{32}")) throw new IllegalArgumentException("session");
		Instant expires = Instant.parse(lease.get("expiresUtc").getAsString());
		if (!expires.isAfter(now) || expires.isAfter(now.plusSeconds(30))) throw new IllegalArgumentException("lease expired");
		return id;
	}
	public static long request(JsonObject request, String session) {
		if (!session.equals(request.get("session").getAsString())) throw new IllegalArgumentException("wrong session");
		long id = request.get("id").getAsLong();
		if (id < 1 || id > 9_000_000_000_000L) throw new IllegalArgumentException("id");
		if (!ACTIONS.contains(request.get("action").getAsString())) throw new IllegalArgumentException("action");
		return id;
	}
	public static String runId(String id) {
		if (!id.matches("[A-Za-z0-9_-]{1,64}")) throw new IllegalArgumentException("run ID");
		return id;
	}
}
