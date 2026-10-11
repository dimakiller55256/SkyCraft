package dev.skycraft.link;

/** A replacement player must receive Skyrim's position before it can drive Skyrim. */
public final class PlayerSyncGate {
	private Object player;
	private int sequence;
	private boolean initialized;
	public boolean observe(Object current, int teleportSequence) {
		if (current == null || current != player || teleportSequence != sequence) {
			player = current; sequence = teleportSequence; initialized = false;
		}
		return current != null && initialized;
	}
	public void initialized(Object current, int teleportSequence) {
		if (current != null && current == player && teleportSequence == sequence) initialized = true;
	}
	public void invalidate() { player = null; initialized = false; }
}
