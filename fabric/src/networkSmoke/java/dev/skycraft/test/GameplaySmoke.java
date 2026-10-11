package dev.skycraft.test;

import dev.skycraft.client.NetworkClient;
import dev.skycraft.client.NetworkMenu;
import dev.skycraft.net.SkyNet;
import dev.skycraft.world.SkyDig;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.*;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import java.nio.file.*;
import java.net.ServerSocket;
import java.util.UUID;

/** Exercises the release networking/menu and dig handlers with two independent actual clients. */
final class GameplaySmoke {
    private final boolean host="host".equals(System.getProperty("skycraft.gameplaySmokeRole"));
    private final long deadline=System.nanoTime()+240_000_000_000L;
    private int step,ticks,port; private boolean done; private BlockPos dug;
    private Path peer;
    private volatile int combatStep,combatAt;
    private volatile String combatResult;
    private volatile boolean ownershipChecked;
    private final ItemPacketSmoke itemPackets=new ItemPacketSmoke();
    void initialize(){ClientTickEvents.END_CLIENT_TICK.register(this::tick);}
    private void tick(Minecraft mc) {
        if(done)return;
        try {
            peer=mc.gameDirectory.toPath().resolveSibling(host?"gameplay-client":"gameplay-host");
            if(System.nanoTime()>deadline)throw new IllegalStateException("Timeout at step "+step);
            if(Files.exists(peer.resolve("smoke-result.txt"))&&Files.readString(peer.resolve("smoke-result.txt")).startsWith("FAIL"))throw new IllegalStateException("Peer failed");
            if(mc.gui.overlay()!=null)return;
            if(mc.gui.screen()!=null&&mc.gui.screen().getClass().getSimpleName().contains("Onboarding"))mc.gui.setScreen(new TitleScreen());
            if(!host){guest(mc);return;}
            var server=mc.getSingleplayerServer();
            switch(step) {
                case 0 -> {
                    if(!(mc.gui.screen() instanceof TitleScreen title))return;
                    if(Screens.getWidgets(title).stream().noneMatch(w->w.getMessage().getString().equals("SkyCraft: сеть")))throw new IllegalStateException("Title menu absent");
                    var settings=new LevelSettings("SkyCraft isolated gameplay",GameType.CREATIVE,new LevelSettings.DifficultySettings(Difficulty.PEACEFUL,false,false),true,WorldDataConfiguration.DEFAULT);
                    mc.createWorldOpenFlows().createFreshLevel("gameplay-"+UUID.randomUUID(),settings,new WorldOptions(0,false,false),r->r.lookupOrThrow(Registries.WORLD_PRESET).getOrThrow(WorldPresets.FLAT).value().createWorldDimensions(),title);step=1;
                }
                case 1 -> {
                    if(server==null||mc.player==null)return;
                    mc.gui.setScreen(new PauseScreen(true));
                    if(Screens.getWidgets(mc.gui.screen()).stream().noneMatch(w->w.getMessage().getString().equals("SkyCraft: сеть")))throw new IllegalStateException("Pause menu absent");
                    mc.gui.setScreen(new NetworkMenu(mc.gui.screen()));ticks=0;step=2;
                }
                case 2 -> {
                    if(++ticks<3)return;
                    try(var free=new ServerSocket(0)){port=free.getLocalPort();}
                    var widgets=Screens.getWidgets(mc.gui.screen());
                    widgets.stream().filter(w->w instanceof EditBox&&w.getY()==100).map(w->(EditBox)w).findFirst().orElseThrow().setValue(Integer.toString(port));
                    press(mc,"Сессия: ONLINE");press(mc,"Открыть свой мир для друга");
                    if(!server.isPublished()||server.usesAuthentication()||server.getPort()!=port)throw new IllegalStateException("LAN GUI failed");
                    ticks=0;step=3;
                }
                case 3 -> {
                    if(++ticks<15)return;
                    net.minecraft.client.Screenshot.grab(mc.gameDirectory,"gameplay-network-menu.png",mc.gameRenderer.mainRenderTarget(),1,c->{});
                    mc.gui.setScreen(null);
                    inputCheck(mc);
                    Files.writeString(mc.gameDirectory.toPath().resolve("port.txt"),Integer.toString(port));step=4;
                }
                case 4 -> {
                    if(!Files.exists(peer.resolve("dug.txt")))return;
                    String[] xyz=Files.readString(peer.resolve("dug.txt")).split(",");dug=new BlockPos(Integer.parseInt(xyz[0]),Integer.parseInt(xyz[1]),Integer.parseInt(xyz[2]));
                    // Evaluate on the owning server thread; mark it for the guest/host tick.
                    server.execute(()->{
                        if(SkyDig.isDug(server.overworld(),0x3c,dug))try{Files.writeString(mc.gameDirectory.toPath().resolve("server-dug.txt"),"PASS");}catch(Exception e){throw new RuntimeException(e);}
                    });
                    if(!Files.exists(mc.gameDirectory.toPath().resolve("server-dug.txt")))return;
                    step=6;
                }
                case 6 -> {
                    if(!Files.exists(peer.resolve("rejoined.txt")))return;
                    if(!Files.exists(peer.resolve("actor-hit.txt"))) {
                        server.execute(()->{
                            if(ownershipChecked)return;
                            var owner=server.getPlayerList().getPlayers().stream().filter(p->p.getPlainTextName().equals("GameplayGuest")).findFirst().orElse(null);
                            if(owner==null)return;
                            var own=dev.skycraft.combat.SkyCombat.proxy(owner,0x1234);
                            if(own==null)return;
                            try {
                                var foreign=new dev.skycraft.combat.SkyrimActorEntity(dev.skycraft.combat.SkyCombat.SKYRIM_ACTOR,owner.level());
                                foreign.setOwner(server.getPlayerList().getPlayers().stream().filter(p->!p.getUUID().equals(owner.getUUID())).findFirst().orElseThrow().getUUID());
                                foreign.setFormId(0x1234);
                                if(foreign.hurtServer(owner.level(),owner.level().damageSources().playerAttack(owner),4))throw new IllegalStateException("Foreign actor accepted guest attack");
                                if(!own.hurtServer(owner.level(),owner.level().damageSources().playerAttack(owner),4))throw new IllegalStateException("Owned actor rejected attack");
                                ownershipChecked=true;
                                Files.writeString(mc.gameDirectory.toPath().resolve("ownership.txt"),"PASS: identical FormID scoped to owner; foreign attack rejected; own attack accepted");
                            }catch(Exception e){combatResult="FAIL: "+e;}
                        });
                        if(combatResult!=null)throw new IllegalStateException(combatResult);
                        return;
                    }
                    server.execute(()->{
                        if(combatResult!=null)return;
                        try {
                            var player=server.getPlayerList().getPlayers().stream().filter(p->p.getPlainTextName().equals("GameplayGuest")).findFirst().orElse(null);
                            if(player==null)return; // guest deliberately reconnects before the combat phase
                            if(combatStep==0){
                                if(!Files.exists(peer.resolve("item-packet.txt")))return;
                                ItemSmoke.check(player,server.getPlayerList().getPlayers().stream().filter(p->!p.getUUID().equals(player.getUUID())).findFirst().orElseThrow(),mc.gameDirectory.toPath().resolve("items.txt"));
                                player.setGameMode(GameType.SURVIVAL);player.setHealth(20);player.setYRot(0);
                                player.setItemSlot(net.minecraft.world.entity.EquipmentSlot.HEAD,new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.IRON_HELMET));
                                player.setItemSlot(net.minecraft.world.entity.EquipmentSlot.CHEST,new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.IRON_CHESTPLATE));
                                player.setItemSlot(net.minecraft.world.entity.EquipmentSlot.LEGS,new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.IRON_LEGGINGS));
                                player.setItemSlot(net.minecraft.world.entity.EquipmentSlot.FEET,new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.IRON_BOOTS));
                                player.setItemSlot(net.minecraft.world.entity.EquipmentSlot.OFFHAND,new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.SHIELD));
                                player.startUsingItem(net.minecraft.world.InteractionHand.OFF_HAND);combatAt=server.getTickCount();combatStep=1;
                            }else if(combatStep==1&&server.getTickCount()-combatAt>=100){
                                if(!player.isBlocking())throw new IllegalStateException("Shield inactive");
                                dev.skycraft.combat.SkyCombat.hurtPlayer(player,dev.skycraft.link.Proto.HURT_MELEE,178.48f,0,0,player.position().add(player.getLookAngle().scale(3)));
                                if(player.getHealth()!=20)throw new IllegalStateException("Front shield did not block guest origin");
                                Files.writeString(mc.gameDirectory.toPath().resolve("shield-done.txt"),"release");
                                player.stopUsingItem();combatAt=server.getTickCount();combatStep=2;
                            }else if(combatStep==2&&server.getTickCount()-combatAt>=25){
                                if(player.isBlocking())throw new IllegalStateException("Shield still raised in armor test");
                                dev.skycraft.combat.SkyCombat.hurtPlayer(player,dev.skycraft.link.Proto.HURT_MELEE,178.48f,0,0,player.position().add(player.getLookAngle().scale(3)));
                                float lost=20-player.getHealth();if(!(lost>0&&lost<12))throw new IllegalStateException("Armor did not mitigate capped hit: "+lost);
                                combatResult="PASS";Files.writeString(mc.gameDirectory.toPath().resolve("combat.txt"),"PASS shield front blocked; iron armor mitigated 12 HP cap to "+lost);
                            }
                        }catch(Exception e){combatResult="FAIL: "+e;}
                    });
                    if(combatResult!=null){if(!combatResult.equals("PASS"))throw new IllegalStateException(combatResult);step=5;}
                }
                case 5 -> {
                    if(Files.exists(peer.resolve("smoke-result.txt"))){dev.skycraft.client.MirrorWorld.itemTestWorld(mc);step=7;}
                }
                case 7 -> {
                    if(mc.level==null){dev.skycraft.client.MirrorWorld.openWhenReady(mc);return;}
                    if(mc.getSingleplayerServer()==null||!mc.getSingleplayerServer().getWorldData().getLevelName().equals("SkyCraft-Items-Test"))return;
                    finish(mc,"PASS: LAN menu; guest PLAY; DigOpen host/guest; chunk-load resync; shield/armor; NPC ownership and guest hit packet; real keyboard release; item conversion and durable overflow; item test world isolated");
                }
            }
        }catch(Exception e){dev.skycraft.SkyCraft.LOG.error("Gameplay smoke failed",e);finish(mc,"FAIL: "+e);}
    }
    private void guest(Minecraft mc)throws Exception {
        if(step==0){
            if(!(mc.gui.screen() instanceof TitleScreen title)||!Files.exists(peer.resolve("port.txt")))return;
            NetworkClient.connect(mc,title,dev.skycraft.network.Endpoint.parse("127.0.0.1:"+Files.readString(peer.resolve("port.txt"))));step=1;
        }else if(step==1){
            if(mc.player==null||mc.level==null||mc.isLocalServer())return;
            dug=mc.player.blockPosition().offset(1,0,0);
            ClientPlayNetworking.send(new SkyNet.DigOpen(0x3c,dug,dev.skycraft.link.Proto.DIG_STONE));
            Files.writeString(mc.gameDirectory.toPath().resolve("dug.txt"),dug.getX()+","+dug.getY()+","+dug.getZ());step=2;
        }else if(step==2){
			if(mc.level==null)throw new IllegalStateException("Disconnected before dig snapshot: "+NetworkClient.lastFailure());
            if(!SkyDig.isDug(mc.level,0x3c,dug)||!Files.exists(peer.resolve("server-dug.txt")))return;
            String log=Files.readString(mc.gameDirectory.toPath().resolve("logs/latest.log"));
            if(!log.contains("1 world sections"))return;
            mc.disconnectWithSavingScreen();mc.gui.setScreen(new TitleScreen());ticks=0;step=3;
        }else if(step==3){
            if(++ticks<20)return;
            NetworkClient.connect(mc,new TitleScreen(),dev.skycraft.network.Endpoint.parse("127.0.0.1:"+Files.readString(peer.resolve("port.txt"))));step=4;
        }else if(step==4){
            if(mc.level==null||mc.player==null||mc.isLocalServer())return;
            if(mc.gui.screen()!=null)return; // A player/chunk exists before the join loading screen closes.
            if(!SkyDig.isDug(mc.level,0x3c,dug))return;
            itemPackets.tick(mc);
            inputCheckOnce(mc);
            if(ticks++%2==0)ClientPlayNetworking.send(new dev.skycraft.net.ActorSync.Actors(java.util.List.of(new dev.skycraft.link.SkyLink.Actor(0x1234,0,(float)mc.player.getX()+1,(float)mc.player.getY(),(float)mc.player.getZ(),0,0.6f,1.8f,1,1,"Guest actor"))));
            if(dev.skycraft.client.ActorSyncClient.receivedHits>0)Files.writeString(mc.gameDirectory.toPath().resolve("actor-hit.txt"),"PASS: hit returned through owner clientbound packet");
            mc.options.keyUse.setDown(!Files.exists(peer.resolve("shield-done.txt")));
            Files.writeString(mc.gameDirectory.toPath().resolve("rejoined.txt"),"ready");
            if(Files.exists(peer.resolve("combat.txt")))finish(mc,"PASS: DigOpen mirrored into guest chunk; explicit dig snapshot observed; persisted dig restored after reconnect; combat checks passed on real server");
        }
    }
    private boolean inputChecked;
    private void inputCheckOnce(Minecraft mc)throws Exception{if(!inputChecked){inputCheck(mc);inputChecked=true;}}
    private static void inputCheck(Minecraft mc)throws Exception {
        collisionCheck(mc);
        var key=dev.skycraft.client.InputBridge.class.getDeclaredMethod("key",Minecraft.class,long.class,int.class,boolean.class);key.setAccessible(true);
        long handle=mc.getWindow().handle();
        key.invoke(null,mc,handle,225,true);key.invoke(null,mc,handle,26,true);
        if(!mc.options.keyUp.isDown())throw new IllegalStateException("Real shifted W press missed binding (screen="+mc.gui.screen()+", mapping="+mc.options.keyUp.saveString()+")");
        key.invoke(null,mc,handle,225,false);key.invoke(null,mc,handle,26,false);
        if(mc.options.keyUp.isDown())throw new IllegalStateException("W remained held after modifier release");
        mc.options.keyUp.setDown(true);mc.options.keyUse.setDown(true);
        dev.skycraft.client.InputBridge.releaseAll();
        if(mc.options.keyUp.isDown()||mc.options.keyUse.isDown())throw new IllegalStateException("Orphan bindings survived releaseAll");
        Files.writeString(mc.gameDirectory.toPath().resolve("input-check.txt"),"PASS: actual keyPress Shift+W/release; orphan movement and use bindings cleared");
    }
    private static void collisionCheck(Minecraft mc)throws Exception {
        var store=dev.skycraft.world.SkyCollision.class;
        var clear=store.getDeclaredMethod("clear",int.class);clear.setAccessible(true);
        var tris=store.getDeclaredMethod("readTris",java.lang.foreign.MemorySegment.class,long.class);tris.setAccessible(true);
        var voxels=store.getDeclaredMethod("readRegion",java.lang.foreign.MemorySegment.class,long.class);voxels.setAccessible(true);
        var sky=dev.skycraft.client.SkyClient.sky();int flags=sky.flags;
        double distance=mc.player.fallDistance;
        try(var arena=java.lang.foreign.Arena.ofConfined()) {
            clear.invoke(null,991);sky.flags=dev.skycraft.link.Proto.SKY_IN_GAME;
            var move=new net.minecraft.world.phys.Vec3(.1,-.0784,0);
            if(!dev.skycraft.client.SkyCollider.collide(mc.player,move).equals(net.minecraft.world.phys.Vec3.ZERO))throw new IllegalStateException("Unknown geometry allowed player to fall");
            int rx=(int)Math.floor(mc.player.getX()/8),ry=(int)Math.floor(mc.player.getY()/8),rz=(int)Math.floor(mc.player.getZ()/8);
            var mem=arena.allocate(112,8);
            for(int x=rx-1;x<=rx+1;x++)for(int y=ry-1;y<=ry+1;y++)for(int z=rz-1;z<=rz+1;z++) {
                mem.fill((byte)0);
                int[] header={x*8,y*8,z*8,x*8+7,y*8+7,z*8+7,991,0};
                for(int i=0;i<header.length;i++)mem.set(java.lang.foreign.ValueLayout.JAVA_INT,i*4L,header[i]);
                tris.invoke(null,mem,0L);voxels.invoke(null,mem,0L);
            }
            if(!dev.skycraft.client.SkyCollider.collide(mc.player,move).equals(move))throw new IllegalStateException("Known empty space blocked legitimate falling");
            double floor=mc.player.getY()+.05;
            mem.fill((byte)0);
            int fy=(int)Math.floor(floor/8),minX=rx*8,minZ=rz*8;
            int[] header={minX,fy*8,minZ,minX+7,fy*8+7,minZ+7,991,2};
            for(int i=0;i<header.length;i++)mem.set(java.lang.foreign.ValueLayout.JAVA_INT,i*4L,header[i]);
            float[][] vertices={{minX,(float)floor,minZ,minX+8,(float)floor,minZ+8,minX+8,(float)floor,minZ},{minX,(float)floor,minZ,minX,(float)floor,minZ+8,minX+8,(float)floor,minZ+8}};
            for(int i=0;i<2;i++) {
                for(int n=0;n<9;n++)mem.set(java.lang.foreign.ValueLayout.JAVA_FLOAT,32+i*40L+n*4L,vertices[i][n]);
                mem.set(java.lang.foreign.ValueLayout.JAVA_INT,32+i*40L+36,dev.skycraft.link.Proto.TRI_TERRAIN);
            }
            tris.invoke(null,mem,0L);mc.player.fallDistance=.1;
            if(dev.skycraft.client.SkyCollider.collide(mc.player,move).y<=0)throw new IllegalStateException("Late terrain did not repair shallow penetration");
            sky.flags|=dev.skycraft.link.Proto.SKY_TAKEOVER;
            if(!dev.skycraft.client.SkyCollider.collide(mc.player,move).equals(net.minecraft.world.phys.Vec3.ZERO))throw new IllegalStateException("Minecraft moved during Skyrim takeover");
            Files.writeString(mc.gameDirectory.toPath().resolve("collision-check.txt"),"PASS: actual collider holds unknown geometry; known empty permits falling; late terrain lifts feet; Skyrim takeover freezes Minecraft");
        }finally{sky.flags=flags;mc.player.fallDistance=distance;clear.invoke(null,-1);}
    }
    private static void press(Minecraft mc,String label){((Button)Screens.getWidgets(mc.gui.screen()).stream().filter(w->w instanceof Button&&w.getMessage().getString().equals(label)).findFirst().orElseThrow()).onPress(new KeyEvent(257,0,0));}
    private void finish(Minecraft mc,String result){
        done=true;mc.options.keyUse.setDown(false);try{Files.writeString(mc.gameDirectory.toPath().resolve("smoke-result.txt"),result);}catch(Exception e){throw new RuntimeException(e);}mc.stop();
    }
}
