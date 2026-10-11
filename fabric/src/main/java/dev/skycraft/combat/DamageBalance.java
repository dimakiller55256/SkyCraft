package dev.skycraft.combat;

/** Bound bridge units before Minecraft applies its armor/shield/enchantment rules. */
public final class DamageBalance {
    private DamageBalance() {}
    public static float convert(int kind, float skyrimDamage, float scale, float meleeLimit) {
        if (!Float.isFinite(skyrimDamage) || skyrimDamage<=0) return 0;
        if (!Float.isFinite(scale) || scale<=0) scale=5;
        if (!Float.isFinite(meleeLimit) || meleeLimit<=0) meleeLimit=12;
        float amount=skyrimDamage/scale;
        return kind==dev.skycraft.link.Proto.HURT_MELEE || kind==dev.skycraft.link.Proto.HURT_PROJECTILE
            ? Math.min(amount,meleeLimit) : Math.min(amount,200);
    }
}
