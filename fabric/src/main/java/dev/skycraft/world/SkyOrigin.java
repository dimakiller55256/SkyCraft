package dev.skycraft.world;

import org.jspecify.annotations.Nullable;

/**
 * Where this machine's Skyrim world sits in the Minecraft map. Skyrim speaks in its own world's
 * coordinates (everything in the shared-memory protocol); the server gives every Skyrim world its own
 * square of the map (SkyWorlds), so a Minecraft position is the Skyrim one plus that square's origin.
 * Until the server has answered for the world Skyrim is in, nothing crosses between the two.
 */
public final class SkyOrigin {
	/** Skyrim world {@code worldId} starts at block ({@code x}, 0, {@code z}) of the Minecraft map. */
	public record Origin(int worldId, int x, int z) {
		public double toMcX(double skyrimX) {
			return skyrimX + this.x;
		}

		public double toMcZ(double skyrimZ) {
			return skyrimZ + this.z;
		}

		public double toSkyrimX(double mcX) {
			return mcX - this.x;
		}

		public double toSkyrimZ(double mcZ) {
			return mcZ - this.z;
		}

		/** True if a Minecraft position lies in this world's square. */
		public boolean contains(double mcX, double mcZ) {
			double half = SkyWorlds.SLOT_BLOCKS / 2.0;
			return Math.abs(mcX - this.x) < half && Math.abs(mcZ - this.z) < half;
		}
	}

	private static volatile @Nullable Origin current;

	private SkyOrigin() {
	}

	/** The origin of the world Skyrim is in, if the server has placed it; one consistent snapshot. */
	public static @Nullable Origin current() {
		return current;
	}

	/** The origin for {@code worldId}, or null while that world has no place yet. */
	public static @Nullable Origin of(int worldId) {
		Origin o = current;
		return o != null && worldId != 0 && o.worldId() == worldId ? o : null;
	}

	public static boolean ready(int worldId) {
		return of(worldId) != null;
	}

	public static void set(int worldId, int x, int z) {
		current = new Origin(worldId, x, z);
	}

	/** A new server (joined or left a friend's world): every world needs placing again. */
	public static void clear() {
		current = null;
	}
}
