package dev.skycraft.link;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PlayerSyncGateTest {
	@Test void newPlayerBetweenFrameAndPublicationCannotDriveSkyrim() {
		var gate = new PlayerSyncGate(); Object old = new Object(), replacement = new Object();
		assertFalse(gate.observe(old, 7)); gate.initialized(old, 7); assertTrue(gate.observe(old, 7));
		assertFalse(gate.observe(replacement, 7)); // Same teleport ack, but a different world/player.
		gate.initialized(old, 7); assertFalse(gate.observe(replacement, 7));
		gate.initialized(replacement, 7); assertTrue(gate.observe(replacement, 7));
	}
	@Test void sequenceChangeAndWorldGapRequireFreshInitialization() {
		var gate = new PlayerSyncGate(); Object p = new Object();
		gate.observe(p, Integer.MAX_VALUE); gate.initialized(p, Integer.MAX_VALUE);
		assertFalse(gate.observe(p, Integer.MIN_VALUE)); gate.initialized(p, Integer.MAX_VALUE);
		assertFalse(gate.observe(p, Integer.MIN_VALUE)); gate.initialized(p, Integer.MIN_VALUE);
		assertTrue(gate.observe(p, Integer.MIN_VALUE)); assertFalse(gate.observe(null, Integer.MIN_VALUE));
		assertFalse(gate.observe(p, Integer.MIN_VALUE));
	}
	@Test void explicitLeaveBlocksPublicationEvenIfOldPlayerIsStillPresent() {
		var gate = new PlayerSyncGate(); Object p = new Object(); gate.observe(p, 0); gate.initialized(p, 0);
		gate.invalidate(); assertFalse(gate.observe(p, 0));
	}
}
