// EasyLAN by XiaoXianHW / Darf; YS adaptation for Minecraft 26.3 (2026-10-05).
// SPDX-License-Identifier: GPL-3.0-only
package org.xiaoxian.util;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.regex.Pattern;

public class ChatUtil {
    private static final Minecraft MC = Minecraft.getInstance();
    private static final Pattern PATTERN = Pattern.compile("&([0-9a-fk-or])");

    public static void sendMsg(String msg) {
        msg = PATTERN.matcher(msg).replaceAll("\u00A7$1");
        sendComponentMsg(Component.nullToEmpty(msg));
    }

    public static void sendComponentMsg(Component component) {
        if (MC == null || component == null) {
            return;
        }

        MC.execute(() -> {
            if (MC.gui != null) {
                MC.gui.hud.getChat().addClientSystemMessage(component);
            }
        });
    }
}
