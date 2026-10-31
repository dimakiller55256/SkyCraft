package dev.skycraft.world;

import static org.junit.jupiter.api.Assertions.*;

import dev.skycraft.link.SkyLink;
import dev.skycraft.net.ActorSync;
import io.netty.buffer.Unpooled;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import org.junit.jupiter.api.Test;

class SkyWorldsTest {
	@Test
	void spiralGivesEverySquareOnceAndRingsInOrder() {
		Set<Long> seen = new HashSet<>();
		int previousRing = 0;
		for (int i = 0; i < 25 * 25; i++) {
			int[] s = SkyWorlds.spiral(i);
			assertTrue(seen.add(((long) s[0] << 32) ^ (s[1] & 0xFFFFFFFFL)), "square " + i + " repeats");
			int ring = Math.max(Math.abs(s[0]), Math.abs(s[1]));
			assertTrue(ring == previousRing || ring == previousRing + 1, "square " + i + " skips a ring");
			previousRing = ring;
		}
		assertArrayEquals(new int[] { 0, 0 }, SkyWorlds.spiral(0));
		assertArrayEquals(new int[] { 0, 0 }, SkyWorlds.spiral(-5));
		assertEquals(12, previousRing); // 25 x 25 squares fill exactly the rings up to 12
	}

	@Test
	void keysDoNotDependOnLoadOrder() {
		// Dawnguard's cell 0x02001234 in another load order is 0x05001234: same plugin, same local ID.
		assertEquals("dawnguard.esm:001234", SkyWorlds.key("Dawnguard.esm", 0x001234, 0x02001234));
		assertEquals(SkyWorlds.key("Dawnguard.esm", 0x001234, 0x02001234), SkyWorlds.key("Dawnguard.esm", 0x001234, 0x05001234));
		assertEquals(SkyWorlds.TAMRIEL, SkyWorlds.key("Skyrim.esm", 0x3C, 0x3C));
		assertEquals("id:ff000812", SkyWorlds.key("", 0, 0xFF000812));
		assertTrue(SkyWorlds.validKey(SkyWorlds.TAMRIEL));
		assertFalse(SkyWorlds.validKey(""));
		assertFalse(SkyWorlds.validKey("no-colon"));
		assertFalse(SkyWorlds.validKey("x:" + "a".repeat(200)));
		assertFalse(SkyWorlds.validKey("a\":b"));
	}

	@Test
	void originsRoundTripAndContain() {
		var tamriel = new SkyOrigin.Origin(0x3C, 0, 0);
		var interior = new SkyOrigin.Origin(0x1A26F, -2 * SkyWorlds.SLOT_BLOCKS, SkyWorlds.SLOT_BLOCKS);
		double x = -3571.25, z = 2911.5;
		assertEquals(x, tamriel.toMcX(x));
		assertEquals(x, interior.toSkyrimX(interior.toMcX(x)));
		assertEquals(z, interior.toSkyrimZ(interior.toMcZ(z)));
		assertTrue(interior.contains(interior.toMcX(x), interior.toMcZ(z)));
		assertFalse(tamriel.contains(interior.toMcX(x), interior.toMcZ(z)));
		assertTrue(tamriel.contains(x, z));
	}

	@Test
	void trianglesMoveExactlyIntoTheirSquare() {
		float[] v = { 1.25f, 2.0f, -3.5f, 2.25f, 2.0f, -3.5f, 1.25f, 3.0f, -2.5f };
		var local = new SkyTri(v, 0, 0);
		var placed = new SkyTri(v, 0, 0, 3.0 * SkyWorlds.SLOT_BLOCKS, -5.0 * SkyWorlds.SLOT_BLOCKS);
		assertEquals(local.ax + 3.0 * SkyWorlds.SLOT_BLOCKS, placed.ax);
		assertEquals(local.cz - 5.0 * SkyWorlds.SLOT_BLOCKS, placed.cz);
		assertEquals(local.ny, placed.ny, 1e-12);
		var back = placed.shifted(-3.0 * SkyWorlds.SLOT_BLOCKS, 5.0 * SkyWorlds.SLOT_BLOCKS);
		assertEquals(local.ax, back.ax);
		assertEquals(local.minZ, back.minZ);
		assertEquals(local.walkable, back.walkable);
	}

	@Test
	void guestActorsKeepFarPositionsExactly() {
		var b = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
		try {
			var actor = new SkyLink.Actor(0x1A694, 1, 4.0 * SkyWorlds.SLOT_BLOCKS + 0.0625, 70.5, -6.0 * SkyWorlds.SLOT_BLOCKS - 0.125, 90.0f, 0.6f, 1.8f, 0.5f, 12, "Bandit");
			var p = new ActorSync.Actors(List.of(actor));
			ActorSync.Actors.CODEC.encode(b, p);
			assertEquals(p, ActorSync.Actors.CODEC.decode(b));
			assertEquals(0, b.readableBytes());
		} finally {
			b.release();
		}
	}
}
