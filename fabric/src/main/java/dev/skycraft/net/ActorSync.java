package dev.skycraft.net;

import dev.skycraft.combat.SkyCombat;
import dev.skycraft.link.Proto;
import dev.skycraft.link.SkyLink;
import java.util.List;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Actor IDs and damage always belong to the sender's Skyrim, never to another player's. */
public final class ActorSync {
    private ActorSync() {}
    public record Actors(List<SkyLink.Actor> actors) implements CustomPacketPayload {
        public static final Type<Actors> TYPE = new Type<>(Identifier.fromNamespaceAndPath("skycraft", "actors_v1"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Actors> CODEC = new StreamCodec<>() {
            public Actors decode(RegistryFriendlyByteBuf b) {
                int count=b.readVarInt();
                if(count<0 || count>Proto.MAX_ACTORS) throw new IllegalArgumentException("Actor count");
                var out=new java.util.ArrayList<SkyLink.Actor>(count);
                for(int i=0;i<count;i++) out.add(new SkyLink.Actor(b.readInt(),b.readInt(),b.readFloat(),b.readFloat(),b.readFloat(),b.readFloat(),b.readFloat(),b.readFloat(),b.readFloat(),b.readVarInt(),b.readUtf(64)));
                return new Actors(List.copyOf(out));
            }
            public void encode(RegistryFriendlyByteBuf b,Actors p) {
                if(p.actors.size()>Proto.MAX_ACTORS) throw new IllegalArgumentException("Actor count");
                b.writeVarInt(p.actors.size());
                for(var a:p.actors) {b.writeInt(a.formId());b.writeInt(a.flags());b.writeFloat(a.x());b.writeFloat(a.y());b.writeFloat(a.z());b.writeFloat(a.yaw());b.writeFloat(a.width());b.writeFloat(a.height());b.writeFloat(a.healthFrac());b.writeVarInt(a.level());b.writeUtf(a.name(),64);}
            }
        };
        public Type<? extends CustomPacketPayload> type(){return TYPE;}
    }
    public record Hit(int formId,float damage,float x,float z,float push,int flags,int weapon) implements CustomPacketPayload {
        public static final Type<Hit> TYPE = new Type<>(Identifier.fromNamespaceAndPath("skycraft", "actor_hit_v1"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Hit> CODEC = new StreamCodec<>() {
            public Hit decode(RegistryFriendlyByteBuf b){return new Hit(b.readInt(),b.readFloat(),b.readFloat(),b.readFloat(),b.readFloat(),b.readInt(),b.readInt());}
            public void encode(RegistryFriendlyByteBuf b,Hit p){b.writeInt(p.formId);b.writeFloat(p.damage);b.writeFloat(p.x);b.writeFloat(p.z);b.writeFloat(p.push);b.writeInt(p.flags);b.writeInt(p.weapon);}
        };
        public Type<? extends CustomPacketPayload> type(){return TYPE;}
    }
    public record WorldContext(int worldId,boolean ready) implements CustomPacketPayload {
        public boolean permits(int localWorldId) { return ready && worldId!=0 && worldId==localWorldId; }
        public static final Type<WorldContext> TYPE = new Type<>(Identifier.fromNamespaceAndPath("skycraft", "world_context_v1"));
        public static final StreamCodec<RegistryFriendlyByteBuf, WorldContext> CODEC = new StreamCodec<>() {
            public WorldContext decode(RegistryFriendlyByteBuf b){return new WorldContext(b.readInt(),b.readBoolean());}
            public void encode(RegistryFriendlyByteBuf b,WorldContext p){b.writeInt(p.worldId);b.writeBoolean(p.ready);}
        };
        public Type<? extends CustomPacketPayload> type(){return TYPE;}
    }
    public static void init() {
        PayloadTypeRegistry.serverboundPlay().register(Actors.TYPE,Actors.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(Hit.TYPE,Hit.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(WorldContext.TYPE,WorldContext.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(Actors.TYPE,(p,c)->c.server().execute(()->SkyCombat.guestActors(c.player(),p.actors)));
    }
}
