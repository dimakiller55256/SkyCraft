// EasyLAN by XiaoXianHW / Darf; YS adaptation for Minecraft 26.3 (2026-10-05).
// SPDX-License-Identifier: GPL-3.0-only
package org.xiaoxian.lan;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.server.MinecraftServer;
import org.xiaoxian.EasyLAN;
import org.xiaoxian.easylan.core.validation.ValidationRules;
import org.xiaoxian.util.ConfigUtil;
import org.xiaoxian.util.ChatUtil;
import net.minecraft.network.chat.Component;
/** One vanilla listener; select authentication before accepting connections. */
public final class ShareToLan {
    public static boolean publish(IntegratedServer server,int port,int players,boolean online) {
        if (!ValidationRules.isValidPort(port)||!ValidationRules.isValidMaxPlayer(players))
            throw new IllegalArgumentException("Invalid LAN port or player count");
        if (server.isPublished()) throw new IllegalStateException("LAN already open; close and reopen the world");
        boolean previousAuth=server.usesAuthentication(); String previousMax=EasyLAN.CustomMaxPlayer;
        EasyLAN.CustomMaxPlayer=Integer.toString(players); server.setUsesAuthentication(online);
        if (!server.publishServer(MinecraftServer.MultiplayerScope.LAN,false,port)) {
            server.setUsesAuthentication(previousAuth); EasyLAN.CustomMaxPlayer=previousMax;
            LanDiagnostics.event("publish_failed","port",port); return false;
        }
        EasyLAN.onlineMode=online; EasyLAN.CustomPort=Integer.toString(port); ConfigUtil.save();
        server.execute(()->{
            ServerRuleApplier.apply(server);
            if (EasyLAN.HttpAPI) {
                try { updateApi(server); EasyLAN.getRuntimeState().startHttpApi(); }
                catch (java.io.IOException e) { LanDiagnostics.event("http_error","type",e.getClass().getSimpleName()); }
            }
        });
        EasyLAN.getRuntimeState().setShared(true); EasyLAN.getRuntimeState().setLanPort(Integer.toString(port));
        LanDiagnostics.event("lan_open","port",port,"authentication",online?"ONLINE":"OFFLINE","maxPlayers",players);
        if(EasyLAN.LanOutput) ChatUtil.sendComponentMsg(Component.translatable("easylan.ys.opened",port,online?"ONLINE":"OFFLINE"));
        return true;
    }
    public static void updateApi(IntegratedServer server) {
        var snapshot=EasyLAN.getRuntimeState().getStatusSnapshot();
        snapshot.putStatus("port",Integer.toString(server.getPort())); snapshot.putStatus("version",server.getServerVersion());
        snapshot.putStatus("onlineMode",Boolean.toString(server.usesAuthentication()));
        snapshot.putStatus("onlinePlayer",Integer.toString(server.getPlayerCount()));
        snapshot.putStatus("maxPlayer",Integer.toString(server.getMaxPlayers()));
        snapshot.replacePlayers(java.util.List.of(server.getPlayerNames()));
    }
    public void handleStop() { EasyLAN.getRuntimeState().shutdownAll(); LanDiagnostics.event("world_closed"); }
    public static String getLanPort() { return EasyLAN.getRuntimeState().getLanPort(); }
}
