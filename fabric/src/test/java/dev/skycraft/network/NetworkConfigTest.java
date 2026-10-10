package dev.skycraft.network;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NetworkConfigTest {
	@TempDir Path directory;

	@Test void preservesExistingSettingsAndUsesDirectByDefault() throws Exception {
		Path file = directory.resolve("skycraft.properties");
		String old = "join=friend.example:25570\ndestruction=false\n# Своя настройка\n";
		Files.writeString(file, old);
		var config = NetworkConfig.load(file);
		assertEquals("friend.example:25570", config.join());
		assertEquals(NetworkConfig.Mode.DIRECT, config.mode());
		assertEquals(old, Files.readString(file));
	}

	@Test void createsUtf8TemplateAndHidesCredentials() throws Exception {
		Path file = directory.resolve("config/skycraft.properties");
		assertEquals(NetworkConfig.Mode.DIRECT, NetworkConfig.load(file).mode());
		assertTrue(Files.readString(file).contains("Прокси"));
		Properties props = new Properties();
		props.setProperty("network.mode", "SOCKS5");
		props.setProperty("network.proxy.username", "private-user");
		props.setProperty("network.proxy.password", "private-password");
		String summary = NetworkConfig.fromProperties(props).toString();
		assertFalse(summary.contains("private-user"));
		assertFalse(summary.contains("private-password"));
	}

	@Test void rejectsBadModeTimeoutAndInjectedCredentials() {
		Properties props = new Properties();
		props.setProperty("network.mode", "SYSTEM");
		assertThrows(IllegalArgumentException.class, () -> NetworkConfig.fromProperties(props));
		props.setProperty("network.mode", "DIRECT");
		props.setProperty("network.timeoutMillis", "0");
		assertThrows(IllegalArgumentException.class, () -> NetworkConfig.fromProperties(props));
		props.remove("network.timeoutMillis");
		props.setProperty("network.proxy.password", "pass\r\nInjected: true");
		assertThrows(IllegalArgumentException.class, () -> NetworkConfig.fromProperties(props));
	}
}
