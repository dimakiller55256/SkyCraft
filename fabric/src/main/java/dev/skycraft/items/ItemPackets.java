package dev.skycraft.items;
import net.fabricmc.fabric.api.networking.v1.*;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public final class ItemPackets {
    private ItemPackets() {}
    public record Request(int action,String id,String character,String target,String plugin,int form,String item,int count) implements CustomPacketPayload {
        public static final Type<Request> TYPE=new Type<>(Identifier.fromNamespaceAndPath("skycraft","item_exchange_v1"));
        public static final StreamCodec<RegistryFriendlyByteBuf,Request> CODEC=new StreamCodec<>() {
            public Request decode(RegistryFriendlyByteBuf b){return new Request(b.readVarInt(),b.readUtf(40),b.readUtf(40),b.readUtf(40),b.readUtf(128),b.readInt(),b.readUtf(96),b.readVarInt());}
            public void encode(RegistryFriendlyByteBuf b,Request p){b.writeVarInt(p.action);b.writeUtf(p.id,40);b.writeUtf(p.character,40);b.writeUtf(p.target,40);b.writeUtf(p.plugin,128);b.writeInt(p.form);b.writeUtf(p.item,96);b.writeVarInt(p.count);}
        };
        public Type<? extends CustomPacketPayload> type(){return TYPE;}
    }
    public record Reply(String id,int status,String target,String message) implements CustomPacketPayload {
        public static final Type<Reply> TYPE=new Type<>(Identifier.fromNamespaceAndPath("skycraft","item_receipt_v1"));
        public static final StreamCodec<RegistryFriendlyByteBuf,Reply> CODEC=new StreamCodec<>() {
            public Reply decode(RegistryFriendlyByteBuf b){return new Reply(b.readUtf(40),b.readVarInt(),b.readUtf(40),b.readUtf(160));}
            public void encode(RegistryFriendlyByteBuf b,Reply p){b.writeUtf(p.id,40);b.writeVarInt(p.status);b.writeUtf(p.target,40);b.writeUtf(p.message,160);}
        };
        public Type<? extends CustomPacketPayload> type(){return TYPE;}
    }
    public static void init(){
        PayloadTypeRegistry.serverboundPlay().register(Request.TYPE,Request.CODEC);PayloadTypeRegistry.clientboundPlay().register(Reply.TYPE,Reply.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(Request.TYPE,(p,c)->c.server().execute(()->{
            var r=ItemConversion.handle(c.player(),p.action,p.id,p.character,p.target,p.plugin,p.form,p.item,p.count);
            ServerPlayNetworking.send(c.player(),new Reply(r.id(),r.status(),r.target(),r.message()));
        }));
    }
}
