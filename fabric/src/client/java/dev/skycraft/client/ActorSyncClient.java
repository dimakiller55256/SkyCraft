package dev.skycraft.client;

import dev.skycraft.link.SkyLink;
import dev.skycraft.link.Proto;
import dev.skycraft.net.ActorSync;
import java.util.ArrayList;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;

/** Publishes this machine's NPCs and receives hits only for this machine's Skyrim. */
public final class ActorSyncClient {
    private static ActorSync.WorldContext context;
    private static int ticks,lastSent,lastMessage;
    public static int receivedHits;
    private static final ArrayList<SkyLink.Actor> ACTORS=new ArrayList<>();
    private ActorSyncClient() {}
    public static int hostWorldId(){return context==null?0:context.worldId();}
    public static boolean compatible() {
        var mc=Minecraft.getInstance();
        if(mc.isLocalServer() || !SkyClient.tookOver() || mc.level==null) return true;
        return context!=null && context.permits(SkyClient.sky().worldId);
    }
    public static void init() {
        ClientPlayConnectionEvents.JOIN.register((h,s,mc)->context=null);
        ClientPlayConnectionEvents.DISCONNECT.register((h,mc)->context=null);
        ClientPlayNetworking.registerGlobalReceiver(ActorSync.WorldContext.TYPE,(p,c)->c.client().execute(()->context=p));
        ClientPlayNetworking.registerGlobalReceiver(ActorSync.Hit.TYPE,(p,c)->c.client().execute(()->{
            receivedHits++;
            if(!SkyLink.active() || !compatible() || !Float.isFinite(p.damage()) || !Float.isFinite(p.x()) || !Float.isFinite(p.z()) || !Float.isFinite(p.push())) return;
            if(SkyLink.readActors(ACTORS) && ACTORS.stream().anyMatch(a->a.formId()==p.formId() && !a.dead())) {
                SkyLink.pushEvent(Proto.EV_HIT_ACTOR,p.formId(),p.damage(),p.x(),p.z(),p.push(),p.flags(),p.weapon());
                dev.skycraft.SkyCraft.LOG.info("SkyCraft: guest hit delivered to local Skyrim actor {}, damage {}",Integer.toHexString(p.formId()),p.damage());
            }
        }));
        ClientTickEvents.END_CLIENT_TICK.register(mc->{
            ticks++;
            if(mc.player==null || mc.isLocalServer() || !SkyClient.tookOver()) return;
            if(!compatible() && ticks-lastMessage>=100) {
                lastMessage=ticks;
                String message="SkyCraft: разные локации Skyrim или хост загружает игру. Хост: "+Integer.toHexString(hostWorldId())+", вы: "+Integer.toHexString(SkyClient.sky().worldId)+". Перейдите в ту же локацию в Skyrim; управление Skyrim временно обычное (E — взаимодействие).";
                mc.gui.hud.getChat().addClientSystemMessage(net.minecraft.network.chat.Component.literal(message));
                dev.skycraft.SkyCraft.LOG.warn(message);
            }
            if(ticks-lastSent<2 || !ClientPlayNetworking.canSend(ActorSync.Actors.TYPE))return;
            lastSent=ticks;
            if(SkyLink.active() && SkyLink.inputFresh() && compatible() && SkyLink.readActors(ACTORS))
                ClientPlayNetworking.send(new ActorSync.Actors(java.util.List.copyOf(ACTORS)));
            else ClientPlayNetworking.send(new ActorSync.Actors(java.util.List.of()));
        });
    }
}
