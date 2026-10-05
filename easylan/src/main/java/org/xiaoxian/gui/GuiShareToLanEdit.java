// EasyLAN by XiaoXianHW / Darf; YS adaptation for Minecraft 26.3 (2026-10-05).
// SPDX-License-Identifier: GPL-3.0-only
package org.xiaoxian.gui;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
public final class GuiShareToLanEdit {
    public static String PortText;
    public static String MaxPlayerText;
    public static void maybeReplace(Minecraft minecraft, Screen screen) {
        if (screen instanceof PauseScreen) {
            Screens.getWidgets(screen).add(Button.builder(Component.translatable("easylan.ys.menu"), ignored ->
                minecraft.gui.setScreen(new EasyLanPublishScreen(screen)))
                .bounds(5, screen.height - 25, 115, 20).build());
        }
        if (screen instanceof net.minecraft.client.gui.screens.TitleScreen
                || screen instanceof net.minecraft.client.gui.screens.DisconnectedScreen) {
            Screens.getWidgets(screen).add(Button.builder(Component.translatable("easylan.ys.report"), ignored -> {
                try {
                    var path=org.xiaoxian.lan.LanDiagnostics.export(minecraft);
                    minecraft.keyboardHandler.setClipboard(path.toAbsolutePath().toString());
                    minecraft.showDebugChat(Component.translatable("easylan.ys.reportSaved"));
                } catch(java.io.IOException e) {
                    org.slf4j.LoggerFactory.getLogger("EasyLAN").warn("Cannot save report",e);
                    minecraft.showDebugChat(Component.translatable("easylan.ys.reportError"));
                }
            }).bounds(5,screen.height-25,115,20).build());
        }
    }
}
