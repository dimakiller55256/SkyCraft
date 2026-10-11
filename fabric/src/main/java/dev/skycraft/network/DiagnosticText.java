package dev.skycraft.network;

/** Disconnect messages are useful; credentials, URLs and control characters are not. */
public final class DiagnosticText {
	private DiagnosticText() { }
	public static String safe(String text) {
		if (text == null) return "";
		String value = text.substring(0, Math.min(text.length(), 4096));
		value = value.replaceAll("(?i)(?:https?|tcp|socks5)://\\S+", "[url]");
		value = value.replaceAll("(?i)(?:authorization|proxy-authorization)\\s*[:=]\\s*[^\\r\\n]+", "[authorization]");
		value = value.replaceAll("(?i)(?:access[_-]?token|refresh[_-]?token|password|secret|session[_-]?id)\\s*[:=]\\s*[^\\s,;]+", "[secret]");
		value = value.replaceAll("[A-Za-z0-9_-]{16,}\\.[A-Za-z0-9_-]{16,}\\.[A-Za-z0-9_-]{8,}", "[token]");
		value = value.replaceAll("[\\p{Cntrl}]+", " ").trim();
		return value.substring(0, Math.min(value.length(), 240));
	}
}
