// EasyLAN by XiaoXianHW / Darf; YS adaptation for Minecraft 26.3 (2026-10-05).
// SPDX-License-Identifier: GPL-3.0-only
package org.xiaoxian.easylan.fabric.mixin;
import net.minecraft.server.network.ServerLoginPacketListenerImpl;
import net.minecraft.network.DisconnectionDetails;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.xiaoxian.lan.LanDiagnostics;
@Mixin(ServerLoginPacketListenerImpl.class)
public abstract class LoginDiagnosticsMixin {
    @Inject(method="onDisconnect",at=@At("HEAD"))
    private void easylan$loginDisconnect(DisconnectionDetails details,CallbackInfo ci) {
        LanDiagnostics.event("login_disconnect","reason",details.reason().getString());
    }
}
