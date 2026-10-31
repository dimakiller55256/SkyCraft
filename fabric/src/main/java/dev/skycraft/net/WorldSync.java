package dev.skycraft.net;

import dev.skycraft.SkyCraft;
import dev.skycraft.world.SkyWorlds;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Each client tells the server which Skyrim world its Skyrim is in; the server answers with that
 * world's square of the map (SkyWorlds). The same exchange runs in a single-player world, where the
 * server is the integrated one.
 */
public final class WorldSync {
	private WorldSync() {
	}

	/** Client -> server: my Skyrim entered this world. */
	public record Enter(String key, int worldId) implements CustomPacketPayload {
		public static final Type<Enter> TYPE = new Type<>(Identifier.fromNamespaceAndPath(SkyCraft.MOD_ID, "world_enter_v1"));
		public static final StreamCodec<RegistryFriendlyByteBuf, Enter> CODEC = StreamCodec.composite(
			ByteBufCodecs.stringUtf8(96), Enter::key, ByteBufCodecs.INT, Enter::worldId, Enter::new);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/** Server -> client: that world's square starts at block (x, 0, z). */
	public record Slot(String key, int worldId, int x, int z) implements CustomPacketPayload {
		public static final Type<Slot> TYPE = new Type<>(Identifier.fromNamespaceAndPath(SkyCraft.MOD_ID, "world_slot_v1"));
		public static final StreamCodec<RegistryFriendlyByteBuf, Slot> CODEC = StreamCodec.composite(
			ByteBufCodecs.stringUtf8(96), Slot::key, ByteBufCodecs.INT, Slot::worldId, ByteBufCodecs.INT, Slot::x, ByteBufCodecs.INT, Slot::z, Slot::new);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	public static void init() {
		PayloadTypeRegistry.serverboundPlay().register(Enter.TYPE, Enter.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(Slot.TYPE, Slot.CODEC);
		ServerPlayNetworking.registerGlobalReceiver(Enter.TYPE, (payload, context) -> {
			if (!SkyWorlds.validKey(payload.key())) {
				SkyCraft.LOG.warn("SkyCraft: {} sent an invalid Skyrim world key", context.player().getPlainTextName());
				return;
			}
			context.server().execute(() -> {
				int[] origin = SkyWorlds.originFor(context.server(), payload.key());
				if (ServerPlayNetworking.canSend(context.player(), Slot.TYPE)) {
					ServerPlayNetworking.send(context.player(), new Slot(payload.key(), payload.worldId(), origin[0], origin[1]));
				}
			});
		});
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> SkyWorlds.forget());
	}
}
