// EasyLAN by XiaoXianHW / Darf; YS adaptation for Minecraft 26.3 (2026-10-05).
// SPDX-License-Identifier: GPL-3.0-only
package org.xiaoxian.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import org.xiaoxian.EasyLAN;
import org.xiaoxian.gui.GuiShareToLanEdit;
import org.xiaoxian.gui.GuiWorldSelectionEdit;

public class EasyLanClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        GuiShareToLanEdit.PortText = EasyLAN.CustomPort;
        GuiShareToLanEdit.MaxPlayerText = EasyLAN.CustomMaxPlayer;

        ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
            org.xiaoxian.lan.LanDiagnostics.event("screen_init", "screen", screen.getClass().getSimpleName());
            GuiWorldSelectionEdit.maybeReplace(client, screen);
            GuiShareToLanEdit.maybeReplace(client, screen);
        });
    }
}
