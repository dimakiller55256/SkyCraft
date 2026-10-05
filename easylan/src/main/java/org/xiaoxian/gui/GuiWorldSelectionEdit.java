// EasyLAN by XiaoXianHW / Darf; YS adaptation for Minecraft 26.3 (2026-10-05).
// SPDX-License-Identifier: GPL-3.0-only
package org.xiaoxian.gui;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.worldselection.SelectWorldScreen;
import net.minecraft.network.chat.Component;
public final class GuiWorldSelectionEdit {
    public static void maybeReplace(Minecraft minecraft, Screen screen) {
        if (screen instanceof SelectWorldScreen) {
            Screens.getWidgets(screen).add(Button.builder(Component.literal("EasyLAN"), ignored ->
                minecraft.gui.setScreen(new GuiEasyLanMain(screen))).bounds(5, 5, 100, 20).build());
        }
    }
}
