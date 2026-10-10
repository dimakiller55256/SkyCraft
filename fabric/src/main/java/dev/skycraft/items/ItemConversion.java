package dev.skycraft.items;

import com.mojang.serialization.Codec;
import dev.skycraft.SkyCraft;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.phys.Vec3;

/** The server owns inventory grants. Receipts, inventory and overflow share one atomic player file. */
public final class ItemConversion {
    private ItemConversion() {}
    public static final AttachmentType<String> LEDGER=AttachmentRegistry.<String>builder().persistent(
        Codec.either(Codec.STRING,Codec.STRING.listOf()).xmap(value->value.map(text->text,parts->String.join("",parts)),text->com.mojang.datafixers.util.Either.right(ConversionLedger.chunks(text))))
        .buildAndRegister(Identifier.fromNamespaceAndPath("skycraft","item_receipts_v1"));
    private static final Map<MinecraftServer,String> worlds=new WeakHashMap<>();
    private static final Set<UUID> blocked=new HashSet<>();
    public record Reply(String id,int status,String target,String message) {}
    public static void init() {
        ItemPackets.init();
        ServerPlayerEvents.COPY_FROM.register((oldPlayer,newPlayer,alive)->newPlayer.setAttached(LEDGER,oldPlayer.getAttachedOrElse(LEDGER,"")));
        ServerTickEvents.END_SERVER_TICK.register(server->{
            if(server.getTickCount()%20!=0)return;
            for(var player:server.getPlayerList().getPlayers())try { spawnOverflow(player); }
            catch(Exception e){SkyCraft.LOG.error("SkyCraft item overflow recovery failed for {}",player.getUUID(),e);}
        });
    }
    public static String target(ServerPlayer player) throws Exception {
        var server=player.level().getServer();String world=worlds.get(server);
        if(world==null){
            Path path=server.getWorldPath(LevelResource.ROOT).resolve("skycraft-item-world.txt");
            if(!Files.exists(path))Files.writeString(path,UUID.randomUUID().toString(),StandardOpenOption.CREATE_NEW);
            world=UUID.fromString(Files.readString(path).strip()).toString();worlds.put(server,world);
        }
        return UUID.nameUUIDFromBytes((world+":"+player.getUUID()).getBytes(StandardCharsets.UTF_8)).toString();
    }
    public static ItemStack stack(String key,int count) {
        String[] parts=key.split("#",-1);if(parts.length>2)throw new IllegalArgumentException("Invalid item key");
        Identifier id=Identifier.parse(parts[0]);
        var item=BuiltInRegistries.ITEM.getOptional(id).orElseThrow(()->new IllegalArgumentException("Unknown Minecraft item: "+id));
        var out=new ItemStack(item,count);if(out.isEmpty())throw new IllegalArgumentException("Empty Minecraft item");
        if(parts.length==2){
            if(!Set.of("minecraft:potion","minecraft:splash_potion","minecraft:lingering_potion").contains(parts[0]))throw new IllegalArgumentException("Potion applied to non-potion");
            var potion=BuiltInRegistries.POTION.get(Identifier.withDefaultNamespace(parts[1])).orElseThrow(()->new IllegalArgumentException("Unknown potion"));
            out.set(DataComponents.POTION_CONTENTS,new PotionContents(potion));
        }
        return out;
    }
    public static Reply handle(ServerPlayer player,int action,String id,String character,String requestedTarget,String plugin,int form,String key,int count) {
        String owner="";
        try {
            if(blocked.contains(player.getUUID()))throw new IllegalArgumentException("Player journal paused after storage error");
            owner=target(player);
            if(action!=1&&action!=2)throw new IllegalArgumentException("Invalid phase");
            if((action==2||!requestedTarget.isEmpty())&&!owner.equals(requestedTarget))throw new IllegalArgumentException("Different Minecraft world or owner");
            ItemStack sample=stack(key,1); // Reject unknown items BEFORE native removal.
            String before=player.getAttachedOrElse(LEDGER,"");var ledger=ConversionLedger.parse(before);
            var receipt=ledger.receipts.get(id);
            if(action==2&&receipt==null)throw new IllegalArgumentException("Commit has no durable preparation");
            receipt=ledger.prepare(id,character,plugin,form,key,count);
            if(receipt.delivered())return new Reply(id,2,owner,"Already delivered");
            if(action==1) {
                player.setAttached(LEDGER,ledger.json());
                try{saveAtomic(player);}catch(Exception e){player.setAttached(LEDGER,before);throw e;}
                return new Reply(id,1,owner,"Prepared");
            }
            List<ItemStack> inventory=copyInventory(player);int left=count;
            try {
                while(left>0){int n=Math.min(left,sample.getMaxStackSize());var chunk=sample.copyWithCount(n);addSurvival(player,chunk);left-=n-chunk.getCount();if(!chunk.isEmpty())break;}
                ledger.receipts.put(id,receipt.delivered(left));player.setAttached(LEDGER,ledger.json());
                saveAtomic(player);
            } catch(Exception e){restoreInventory(player,inventory);player.setAttached(LEDGER,before);throw e;}
            player.inventoryMenu.broadcastChanges();
            SkyCraft.LOG.info("SkyCraft item delivered: owner {} request {} {} x{} overflow {}",player.getUUID(),id,key,count,left);
            spawnOverflow(player);
            return new Reply(id,2,owner,"Delivered");
        } catch(IllegalArgumentException e){return new Reply(id,3,owner,e.getMessage());}
        catch(Exception e){SkyCraft.LOG.error("SkyCraft item storage failed: {} owner {}",id,player.getUUID(),e);return new Reply(id,4,owner,"Storage unavailable; conversion will retry");}
    }
    /** Avoid creative Inventory.add silently deleting overflow. Only normal 36 slots are eligible. */
    public static void addSurvival(ServerPlayer player,ItemStack incoming) {
        var inv=player.getInventory();
        for(int i=0;i<36&&!incoming.isEmpty();i++) {
            var current=inv.getItem(i);
            if(!current.isEmpty()&&ItemStack.isSameItemSameComponents(current,incoming)){
                int n=Math.min(incoming.getCount(),current.getMaxStackSize()-current.getCount());if(n>0){current.grow(n);incoming.shrink(n);}
            }
        }
        for(int i=0;i<36&&!incoming.isEmpty();i++)if(inv.getItem(i).isEmpty()) {
            int n=Math.min(incoming.getCount(),incoming.getMaxStackSize());inv.setItem(i,incoming.copyWithCount(n));incoming.shrink(n);
        }
        inv.setChanged();
    }
    private static List<ItemStack> copyInventory(ServerPlayer p){var out=new ArrayList<ItemStack>();for(int i=0;i<p.getInventory().getContainerSize();i++)out.add(p.getInventory().getItem(i).copy());return out;}
    private static void restoreInventory(ServerPlayer p,List<ItemStack> copy){for(int i=0;i<copy.size();i++)p.getInventory().setItem(i,copy.get(i));}
    /** Same format/path as 26.3 PlayerDataStorage.save, but errors propagate and rename is atomic. */
    public static void saveAtomic(ServerPlayer player) throws Exception {
        var output=TagValueOutput.createWithContext(ProblemReporter.DISCARDING,player.registryAccess());player.saveWithoutId(output);
        Path dir=player.level().getServer().getWorldPath(LevelResource.PLAYER_DATA_DIR);Files.createDirectories(dir);
        Path path=dir.resolve(player.getStringUUID()+".dat"),temp=Files.createTempFile(dir,"skycraft-item-",".tmp");
        try {
            NbtIo.writeCompressed(output.buildResult(),temp);
            try(var channel=FileChannel.open(temp,StandardOpenOption.WRITE)){channel.force(true);}
            Files.move(temp,path,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        } finally {try{Files.deleteIfExists(temp);}catch(Exception cleanup){SkyCraft.LOG.warn("SkyCraft item temp cleanup failed: {}",temp);}}
    }
    public static void spawnOverflow(ServerPlayer player) throws Exception {
        var ledger=ConversionLedger.parse(player.getAttachedOrElse(LEDGER,""));
        for(var entry:ledger.receipts.entrySet()) {
            var receipt=entry.getValue();if(receipt.overflow()==0)continue;
            // One entity per receipt, at most one stack at a time. Remainder stays in the outbox.
            UUID entityId=UUID.nameUUIDFromBytes(("skycraft-drop:"+player.getUUID()+":"+entry.getKey()).getBytes(StandardCharsets.UTF_8));
            if(player.level().getEntity(entityId)!=null)continue;
            // If the owner crossed a door/world, remove the old display and move its durable outbox.
            for(var level:player.level().getServer().getAllLevels()){var old=level.getEntity(entityId);if(old!=null)old.discard();}
            var item=stack(receipt.item(),1);item.setCount(Math.min(receipt.overflow(),item.getMaxStackSize()));
            Vec3 direction=Vec3.directionFromRotation(0,player.getYRot());Vec3 pos=player.position().add(direction.scale(1.1)).add(0,0.1,0);
            var entity=new OverflowItem(player,pos,item,entry.getKey());entity.setUUID(entityId);player.level().addFreshEntity(entity);
        }
    }
    /** Vanilla item on clients, ephemeral server display of the durable owner-only outbox. */
    public static final class OverflowItem extends ItemEntity {
        public final UUID owner;public final String transaction;
        OverflowItem(ServerPlayer player,Vec3 position,ItemStack item,String id){
            super(player.level(),position.x,position.y,position.z,item);
            owner=player.getUUID();transaction=id;
            var tag=new CompoundTag();tag.putString("skycraft_outbox",id);item.set(DataComponents.CUSTOM_DATA,CustomData.of(tag));
            setTarget(owner);setNeverPickUp();setUnlimitedLifetime();setPermanentlyInvulnerable(true);setNoGravity(true);setDeltaMovement(0,0,0);
        }
        @Override public boolean shouldBeSaved(){return false;}
        // Vanilla mobs must not consume this display without reducing the durable outbox.
        @Override public boolean hasPickUpDelay(){return true;}
        @Override public void playerTouch(Player other){
            if(!(other instanceof ServerPlayer player)||!owner.equals(player.getUUID())||blocked.contains(owner))return;
            String before=player.getAttachedOrElse(LEDGER,"");var ledger=ConversionLedger.parse(before);var receipt=ledger.receipts.get(transaction);
            if(receipt==null||receipt.overflow()==0){discard();return;}
            List<ItemStack> inventory=copyInventory(player);
            ItemStack item=stack(receipt.item(),Math.min(getItem().getCount(),receipt.overflow()));int n=item.getCount();addSurvival(player,item);int taken=n-item.getCount();if(taken==0)return;
            ledger.receipts.put(transaction,receipt.remainder(receipt.overflow()-taken));player.setAttached(LEDGER,ledger.json());
            try{saveAtomic(player);}catch(Exception e){restoreInventory(player,inventory);player.setAttached(LEDGER,before);SkyCraft.LOG.error("SkyCraft outbox pickup not saved; retry later",e);return;}
            player.take(this,taken);player.inventoryMenu.broadcastChanges();discard();
        }
    }
}
