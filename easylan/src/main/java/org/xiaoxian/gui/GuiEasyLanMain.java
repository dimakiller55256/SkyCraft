// EasyLAN by XiaoXianHW / Darf; YS adaptation for Minecraft 26.3 (2026-10-05).
// SPDX-License-Identifier: GPL-3.0-only
package org.xiaoxian.gui;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.xiaoxian.EasyLAN;
import org.xiaoxian.util.ConfigUtil;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
/** Settings apply at the next LAN opening. */
public class GuiEasyLanMain extends Screen {
    private final Screen parent;
    private EditBox motd;
    private final java.util.Map<String,Boolean> draft=new java.util.HashMap<>();
    private final java.util.Map<String,Consumer<Boolean>> setters=new java.util.HashMap<>();
    public GuiEasyLanMain(Screen parent) { super(Component.translatable("easylan.setting")); this.parent = parent; }
    @Override protected void init() {
        int w = Math.min(420, width - 20), left = (width - w) / 2, col = (w - 6) / 2;
        toggle(left,35,col,"easylan.text.onlineMode",()->EasyLAN.onlineMode,v->EasyLAN.onlineMode=v,true);
        toggle(left+col+6,35,col,"easylan.text.pvp",()->EasyLAN.allowPVP,v->EasyLAN.allowPVP=v,false);
        toggle(left,59,col,"easylan.ys.mobs",()->EasyLAN.spawnAnimals||EasyLAN.spawnNPCs,
            v->{EasyLAN.spawnAnimals=v; EasyLAN.spawnNPCs=v;},false);
        toggle(left+col+6,59,col,"easylan.text.allowFlight",()->EasyLAN.allowFlight,v->EasyLAN.allowFlight=v,false);
        toggle(left,83,col,"easylan.text.whitelist",()->EasyLAN.whiteList,v->EasyLAN.whiteList=v,false);
        toggle(left+col+6,83,col,"easylan.text.ban",()->EasyLAN.BanCommands,v->EasyLAN.BanCommands=v,false);
        toggle(left,107,col,"easylan.text.op",()->EasyLAN.OpCommands,v->EasyLAN.OpCommands=v,false);
        toggle(left+col+6,107,col,"easylan.text.save",()->EasyLAN.SaveCommands,v->EasyLAN.SaveCommands=v,false);
        toggle(left,131,col,"easylan.text.httpApi",()->EasyLAN.HttpAPI,v->EasyLAN.HttpAPI=v,false);
        toggle(left+col+6,131,col,"easylan.text.lanInfo",()->EasyLAN.LanOutput,v->EasyLAN.LanOutput=v,false);
        motd = new EditBox(font,left,170,w,20,Component.translatable("easylan.text.motd"));
        motd.setMaxLength(100); motd.setValue(EasyLAN.motd); addRenderableWidget(motd);
        addRenderableWidget(Button.builder(Component.translatable("easylan.save"),b->{
            draft.forEach((key,value)->setters.get(key).accept(value)); EasyLAN.motd=motd.getValue();
            if(ConfigUtil.save()) {
                if(parent instanceof EasyLanPublishScreen lan) lan.refreshFromSettings();
                minecraft.gui.setScreen(parent);
            } else b.setMessage(Component.translatable("easylan.ys.configError"));
        }).bounds(left,height-25,col,20).build());
        addRenderableWidget(Button.builder(Component.translatable("easylan.load"),b->{
            ConfigUtil.load(); minecraft.gui.setScreen(new GuiEasyLanMain(parent));
        }).bounds(left+col+6,height-25,col,20).build());
    }
    private void toggle(int x,int y,int w,String key,BooleanSupplier get,Consumer<Boolean> set,boolean auth) {
        draft.putIfAbsent(key,get.getAsBoolean());setters.put(key,set);
        Button b=Button.builder(label(key,draft.get(key)),button->{
            draft.put(key,!draft.get(key)); button.setMessage(label(key,draft.get(key)));
        }).bounds(x,y,w,20).build();
        b.setTooltip(Tooltip.create(Component.translatable(auth?"easylan.ys.authHint":key))); addRenderableWidget(b);
    }
    private Component label(String key,boolean value) {
        return Component.translatable(key).append(": ").append(Component.translatable(value?"options.on":"options.off"));
    }
    @Override public void extractRenderState(GuiGraphicsExtractor g,int x,int y,float t) {
        super.extractRenderState(g,x,y,t); g.centeredText(font,title,width/2,12,0xFFFFFFFF);
        g.text(font,Component.translatable("easylan.text.motd"),(width-Math.min(420,width-20))/2,158,0xFFDDDDDD);
        g.centeredText(font,Component.translatable("easylan.ys.nextOpening"),width/2,198,0xFFFFDD88);
    }
    @Override public void onClose() { minecraft.gui.setScreen(parent); }
}
