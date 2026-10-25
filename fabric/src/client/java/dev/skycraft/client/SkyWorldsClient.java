package dev.skycraft.client;

import dev.skycraft.SkyCraft;
import dev.skycraft.link.SkyLink;
import dev.skycraft.net.WorldSync;
import dev.skycraft.world.SkyOrigin;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;

/**
 * Asks the server where the local Skyrim's world lies in the Minecraft map (WorldSync), whenever
 * Skyrim enters a world that has no place yet, and on every new server.
 */
final class SkyWorldsClient {
	// A server that never offers WorldSync this long after joining has no world squares (no SkyCraft,
	// or an older one): everything shares one space there, as it used to.
	private static final long CHANNELS_GRACE_MS = 5000;
	private static final long RETRY_MS = 3000;
	private static int requestedWorld;
	private static long requestedAt;
	private static long joinedAt;

	private SkyWorldsClient() {
	}

	static void init() {
		ClientPlayConnectionEvents.JOIN.register((handler, sender, minecraft) -> {
			reset();
			joinedAt = System.currentTimeMillis();
		});
		ClientPlayConnectionEvents.DISCONNECT.register((handler, minecraft) -> reset());
		ClientPlayNetworking.registerGlobalReceiver(WorldSync.Slot.TYPE, (payload, context) -> context.client().execute(() -> {
			if (payload.worldId() == SkyClient.sky().worldId) {
				SkyOrigin.set(payload.worldId(), payload.x(), payload.z());
				SkyCraft.LOG.info("SkyCraft: Skyrim world {} ({}) lies at {} {} of the Minecraft map", payload.key(),
					Integer.toHexString(payload.worldId()), payload.x(), payload.z());
			}
		}));
	}

	private static void reset() {
		SkyOrigin.clear();
		requestedWorld = 0;
	}

	/** Once a frame on the render thread, after Skyrim's state was read. */
	static void frame(Minecraft minecraft, SkyLink.SkyState sky) {
		int world = sky.worldId;
		if (!sky.inGame() || world == 0 || SkyOrigin.ready(world) || minecraft.getConnection() == null) {
			return;
		}
		long now = System.currentTimeMillis();
		if (requestedWorld == world && now - requestedAt < RETRY_MS) {
			return;
		}
		String key = SkyLink.readWorldKey(world);
		if (key == null) {
			return; // Skyrim describes the world right after entering it
		}
		if (!ClientPlayNetworking.canSend(WorldSync.Enter.TYPE)) {
			if (joinedAt != 0 && now - joinedAt > CHANNELS_GRACE_MS) {
				SkyOrigin.set(world, 0, 0);
				SkyCraft.LOG.info("SkyCraft: this server has no Skyrim world squares; world {} shares the map's middle", key);
			}
			return;
		}
		ClientPlayNetworking.send(new WorldSync.Enter(key, world));
		requestedWorld = world;
		requestedAt = now;
	}
}
