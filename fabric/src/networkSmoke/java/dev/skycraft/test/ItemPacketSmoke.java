package dev.skycraft.test;
import java.util.UUID;
import java.nio.file.*;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import dev.skycraft.client.ItemConversionClient;
import dev.skycraft.items.ItemPackets;

final class ItemPacketSmoke {
    private final String id=UUID.randomUUID().toString(),character=UUID.randomUUID().toString();private int stage;private String target="";
    boolean tick(Minecraft mc)throws Exception {
        var reply=ItemConversionClient.lastReply;
        if(stage==0){send(1);stage=1;return false;}
        if(reply==null||!reply.id().equals(id))return false;
        if(reply.status()==3||reply.status()==4)throw new IllegalStateException("Item packet failed: "+reply);
        if(stage==1&&reply.status()==1){target=reply.target();ItemConversionClient.lastReply=null;send(2);stage=2;return false;}
        if(stage==2&&reply.status()==2){ItemConversionClient.lastReply=null;send(2);stage=3;return false;}
        if(stage==3&&reply.status()==2){Files.writeString(mc.gameDirectory.toPath().resolve("item-packet.txt"),"PASS: real guest C2S prepare/commit and S2C acknowledgements; duplicate commit acknowledged");stage=4;}
        return stage==4;
    }
    void send(int action){ClientPlayNetworking.send(new ItemPackets.Request(action,id,character,target,"Skyrim.esm",0x63b45,"minecraft:emerald",1));}
}
