package dev.skycraft.network;

import java.net.IDN;
import java.net.InetAddress;
import java.net.UnknownHostException;

/** A literal Minecraft endpoint; parsing never resolves a DNS name. */
public record Endpoint(String host, int port) {
	public Endpoint {
		if (port < 1 || port > 65535) {
			throw new IllegalArgumentException("Порт должен быть от 1 до 65535.");
		}
		if (host == null || host.isBlank() || host.chars().anyMatch(c -> Character.isWhitespace(c) || c < 32)
			|| host.matches(".*[/\\\\@?#\\[\\]].*")) {
			throw new IllegalArgumentException("Укажите имя сервера или IP без пути, пароля и параметров URL.");
		}
		if (host.contains(":")) {
			try {
				if (host.contains("%") || InetAddress.getByName(host).getAddress().length != 16) {
					throw new IllegalArgumentException("Некорректный IPv6-адрес.");
				}
			} catch (UnknownHostException e) {
				throw new IllegalArgumentException("Некорректный IPv6-адрес.");
			}
		} else {
			host = IDN.toASCII(host, IDN.USE_STD3_ASCII_RULES).toLowerCase(java.util.Locale.ROOT);
			if (host.length() > 253 || host.isEmpty()) {
				throw new IllegalArgumentException("Некорректное имя сервера.");
			}
		}
	}

	public static Endpoint parse(String text) {
		return parse(text, 25565);
	}

	public static Endpoint parse(String text, int defaultPort) {
		String value = text.trim();
		if (value.contains("://")) {
			int separator = value.indexOf("://");
			String scheme = value.substring(0, separator).toLowerCase(java.util.Locale.ROOT);
			if (!java.util.Set.of("http", "https", "minecraft", "skycraft").contains(scheme)) {
				throw new IllegalArgumentException("Неизвестная схема адреса сервера.");
			}
			value = value.substring(separator + 3);
		}
		value = value.replaceFirst("/+$", "");
		String host;
		String port = "";
		if (value.startsWith("[")) {
			int end = value.indexOf(']');
			if (end < 0) throw new IllegalArgumentException("IPv6 указывается как [адрес]:порт.");
			host = value.substring(1, end);
			if (!host.contains(":")) throw new IllegalArgumentException("Скобки предназначены для IPv6.");
			String suffix = value.substring(end + 1);
			if (!suffix.isEmpty()) {
				if (!suffix.startsWith(":")) throw new IllegalArgumentException("Некорректный адрес сервера.");
				port = suffix.substring(1);
				if (port.isEmpty()) throw new IllegalArgumentException("После двоеточия укажите порт.");
			}
		} else {
			int colon = value.indexOf(':');
			if (colon != value.lastIndexOf(':')) throw new IllegalArgumentException("IPv6 указывается как [адрес]:порт.");
			host = colon < 0 ? value : value.substring(0, colon);
			if (colon >= 0) {
				port = value.substring(colon + 1);
				if (port.isEmpty()) throw new IllegalArgumentException("После двоеточия укажите порт.");
			}
		}
		try {
			if (!port.isEmpty() && !port.matches("[0-9]{1,5}")) throw new NumberFormatException();
			return new Endpoint(host, port.isEmpty() ? defaultPort : Integer.parseInt(port));
		} catch (NumberFormatException e) {
			throw new IllegalArgumentException("Порт должен быть числом от 1 до 65535.");
		}
	}

	public String authority() {
		return (host.contains(":") ? "[" + host + "]" : host) + ":" + port;
	}

	@Override public String toString() { return authority(); }
}
