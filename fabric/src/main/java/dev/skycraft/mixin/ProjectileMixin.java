package dev.skycraft.mixin;

import dev.skycraft.world.SkyClip;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.ProjectileDeflection;
import net.minecraft.world.phys.HitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Minecraft ignores projectile hits on air, and to Minecraft a Skyrim wall is air. Treat a hit on
 * Skyrim geometry as a real hit: arrows stick in it, snowballs and eggs break on it.
 */
@Mixin(Projectile.class)
public abstract class ProjectileMixin {
	@org.spongepowered.asm.mixin.injection.Inject(method="canHitEntity",at=@At("HEAD"),cancellable=true)
	private void skycraft$onlyOwnedActors(net.minecraft.world.entity.Entity target,org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<Boolean> cir) {
		var owner=((net.minecraft.world.entity.projectile.Projectile)(Object)this).getOwner();
		if(target instanceof dev.skycraft.combat.SkyrimActorEntity actor && owner instanceof net.minecraft.world.entity.player.Player && !actor.ownedBy(owner.getUUID())) cir.setReturnValue(false);
	}
	@Shadow
	protected abstract void onHit(HitResult hitResult);

	@Inject(method = "hitTargetOrDeflectSelf", at = @At("HEAD"), cancellable = true)
	private void skycraft$hitSkyrim(HitResult hitResult, CallbackInfoReturnable<ProjectileDeflection> cir) {
		if (hitResult instanceof SkyClip.SkyrimHitResult) {
			this.onHit(hitResult);
			cir.setReturnValue(ProjectileDeflection.NONE);
		}
	}
}
