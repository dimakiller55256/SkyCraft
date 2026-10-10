package dev.skycraft.net;

import io.netty.buffer.Unpooled;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TestSessionCodecTest {
	@Test void encodesAndDecodesBoundedSessionMetadata() {
		var bytes = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
		try {
			var original = new SkyNet.TestSession("A20261004_123456_abcdef", "0.1.2-ys.network.4");
			SkyNet.TestSession.CODEC.encode(bytes, original);
			assertEquals(original, SkyNet.TestSession.CODEC.decode(bytes));
			assertEquals(0, bytes.readableBytes());
		} finally { bytes.release(); }
	}
	@Test void rejectsOversizedServerMetadata() {
		var bytes = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
		try { assertThrows(RuntimeException.class, () -> SkyNet.TestSession.CODEC.encode(bytes, new SkyNet.TestSession("x".repeat(65), "v4"))); }
		finally { bytes.release(); }
	}
}
