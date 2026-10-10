package dev.skycraft.client;
import dev.skycraft.items.ItemPackets;
import dev.skycraft.link.SkyLink;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;

/** Relays the local Skyrim mailbox to the owning player's server, including LAN guests. */
public final class ItemConversionClient {
    private static boolean initialized;private static long sentAt;private static Object connection;private static String signature="";
    public static volatile ItemPackets.Reply lastReply;
    private ItemConversionClient() {}
    public static void frame(Minecraft mc){
        SkyLink.itemTestWorld(mc.getSingleplayerServer()!=null&&mc.getSingleplayerServer().getWorldData().getLevelName().equals("SkyCraft-Items-Test"));
        if(!initialized){initialized=true;ClientPlayNetworking.registerGlobalReceiver(ItemPackets.Reply.TYPE,(reply,c)->c.client().execute(()->{lastReply=reply;SkyLink.writeItemReply(reply.id(),reply.status(),reply.target(),reply.message());}));}
        var request=SkyLink.readItemRequest();if(request==null||request.action()==0||mc.player==null||mc.getConnection()==null)return;
        if(request.action()==3){mc.gui.hud.setOverlayMessage(net.minecraft.network.chat.Component.literal(request.count()==1?"SkyCraft: сохраните Skyrim (F5), чтобы включить обмен предметов":request.count()==3?"SkyCraft: предметы выданы. Сохраните Skyrim (F5).":request.count()==4?"SkyCraft: тестовый профиль. Загрузите обычное сохранение Skyrim.":request.count()==5?"SkyCraft: в помощнике нажмите «Тест предметов без друга».":"SkyCraft: обмен остановлен. Откройте инструкцию и соберите отчёт."),false);return;}
        if(!ClientPlayNetworking.canSend(ItemPackets.Request.TYPE)){SkyLink.writeItemReply(request.id(),3,"","Server has no item conversion support");return;}
        String key=request.id()+":"+request.action()+":"+request.target();long now=System.nanoTime();
        if(connection==mc.getConnection()&&signature.equals(key)&&now-sentAt<1_000_000_000L)return;
        connection=mc.getConnection();signature=key;sentAt=now;
        ClientPlayNetworking.send(new ItemPackets.Request(request.action(),request.id(),request.character(),request.target(),request.plugin(),request.localForm(),request.item(),request.count()));
    }
}
