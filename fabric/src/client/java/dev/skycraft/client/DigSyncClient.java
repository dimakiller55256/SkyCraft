package dev.skycraft.client;

import dev.skycraft.net.DigSync;
import dev.skycraft.world.SkyDig;
import java.util.LinkedHashMap;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.status.ChunkStatus;

public final class DigSyncClient {
    private record Pending(DigSync.Snapshot snapshot,int expires) {}
    private static final LinkedHashMap<String,Pending> PENDING=new LinkedHashMap<>();
    private record LoadRequest(String dimension,int x,int z) {}
    private static final LinkedHashMap<String,LoadRequest> LOADS=new LinkedHashMap<>();
    private static int tick;
    private DigSyncClient() {}
    public static void init() {
        ClientPlayConnectionEvents.JOIN.register((h,s,mc)->{PENDING.clear();LOADS.clear();});
        ClientPlayConnectionEvents.DISCONNECT.register((h,mc)->{PENDING.clear();LOADS.clear();});
        ClientPlayNetworking.registerGlobalReceiver(DigSync.Snapshot.TYPE,(p,c)->c.client().execute(()->{
            PENDING.put(p.dimension()+":"+ChunkPos.pack(p.x(),p.z()),new Pending(p,tick+300));
            while(PENDING.size()>512) PENDING.remove(PENDING.keySet().iterator().next());
        }));
        ClientChunkEvents.CHUNK_LOAD.register((level,chunk)->{
            var request=new LoadRequest(level.dimension().identifier().toString(),chunk.getPos().x(),chunk.getPos().z());
            LOADS.put(request.dimension()+":"+ChunkPos.pack(request.x(),request.z()),request);
            while(LOADS.size()>512) LOADS.remove(LOADS.keySet().iterator().next());
        });
        ClientTickEvents.END_CLIENT_TICK.register(mc->{
            tick++;
            if(mc.level==null){PENDING.clear();LOADS.clear();return;}
            if(ClientPlayNetworking.canSend(DigSync.Request.TYPE)) {
                int sent=0;
                for(var it=LOADS.values().iterator();it.hasNext()&&sent<12;) {
                    var request=it.next();it.remove();
                    if(!mc.level.dimension().identifier().toString().equals(request.dimension()))continue;
                    if(mc.level.getChunkSource().getChunk(request.x(),request.z(),ChunkStatus.FULL,false)==null)continue;
                    ClientPlayNetworking.send(new DigSync.Request(request.x(),request.z()));sent++;
                }
            }
            for(var it=PENDING.entrySet().iterator();it.hasNext();) {
                var pending=it.next().getValue();var p=pending.snapshot();
                if(tick>pending.expires()){it.remove();continue;}
                if(!mc.level.dimension().identifier().toString().equals(p.dimension()))continue;
                var chunk=mc.level.getChunkSource().getChunk(p.x(),p.z(),ChunkStatus.FULL,false);
                if(chunk==null)continue;
                for(var s:SkyDig.column(chunk).sections()) dev.skycraft.client.render.WorldExporter.markDirtyNow(p.x(),s.sectionY(),p.z());
                chunk.setAttached(SkyDig.DUG,p.column());
                for(var s:p.column().sections()) {
                    dev.skycraft.client.render.WorldExporter.markDirtyNow(p.x(),s.sectionY(),p.z());
                }
                dev.skycraft.SkyCraft.LOG.info("SkyCraft: dig snapshot received: chunk {} {}, {} world sections",p.x(),p.z(),p.column().sections().size());
                it.remove();
            }
        });
    }
}
