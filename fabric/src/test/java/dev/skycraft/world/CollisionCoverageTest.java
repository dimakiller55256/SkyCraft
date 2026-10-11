package dev.skycraft.world;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class CollisionCoverageTest {
    @Test void sweptMovementRequiresEveryRegionIncludingNegativeCoordinates() {
        assertTrue(CollisionCoverage.ready(-8,-8,-8,8,8,8,(x,y,z)->x>=-1&&x<=0&&y>=-1&&y<=0&&z>=-1&&z<=0));
        assertFalse(CollisionCoverage.ready(-8.01,-8,-8,8,8,8,(x,y,z)->x>=-1));
        assertFalse(CollisionCoverage.ready(-8,-8,-8,8.01,8,8,(x,y,z)->x<=0));
    }
    @Test void streamedEmptySpaceIsKnownAndDoesNotBecomeAnInvisibleFloor() {
        assertTrue(CollisionCoverage.ready(1,-12,1,2,-10,2,(x,y,z)->true));
        assertFalse(CollisionCoverage.ready(1,-12,1,2,-10,2,(x,y,z)->y!=-2));
    }
    @Test void invalidOrUnboundedQueriesCannotLoopOrAcceptMovement() {
        assertFalse(CollisionCoverage.ready(Double.NaN,0,0,1,1,1,(x,y,z)->true));
        assertFalse(CollisionCoverage.ready(0,0,0,1e20,1,1,(x,y,z)->true));
        assertFalse(CollisionCoverage.ready(0,0,0,1000,1000,1000,(x,y,z)->true));
    }
}
