package dev.skycraft.client;

import dev.skycraft.network.Endpoint;
import dev.skycraft.network.HostAuthentication;
import java.net.NetworkInterface;
import java.net.Inet4Address;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;

/** Accessible inside Skyrim's overlay: O -> network, without opening a hidden MC window. */
public final class NetworkMenu extends Screen {
    private final Screen parent;
    private EditBox endpoint, port;
    private boolean online = true;
    private Component status = Component.empty();
    private List<String> addresses = List.of("127.0.0.1 — только этот ПК");
    private int addressIndex;
    private Button addressButton;
    public NetworkMenu(Screen parent) { super(Component.literal("SkyCraft: сеть")); this.parent = parent; }
    public static void register() {
        ScreenEvents.AFTER_INIT.register((mc, screen, w, h) -> {
            if ((screen instanceof PauseScreen pause && pause.showsPauseMenu()) || screen instanceof TitleScreen) {
                Screens.getWidgets(screen).add(Button.builder(Component.literal("SkyCraft: сеть"),
                    b -> mc.gui.setScreen(new NetworkMenu(screen))).bounds(Math.max(4,w-154),4,150,20).build());
            }
        });
    }
    @Override protected void init() {
        int w=Math.min(340,width-20),x=(width-w)/2;
        endpoint=new EditBox(font,x,45,w,20,Component.literal("Адрес хоста IP:порт"));
        endpoint.setMaxLength(256); endpoint.setHint(Component.literal("Вставьте IP:порт хоста")); addRenderableWidget(endpoint);
        addRenderableWidget(Button.builder(Component.literal("Подключиться"),b -> {
            try { String target=Endpoint.parse(endpoint.getValue()).authority(); MirrorWorld.joinFriend(minecraft,target); }
            catch (IllegalArgumentException e) { status=Component.literal(e.getMessage()); }
        }).bounds(x,70,w/2-2,20).build());
        addRenderableWidget(Button.builder(Component.literal("Вернуться в свой мир"),b -> MirrorWorld.leaveFriend(minecraft))
            .bounds(x+w/2+2,70,w/2-2,20).build());
        port=new EditBox(font,x,100,72,20,Component.literal("Порт TCP")); port.setMaxLength(5);port.setValue("25565");addRenderableWidget(port);
        var server=minecraft.getSingleplayerServer();
        if (server!=null && server.isPublished()) { port.setValue(Integer.toString(server.getPort()));online=server.usesAuthentication(); }
        Button auth=Button.builder(authLabel(),b->{online=!online;b.setMessage(authLabel());}).bounds(x+78,100,w-78,20).build();
        auth.active=server!=null&&!server.isPublished();addRenderableWidget(auth);
        Button host=Button.builder(Component.literal("Открыть свой мир для друга"),b->{
            try {
                int p=Integer.parseInt(port.getValue()); if(p<1||p>65535) throw new IllegalArgumentException("Порт: 1–65535");
                NetworkClient.host(minecraft,p,online?HostAuthentication.ONLINE:HostAuthentication.OFFLINE);
                var s=minecraft.getSingleplayerServer();
                if(s!=null&&s.isPublished()) { status=Component.literal("LAN: "+s.getPort()+", "+(s.usesAuthentication()?"ONLINE":"OFFLINE"));minecraft.gui.setScreen(new NetworkMenu(parent)); }
                else status=Component.literal("Не удалось открыть LAN. Проверьте порт.");
            } catch (IllegalArgumentException e) { status=Component.literal("Порт: введите число 1–65535"); }
        }).bounds(x,126,w,20).build();host.active=server!=null&&!server.isPublished();addRenderableWidget(host);
        addresses=addresses();
        addressButton=Button.builder(addressLabel(),b->{addressIndex=(addressIndex+1)%addresses.size();b.setMessage(addressLabel());})
            .bounds(x,150,w,20).build();addRenderableWidget(addressButton);
        Button copy=Button.builder(Component.literal("Копировать IP:порт для друга"),b->{
            var s=minecraft.getSingleplayerServer();if(s!=null&&s.isPublished()) {
                minecraft.keyboardHandler.setClipboard(addresses.get(addressIndex).split(" ")[0]+":"+s.getPort());
                status=Component.literal("Скопировано. Передайте другу; он вставит в верхнее поле.");
            }
        }).bounds(x,174,w,20).build();copy.active=server!=null&&server.isPublished();addRenderableWidget(copy);
        if(server!=null&&server.isPublished()) status=Component.literal("LAN: "+server.getPort()+", "+(server.usesAuthentication()?"ONLINE":"OFFLINE")+", игроков: "+server.getPlayerCount());
        addRenderableWidget(Button.builder(Component.literal("Назад"),b->onClose()).bounds(x,height-25,w,20).build());
    }
    private Component authLabel() {return Component.literal(online?"Сессия: ONLINE":"Сессия: OFFLINE (частная LAN)");}
    private Component addressLabel() {return Component.literal(addresses.get(addressIndex));}
    private static List<String> addresses() {
        List<String> result=new ArrayList<>();
        try {var interfaces=NetworkInterface.getNetworkInterfaces();while(interfaces.hasMoreElements()) {var n=interfaces.nextElement();if(!n.isUp())continue;
            var a=n.getInetAddresses();while(a.hasMoreElements()) {var ip=a.nextElement();if(ip instanceof Inet4Address&&!ip.isLoopbackAddress()&&!ip.isLinkLocalAddress()) result.add(ip.getHostAddress()+" — "+n.getDisplayName());}
        }} catch(java.net.SocketException e) { dev.skycraft.SkyCraft.LOG.warn("SkyCraft: address discovery failed",e); }
        result.sort(Comparator.comparingInt(v->v.toLowerCase(java.util.Locale.ROOT).contains("radmin")?0:1));
        result.add("127.0.0.1 — только этот ПК");return List.copyOf(result);
    }
    @Override public void onClose() {minecraft.gui.setScreen(parent);}
    @Override public void extractRenderState(GuiGraphicsExtractor g,int mouseX,int mouseY,float delta) {
        super.extractRenderState(g,mouseX,mouseY,delta);
        g.centeredText(font,title,width/2,20,0xFFFFFFFF);
        g.centeredText(font,Component.literal("O — меню Minecraft, Esc — меню Skyrim"),width/2,34,0xFFAAAAAA);
        g.centeredText(font,font.plainSubstrByWidth(status.getString(),width-20),width/2,height-42,0xFFFFFFFF);
    }
}
