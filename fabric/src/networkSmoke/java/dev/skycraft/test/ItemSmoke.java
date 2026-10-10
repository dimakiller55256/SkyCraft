package dev.skycraft.test;
import dev.skycraft.items.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import net.minecraft.nbt.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.*;
import net.minecraft.world.level.storage.LevelResource;

/** Runs on the real server thread, exclusively in the disposable gameplay world. */
final class ItemSmoke {
    static void check(ServerPlayer owner,ServerPlayer host,Path result) throws Exception {
        List<ItemStack> backup=new ArrayList<>();for(int i=0;i<owner.getInventory().getContainerSize();i++)backup.add(owner.getInventory().getItem(i).copy());
        String id=UUID.randomUUID().toString(),character=UUID.randomUUID().toString();
        try {
            // Validate the complete reviewed table against the actual 26.3 registry.
            var table=com.google.gson.JsonParser.parseString(Files.readString(Path.of("../../config/item-conversion.json"))).getAsJsonObject();
            int mappings=0;for(var row:table.getAsJsonArray("items")){ItemConversion.stack(row.getAsJsonObject().get("minecraft").getAsString(),1);mappings++;}
            int hostIron=count(host,Items.IRON_INGOT);
            for(int i=0;i<36;i++)owner.getInventory().setItem(i,new ItemStack(Items.COBBLESTONE,64));
            var ready=ItemConversion.handle(owner,1,id,character,"","Skyrim.esm",0x5ace4,"minecraft:iron_ingot",65);
            require(ready.status()==1,"preparation rejected: "+ready);
            require(count(owner,Items.IRON_INGOT)==0,"prepare granted items");
            require(ItemConversion.handle(host,2,id,character,ready.target(),"Skyrim.esm",0x5ace4,"minecraft:iron_ingot",65).status()==3,"foreign owner commit accepted");
            var done=ItemConversion.handle(owner,2,id,character,ready.target(),"Skyrim.esm",0x5ace4,"minecraft:iron_ingot",65);
            require(done.status()==2,"delivery rejected: "+done);
            var saved=readLedger(owner);require(saved.receipts.get(id).overflow()==65,"outbox not saved with receipt");
            require(ItemConversion.handle(owner,2,id,character,ready.target(),"Skyrim.esm",0x5ace4,"minecraft:iron_ingot",65).status()==2,"replay failed");
            require(ItemConversion.handle(owner,2,id,character,ready.target(),"Skyrim.esm",0x5ace4,"minecraft:diamond",65).status()==3,"changed payload accepted");
            String bad=UUID.randomUUID().toString();require(ItemConversion.handle(owner,1,bad,character,"","Skyrim.esm",1,"minecraft:no_such_item",1).status()==3,"unknown item accepted");
            // Emulate restart: reload the attachment from the actual saved player NBT and delete
            // the ephemeral display. The authoritative outbox must respawn exactly once.
            owner.setAttached(ItemConversion.LEDGER,saved.json());var drop=drop(owner,id);drop.discard();ItemConversion.spawnOverflow(owner);
            drop=drop(owner,id);require(!drop.shouldBeSaved(),"outbox display would duplicate in chunk saves");
            require(drop.position().distanceTo(owner.position())<2,"drop is not in front of owner");
            var hopper=new net.minecraft.world.SimpleContainer(5);
            require(!net.minecraft.world.level.block.entity.HopperBlockEntity.addItem(hopper,drop),"hopper consumed durable outbox");
            require(hopper.isEmpty()&&!drop.isRemoved()&&drop.getItem().getCount()==64,"hopper duplicated or consumed outbox");
            require(drop.hasPickUpDelay(),"mob pickup is not blocked");
            int remainder=ConversionLedger.parse(owner.getAttachedOrElse(ItemConversion.LEDGER,"")).receipts.get(id).overflow();
            drop.setNoPickUpDelay();drop.playerTouch(host);require(!drop.isRemoved()&&count(host,Items.IRON_INGOT)==hostIron,"foreign player took outbox");
            drop.playerTouch(owner);require(count(owner,Items.IRON_INGOT)==0,"full inventory consumed outbox");
            owner.getInventory().setItem(35,ItemStack.EMPTY);drop.playerTouch(owner);require(count(owner,Items.IRON_INGOT)==64,"first stack pickup wrong");
            require(readLedger(owner).receipts.get(id).overflow()==1,"pickup receipt not durably reduced");
            ItemConversion.spawnOverflow(owner);drop=drop(owner,id);drop.setNoPickUpDelay();owner.getInventory().setItem(34,ItemStack.EMPTY);drop.playerTouch(owner);
            require(count(owner,Items.IRON_INGOT)==65&&readLedger(owner).receipts.get(id).overflow()==0,"remainder lost or duplicated");
            require(count(host,Items.IRON_INGOT)==hostIron,"host received guest's items");
            require(ItemConversion.handle(owner,1,id,character,"","Skyrim.esm",0x5ace4,"minecraft:iron_ingot",65).status()==2,"saved replay reopened transaction");
            var large=ConversionLedger.parse(owner.getAttachedOrElse(ItemConversion.LEDGER,""));
            for(int i=0;i<1000;i++)large.prepare(UUID.randomUUID().toString(),character,"Skyrim.esm",1,"minecraft:iron_ingot",1);
            require(large.json().length()>65535,"large receipt fixture too small");owner.setAttached(ItemConversion.LEDGER,large.json());ItemConversion.saveAtomic(owner);
            require(readLedger(owner).receipts.size()==large.receipts.size(),"large receipt history was truncated or not saved");
            Files.writeString(result,"PASS: "+mappings+" mappings valid; prepare/commit; guest ownership; 65-item full inventory outbox; hopper/mob pickup blocked; duplicate/restart replay; owner pickup 64+1 persisted; unknown/changed/foreign requests rejected; >64KiB receipt history saved/read");
        } finally {for(int i=0;i<backup.size();i++)owner.getInventory().setItem(i,backup.get(i));ItemConversion.saveAtomic(owner);}
    }
    static int count(ServerPlayer p,Item item){int total=0;for(int i=0;i<36;i++)if(p.getInventory().getItem(i).is(item))total+=p.getInventory().getItem(i).getCount();return total;}
    static ItemConversion.OverflowItem drop(ServerPlayer p,String id){UUID uuid=UUID.nameUUIDFromBytes(("skycraft-drop:"+p.getUUID()+":"+id).getBytes(StandardCharsets.UTF_8));return (ItemConversion.OverflowItem)Objects.requireNonNull(p.level().getEntity(uuid),"outbox entity missing");}
    static ConversionLedger readLedger(ServerPlayer p)throws Exception {
        Path path=p.level().getServer().getWorldPath(LevelResource.PLAYER_DATA_DIR).resolve(p.getStringUUID()+".dat");
        var tag=NbtIo.readCompressed(path,NbtAccounter.unlimitedHeap());
        var attached=tag.getCompoundOrEmpty("fabric:attachments");
        var value=attached.get("skycraft:item_receipts_v1");
        String text=value instanceof ListTag list?list.stream().map(chunk->chunk.asString().orElseThrow()).collect(java.util.stream.Collectors.joining()):value==null?"":value.asString().orElse("");
        require(!text.isEmpty(),"receipt attachment absent from saved NBT: "+tag.keySet());return ConversionLedger.parse(text);
    }
    static void require(boolean ok,String message){if(!ok)throw new IllegalStateException(message);}
}
