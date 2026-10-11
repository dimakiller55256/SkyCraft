package dev.skycraft.network;

import dev.skycraft.net.ActorSync.WorldContext;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PeerWorldTest {
    @Test void exteriorAndInteriorCannotShareGeometry() {
        assertFalse(new WorldContext(0x3c,true).permits(0x1a26f));
    }
    @Test void sameLocationRestoresPermissionWithoutChangingSaves() {
        var host=new WorldContext(0x3c,true);
        assertFalse(host.permits(0x1a26f));
        assertTrue(host.permits(0x3c));
    }
    @Test void unknownWorldIsNeverEnoughEvenIfIdsMatch() {
        assertFalse(new WorldContext(0,true).permits(0));
    }
    @Test void loadingHostMustHoldGuestEvenInSameLocation() {
        assertFalse(new WorldContext(0x3c,false).permits(0x3c));
    }
}
