// EasyLAN by XiaoXianHW / Darf; YS adaptation for Minecraft 26.3 (2026-10-05).
// SPDX-License-Identifier: GPL-3.0-only
package org.xiaoxian.gui;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.xiaoxian.EasyLAN;
import org.xiaoxian.easylan.core.validation.ValidationRules;
import org.xiaoxian.lan.LanDiagnostics;
import org.xiaoxian.lan.ShareToLan;
import java.util.List;

public final class EasyLanPublishScreen extends Screen {
    private final Screen parent;
    private boolean online;
    private EditBox port,players;
    private Button publish;
    private Component status = Component.empty();
    private List<LanDiagnostics.Address> addresses;
    private int addressIndex;
    public EasyLanPublishScreen(Screen parent) { super(Component.translatable("easylan.ys.menu")); this.parent=parent; online=EasyLAN.onlineMode; }
    public void refreshFromSettings() { online=EasyLAN.onlineMode; }
    @Override protected void init() {
        int w=Math.min(360,width-20),left=(width-w)/2;
        var server=minecraft.getSingleplayerServer();
        if(server!=null && server.isPublished()) {
            addresses=LanDiagnostics.addresses();
            addRenderableWidget(Button.builder(addressLabel(server.getPort()),b->{
                addressIndex=(addressIndex+1)%addresses.size(); b.setMessage(addressLabel(server.getPort()));
            }).bounds(left,65,w,20).build());
            addRenderableWidget(Button.builder(Component.translatable("easylan.ys.copy"),b->{
                minecraft.keyboardHandler.setClipboard(addresses.get(addressIndex).endpoint(server.getPort()));
                status=Component.translatable("easylan.ys.copied");
            }).bounds(left,91,w,20).build());
            online=server.usesAuthentication();
            status=Component.translatable("easylan.ys.live",server.getPort(),online?"ONLINE":"OFFLINE",server.getPlayerCount());
        } else if(server!=null) {
            port=new EditBox(font,left,65,w/2-4,20,Component.translatable("easylan.text.port"));
            port.setMaxLength(5); port.setValue(EasyLAN.CustomPort);
            players=new EditBox(font,left+w/2+4,65,w/2-4,20,Component.translatable("easylan.text.maxplayer"));
            players.setMaxLength(6); players.setValue(EasyLAN.CustomMaxPlayer);
            addRenderableWidget(port); addRenderableWidget(players);
            Button auth=Button.builder(authLabel(),b->{online=!online; b.setMessage(authLabel());})
                .bounds(left,96,w,20).build();
            auth.setTooltip(Tooltip.create(Component.translatable("easylan.ys.authHint"))); addRenderableWidget(auth);
            publish=Button.builder(Component.translatable("easylan.ys.publish"),b->publish())
                .bounds(left,height-26,w/2-4,20).build(); addRenderableWidget(publish);
            port.setResponder(v->validate()); players.setResponder(v->validate()); validate();
        }
        addRenderableWidget(Button.builder(Component.translatable("easylan.setting"),b->minecraft.gui.setScreen(new GuiEasyLanMain(this)))
            .bounds(left,150,w,20).build());
        addRenderableWidget(Button.builder(Component.translatable("easylan.ys.report"),b->{
            try { var path=LanDiagnostics.export(minecraft); minecraft.keyboardHandler.setClipboard(path.toAbsolutePath().toString());
                status=Component.translatable("easylan.ys.reportSaved"); }
            catch(java.io.IOException e) {
                org.slf4j.LoggerFactory.getLogger("EasyLAN").warn("Cannot save report",e);
                status=Component.translatable("easylan.ys.reportError");
            }
        }).bounds(left,174,w,20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.back"),b->onClose())
            .bounds(left+w/2+4,height-26,w/2-4,20).build());
    }
    private Component authLabel() { return Component.translatable("easylan.ys.auth",online?"ONLINE":"OFFLINE"); }
    private Component addressLabel(int port) {
        var a=addresses.get(addressIndex);
        return Component.literal(a.endpoint(port)+" — "+a.adapter());
    }
    private void validate() {
        try { publish.active=ValidationRules.isValidPort(Integer.parseInt(port.getValue()))
            && ValidationRules.isValidMaxPlayer(Integer.parseInt(players.getValue())) && minecraft.getSingleplayerServer()!=null; }
        catch(NumberFormatException e) { publish.active=false; }
    }
    public void publish() {
        if(publish==null||!publish.active) return;
        try {
            if(ShareToLan.publish(minecraft.getSingleplayerServer(),Integer.parseInt(port.getValue()),Integer.parseInt(players.getValue()),online)) {
                minecraft.gui.setScreen(new EasyLanPublishScreen(parent));
            } else status=Component.translatable("easylan.ys.portBusy");
        } catch(RuntimeException e) {
            LanDiagnostics.event("publish_error","type",e.getClass().getSimpleName()); status=Component.translatable("easylan.ys.portBusy");
        }
    }
    @Override public void extractRenderState(GuiGraphicsExtractor g,int x,int y,float t) {
        super.extractRenderState(g,x,y,t); int w=Math.min(360,width-20),left=(width-w)/2;
        g.centeredText(font,title,width/2,12,0xFFFFFFFF);
        boolean live=minecraft.getSingleplayerServer()!=null&&minecraft.getSingleplayerServer().isPublished();
        boolean local=minecraft.getSingleplayerServer()!=null;
        g.centeredText(font,Component.translatable(!local?"easylan.ys.clientOnly":live?"easylan.ys.addressHint":"easylan.ys.beforeOpening"),width/2,35,0xFFDDDDDD);
        if(!live&&local) { g.text(font,Component.translatable("easylan.text.port"),left,53,0xFFFFFFFF);
            g.text(font,Component.translatable("easylan.text.maxplayer"),left+w/2+4,53,0xFFFFFFFF); }
        if(local)g.centeredText(font,Component.translatable(online?"easylan.ys.online":"easylan.ys.offline"),width/2,123,online?0xFF88DD88:0xFFFFDD88);
        g.centeredText(font,status,width/2,199,0xFFFFFFFF);
    }
    @Override public void onClose() { minecraft.gui.setScreen(parent); }
}
