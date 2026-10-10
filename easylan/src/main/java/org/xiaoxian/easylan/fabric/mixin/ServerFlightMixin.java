// EasyLAN by XiaoXianHW / Darf; YS adaptation for Minecraft 26.3 (2026-10-05).
// SPDX-License-Identifier: GPL-3.0-only
package org.xiaoxian.easylan.fabric.mixin;
import net.minecraft.server.MinecraftServer;
import net.minecraft.client.server.IntegratedServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.xiaoxian.EasyLAN;
@Mixin(MinecraftServer.class)
public abstract class ServerFlightMixin {
    @Inject(method="allowFlight",at=@At("HEAD"),cancellable=true)
    private void easylan$flight(CallbackInfoReturnable<Boolean> cir) {
        if((Object)this instanceof IntegratedServer) cir.setReturnValue(EasyLAN.allowFlight);
    }
}
