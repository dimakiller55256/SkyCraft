package dev.skycraft.client;

import dev.skycraft.link.SkyLink;
import dev.skycraft.link.Proto;
import dev.skycraft.net.ActorSync;
import dev.skycraft.world.SkyOrigin;
import java.util.ArrayList;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;

/** Publishes this machine's NPCs and receives hits only for this machine's Skyrim. */
public final class ActorSyncClient {
    private static int ticks,lastSent;
    public static int receivedHits;
    private static final ArrayList<SkyLink.Actor> ACTORS=new ArrayList<>();
    private ActorSyncClient() {}
    /**
     * Skyrim's current world has its place in the Minecraft map (SkyWorlds), so positions can cross
     * between the two. Players in different Skyrim worlds each have their own square of the map.
     */
    public static boolean compatible() {
        var mc=Minecraft.getInstance();
        if(!SkyClient.tookOver() || mc.level==null) return true;
        return SkyOrigin.ready(SkyClient.sky().worldId);
    }
    public static void init() {
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
            if(ticks-lastSent<2 || !ClientPlayNetworking.canSend(ActorSync.Actors.TYPE))return;
            lastSent=ticks;
            // Map positions: the server places each player's NPCs in that player's world square.
            if(SkyLink.active() && SkyLink.inputFresh() && compatible() && SkyLink.readActors(ACTORS))
                ClientPlayNetworking.send(new ActorSync.Actors(java.util.List.copyOf(ACTORS)));
            else ClientPlayNetworking.send(new ActorSync.Actors(java.util.List.of()));
        });
    }
}
