package dev.skycraft.combat;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import dev.skycraft.link.Proto;
class DamageBalanceTest {
    @Test void giantMeleeCannotOneShotBeforeArmorButOrdinaryHitsKeepTheirScale() {
        assertEquals(12f,DamageBalance.convert(Proto.HURT_MELEE,178.48f,5,12));
        assertEquals(3f,DamageBalance.convert(Proto.HURT_MELEE,15f,5,12));
        assertEquals(12f,DamageBalance.convert(Proto.HURT_PROJECTILE,10000,5,12));
    }
    @Test void invalidNetworkValuesCannotDamagePlayer() {
        assertEquals(0,DamageBalance.convert(Proto.HURT_MELEE,Float.NaN,5,12));
        assertEquals(0,DamageBalance.convert(Proto.HURT_MELEE,Float.POSITIVE_INFINITY,5,12));
        assertEquals(0,DamageBalance.convert(Proto.HURT_MELEE,-100,5,12));
        assertEquals(3,DamageBalance.convert(Proto.HURT_MELEE,15,Float.NaN,Float.NaN));
    }
}
