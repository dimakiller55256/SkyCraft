package dev.skycraft.network;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Properties;

/** Explicit, per-instance settings. Never changes system routes, DNS or proxy settings. */
public record NetworkConfig(String join, Mode mode, Endpoint proxy, String username, String password,
	int timeoutMillis, int hostPort, String advertisedAddress) {
	public enum Mode { DIRECT, SOCKS5, HTTP_CONNECT }

	public static NetworkConfig load(Path file) throws IOException {
		if (!Files.exists(file)) {
			Files.createDirectories(file.getParent());
			try {
				Files.writeString(file, """
					# SkyCraft: адрес друга (пусто = свой мир).
					join=
					# DIRECT, SOCKS5 или HTTP_CONNECT. Прокси задаётся явно, только для игры.
					network.mode=DIRECT
					network.proxy=127.0.0.1:1080
					network.proxy.username=
					network.proxy.password=
					network.timeoutMillis=10000
					# Порт для /skycraft host; адрес для приглашения Discord, если известен.
					network.hostPort=25565
					network.advertisedAddress=
					""", java.nio.file.StandardOpenOption.CREATE_NEW);
			} catch (java.nio.file.FileAlreadyExistsException ignored) { }
		}
		Properties props = new Properties();
		try (var reader = Files.newBufferedReader(file)) { props.load(reader); }
		return fromProperties(props);
	}

	public static NetworkConfig fromProperties(Properties props) {
		Mode mode;
		try { mode = Mode.valueOf(props.getProperty("network.mode", "DIRECT").trim().toUpperCase(Locale.ROOT)); }
		catch (IllegalArgumentException e) { throw new IllegalArgumentException("network.mode: выберите DIRECT, SOCKS5 или HTTP_CONNECT."); }
		Endpoint proxy = mode == Mode.DIRECT ? null : Endpoint.parse(
			props.getProperty("network.proxy", mode == Mode.SOCKS5 ? "127.0.0.1:1080" : "127.0.0.1:8080"), mode == Mode.SOCKS5 ? 1080 : 8080);
		String username = props.getProperty("network.proxy.username", "");
		String password = props.getProperty("network.proxy.password", "");
		if (username.chars().anyMatch(c -> c < 32) || password.chars().anyMatch(c -> c < 32)) {
			throw new IllegalArgumentException("Недопустимые символы в настройках авторизации прокси.");
		}
		String advertised = props.getProperty("network.advertisedAddress", "").trim();
		if (!advertised.isEmpty()) advertised = Endpoint.parse(advertised).authority();
		return new NetworkConfig(props.getProperty("join", "").trim(), mode, proxy, username, password,
			integer(props, "network.timeoutMillis", 10000, 1000, 60000),
			integer(props, "network.hostPort", 25565, 1, 65535), advertised);
	}

	private static int integer(Properties props, String key, int fallback, int min, int max) {
		try {
			int value = Integer.parseInt(props.getProperty(key, Integer.toString(fallback)).trim());
			if (value < min || value > max) throw new NumberFormatException();
			return value;
		} catch (NumberFormatException e) {
			throw new IllegalArgumentException(key + ": допустимы значения от " + min + " до " + max + ".");
		}
	}

	@Override public String toString() {
		return "NetworkConfig[mode=" + mode + ", proxy=" + proxy + ", timeoutMillis=" + timeoutMillis + "]";
	}
}
