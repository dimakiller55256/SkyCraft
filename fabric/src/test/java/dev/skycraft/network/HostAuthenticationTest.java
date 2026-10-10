package dev.skycraft.network;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class HostAuthenticationTest {
	@Test void missingChoiceKeepsAuthentication() {
		assertEquals(HostAuthentication.ONLINE, HostAuthentication.requested(null, true));
		assertEquals(HostAuthentication.ONLINE, HostAuthentication.requested("", false));
		assertEquals(HostAuthentication.ONLINE, HostAuthentication.requested("ONLINE", false));
	}
	@Test void offlineNeedsAnExplicitPrivateNetwork() {
		assertEquals(HostAuthentication.OFFLINE, HostAuthentication.requested("OFFLINE", true));
		assertThrows(IllegalArgumentException.class, () -> HostAuthentication.requested("OFFLINE", false));
	}
	@Test void unknownChoiceCannotSilentlyDisableAuthentication() {
		assertThrows(IllegalArgumentException.class, () -> HostAuthentication.requested("false", true));
		assertThrows(IllegalArgumentException.class, () -> HostAuthentication.requested("UNKNOWN", false));
	}
}
