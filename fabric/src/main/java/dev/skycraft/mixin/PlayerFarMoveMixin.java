package dev.skycraft.mixin;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A guest going through a Skyrim door lands in another square of the map, and to the server that
 * is one move packet. Sweeping collision along it would visit every block of a box up to 16384
 * wide and freeze the server for half a minute: a move this long is a teleport, not a step.
 */
@Mixin(Entity.class)
public abstract class PlayerFarMoveMixin {
	private static final double SKYCRAFT_TELEPORT_DISTANCE = 128.0;

	@Inject(method = "move", at = @At("HEAD"), cancellable = true)
	private void skycraft$teleportIsNoStep(MoverType type, Vec3 movement, CallbackInfo ci) {
		Entity self = (Entity) (Object) this;
		if (self instanceof ServerPlayer && movement.lengthSqr() > SKYCRAFT_TELEPORT_DISTANCE * SKYCRAFT_TELEPORT_DISTANCE) {
			self.setPos(self.position().add(movement));
			ci.cancel();
		}
	}
}
