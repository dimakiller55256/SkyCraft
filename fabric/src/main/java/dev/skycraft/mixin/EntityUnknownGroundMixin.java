package dev.skycraft.mixin;

import dev.skycraft.world.SkyCollision;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * This server only knows Skyrim's ground where its own Skyrim's player has been. A guest elsewhere
 * (another Skyrim world has its own square of the map) stands on ground the server never saw, so
 * items, mobs and drops there would fall through it and be lost. They stay where they are instead
 * until that ground is known; going up or sideways is left alone.
 */
@Mixin(Entity.class)
public abstract class EntityUnknownGroundMixin {
	@Inject(method = "move", at = @At("HEAD"), cancellable = true)
	private void skycraft$holdOverUnknownGround(MoverType type, Vec3 movement, CallbackInfo ci) {
		Entity self = (Entity) (Object) this;
		if (movement.y >= 0.0 || self instanceof Player || self.noPhysics || self.level().isClientSide() || !SkyCollision.active()) {
			return;
		}
		if (!SkyCollision.movementKnown(self.getBoundingBox().expandTowards(movement))) {
			self.setDeltaMovement(Vec3.ZERO);
			ci.cancel();
		}
	}
}
