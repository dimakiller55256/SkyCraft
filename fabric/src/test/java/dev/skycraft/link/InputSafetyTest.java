package dev.skycraft.link;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class InputSafetyTest {
    @Test void stalledOrInvalidHeartbeatCannotKeepKeysPressed() {
        assertTrue(InputSafety.fresh(0)); assertTrue(InputSafety.fresh(349));
        assertFalse(InputSafety.fresh(350)); assertFalse(InputSafety.fresh(6000));
        assertFalse(InputSafety.fresh(-1));
    }
    @Test void blockedInputStillDeliversDamageAndRelease() {
        for (int type : new int[]{Proto.IN_KEY, Proto.IN_MOUSE_BUTTON, Proto.IN_SCROLL,
                Proto.IN_CURSOR, Proto.IN_TEXT, Proto.IN_OPEN_MENU}) {
            assertFalse(InputSafety.accept(type, false)); assertTrue(InputSafety.accept(type, true));
        }
        assertTrue(InputSafety.accept(Proto.IN_HURT, false));
        assertTrue(InputSafety.accept(Proto.IN_RELEASE_ALL, false));
    }
}
