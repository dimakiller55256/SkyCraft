package dev.skycraft.mixin;

import dev.skycraft.items.ItemConversion;
import net.minecraft.world.Container;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Outbox displays can only be collected through the owner's durable pickup. */
@Mixin(HopperBlockEntity.class)
public abstract class HopperBlockEntityMixin {
    @Inject(method = "addItem(Lnet/minecraft/world/Container;Lnet/minecraft/world/entity/item/ItemEntity;)Z",
        at = @At("HEAD"), cancellable = true)
    private static void skycraft$protectOutbox(Container container, ItemEntity item,
                                              CallbackInfoReturnable<Boolean> callback) {
        if (item instanceof ItemConversion.OverflowItem) callback.setReturnValue(false);
    }
}
