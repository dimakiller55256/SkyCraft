package dev.skycraft.network;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

class EndpointTest {
	@Test void pastedLinksAndIpv6() {
		assertEquals("example.org:25565", Endpoint.parse(" https://EXAMPLE.org/ ").authority());
		assertEquals("example.org:25570", Endpoint.parse("minecraft://example.org:25570").authority());
		assertEquals("[::1]:25565", Endpoint.parse("[::1]").authority());
		assertEquals("[2001:db8::1]:25570", Endpoint.parse("[2001:db8::1]:25570").authority());
		assertEquals("xn--e1afmkfd.xn--p1ai:25565", Endpoint.parse("пример.рф").authority());
	}

	@ParameterizedTest @ValueSource(strings = {"", "host:0", "host:65536", "host:-1", "host:",
		"host:25x", "user:password@host", "host/path", "host?token=secret", "host#fragment",
		"host\r\nInjected: value", "::1", "[::1", "[::1]suffix", "[host]:12", "socks5://host"})
	void rejectsAmbiguousAndUnsafeAddresses(String text) {
		assertThrows(IllegalArgumentException.class, () -> Endpoint.parse(text));
	}
}
