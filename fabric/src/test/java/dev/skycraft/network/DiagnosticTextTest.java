package dev.skycraft.network;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DiagnosticTextTest {
	@Test void keepsServerReasonButRemovesCredentialsAndUrls() {
		assertEquals("Invalid session", DiagnosticText.safe("Invalid session"));
		String value = DiagnosticText.safe("Denied password=hunter2 accessToken=abcd https://u:p@server/path?token=abc\nAuthorization: Bearer xyz");
		for (String secret : new String[] {"hunter2", "abcd", "u:p", "Bearer", "xyz"}) assertFalse(value.contains(secret));
	}
	@Test void boundsAndNormalizesUntrustedDisconnectText() {
		assertEquals("", DiagnosticText.safe(null)); assertEquals("a b", DiagnosticText.safe("a\nb"));
		assertEquals(240, DiagnosticText.safe("x".repeat(5000)).length());
	}
}
