package dev.skycraft.net;

import dev.skycraft.world.SkyDig;
import net.fabricmc.fabric.api.networking.v1.*;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import java.util.HashMap;
import java.util.HashSet;
import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.chunk.LevelChunk;

/** Explicit repair path in addition to Fabric chunk attachments: change push and load request. */
public final class DigSync {
    private static final HashSet<LevelChunk> DIRTY = new HashSet<>();
    private static final HashMap<UUID,Integer> REQUESTS = new HashMap<>();
    private DigSync() {}
    public record Request(int x,int z) implements CustomPacketPayload {
        public static final Type<Request> TYPE=new Type<>(Identifier.fromNamespaceAndPath("skycraft","dig_request"));
        public static final StreamCodec<RegistryFriendlyByteBuf,Request> CODEC=new StreamCodec<>() {
            public Request decode(RegistryFriendlyByteBuf b){return new Request(b.readInt(),b.readInt());}
            public void encode(RegistryFriendlyByteBuf b,Request p){b.writeInt(p.x);b.writeInt(p.z);}
        };
        public Type<? extends CustomPacketPayload> type(){return TYPE;}
    }
    public record Snapshot(String dimension,int x,int z,SkyDig.DugColumn column) implements CustomPacketPayload {
        public static final Type<Snapshot> TYPE=new Type<>(Identifier.fromNamespaceAndPath("skycraft","dig_snapshot"));
        public static final StreamCodec<RegistryFriendlyByteBuf,Snapshot> CODEC=new StreamCodec<>() {
            public Snapshot decode(RegistryFriendlyByteBuf b){return new Snapshot(b.readUtf(256),b.readInt(),b.readInt(),SkyDig.DugColumn.STREAM_CODEC.decode(b));}
            public void encode(RegistryFriendlyByteBuf b,Snapshot p){b.writeUtf(p.dimension,256);b.writeInt(p.x);b.writeInt(p.z);SkyDig.DugColumn.STREAM_CODEC.encode(b,p.column);}
        };
        public Type<? extends CustomPacketPayload> type(){return TYPE;}
    }
    public static void init() {
        PayloadTypeRegistry.serverboundPlay().register(Request.TYPE,Request.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(Snapshot.TYPE,Snapshot.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(Request.TYPE,(p,c)->c.server().execute(()->{
            var player=c.player();
            if (REQUESTS.merge(player.getUUID(),1,Integer::sum)<=32 && near(player,p.x,p.z)) {
                var chunk=player.level().getChunkSource().getChunkNow(p.x,p.z);
                if(chunk!=null) send(player,chunk,SkyDig.column(chunk));
            }
        }));
        ServerTickEvents.END_SERVER_TICK.register(server->{
            for(var chunk:DIRTY) {
                for(var player:server.getPlayerList().getPlayers()) {
                    if(player.level()==chunk.getLevel() && near(player,chunk.getPos().x(),chunk.getPos().z())) send(player,chunk,SkyDig.column(chunk));
                }
            }
            DIRTY.clear(); REQUESTS.clear();
        });
    }
    private static boolean near(ServerPlayer p,int x,int z) {
        return Math.abs((long)p.chunkPosition().x()-x)<=12 && Math.abs((long)p.chunkPosition().z()-z)<=12;
    }
    private static void send(ServerPlayer player,LevelChunk chunk,SkyDig.DugColumn column) {
        if(ServerPlayNetworking.canSend(player,Snapshot.TYPE)) ServerPlayNetworking.send(player,new Snapshot(chunk.getLevel().dimension().identifier().toString(),chunk.getPos().x(),chunk.getPos().z(),column));
    }
    public static void changed(LevelChunk chunk) {
        if(chunk.getLevel().getServer()!=null) DIRTY.add(chunk);
    }
}
