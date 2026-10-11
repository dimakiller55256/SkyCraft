// EasyLAN by XiaoXianHW / Darf; YS adaptation for Minecraft 26.3 (2026-10-05).
// SPDX-License-Identifier: GPL-3.0-only
package org.xiaoxian.lan;

import net.minecraft.server.MinecraftServer;

public class ServerStopping {
    public static void onServerStopping(MinecraftServer minecraftServer) {
        if (minecraftServer.isSingleplayer()) {
            new ShareToLan().handleStop();
        }
    }
}
