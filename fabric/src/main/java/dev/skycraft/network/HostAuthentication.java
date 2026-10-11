package dev.skycraft.network;

/** Explicit host choice; offline profiles are offered only for a private test network. */
public enum HostAuthentication {
	ONLINE, OFFLINE;

	public static HostAuthentication requested(String value, boolean privateNetwork) {
		HostAuthentication selected;
		try { selected = value == null || value.isBlank() ? ONLINE : valueOf(value); }
		catch (IllegalArgumentException e) { throw new IllegalArgumentException("Режим входа: ONLINE или OFFLINE."); }
		if (selected == OFFLINE && !privateNetwork) {
			throw new IllegalArgumentException("Автономные профили доступны для частной LAN/Radmin. Выберите общую виртуальную или локальную сеть.");
		}
		return selected;
	}
}
