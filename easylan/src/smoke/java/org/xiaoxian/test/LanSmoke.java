// EasyLAN by XiaoXianHW / Darf; YS adaptation for Minecraft 26.3 (2026-10-05).
// SPDX-License-Identifier: GPL-3.0-only
package org.xiaoxian.test;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.*;
import net.minecraft.client.gui.screens.worldselection.SelectWorldScreen;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.*;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.SharedConstants;
import org.xiaoxian.EasyLAN;
import org.xiaoxian.gui.*;
import org.xiaoxian.lan.*;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** Two actual isolated Minecraft clients; no Skyrim, launcher accounts, or installed saves. */
public final class LanSmoke implements ClientModInitializer {
    private final boolean host="host".equals(System.getProperty("easylan.smokeRole"));
    private final long deadline=System.nanoTime()+240_000_000_000L;
    private int step,port,ticks;
    private boolean finished;
    private CompletableFuture<Integer> challenge;
    private Path peer;
    private Screen selection;
    @Override public void onInitializeClient() { ClientTickEvents.END_CLIENT_TICK.register(this::tick); }
    private void tick(Minecraft mc) {
        if(finished) return;
        try {
            peer=mc.gameDirectory.toPath().resolveSibling(host?"easylan-smoke-client":"easylan-smoke-host");
            if(System.nanoTime()>deadline) throw new IOException("Timeout at step "+step);
            if(Files.exists(peer.resolve("smoke-result.txt")) && Files.readString(peer.resolve("smoke-result.txt")).startsWith("FAIL"))
                throw new IOException("Peer test failed");
            if(mc.gui.overlay()!=null) return;
            if(mc.gui.screen()!=null&&mc.gui.screen().getClass().getName().contains("Onboarding")) mc.gui.setScreen(new TitleScreen());
            if(!host) { guest(mc); return; }
            var server=mc.getSingleplayerServer();
            switch(step) {
                case 0 -> {
                    if(!(mc.gui.screen() instanceof TitleScreen title)) return;
                    selection=new SelectWorldScreen(title); mc.gui.setScreen(selection);
                    ticks=0;step=10;
                }
                case 10 -> {
                    if(++ticks<3)return;
                    // With no saved worlds vanilla immediately opens CreateWorldScreen.
                    // Inspect the initialized selection screen itself, not its successor.
                    if(Screens.getWidgets(selection).stream().noneMatch(w->w.getMessage().getString().equals("EasyLAN")))
                        throw new IOException("World selection settings button absent");
                    var title=new TitleScreen();
                    mc.gui.setScreen(title);
                    var settings=new LevelSettings("EasyLAN smoke",GameType.CREATIVE,
                        new LevelSettings.DifficultySettings(Difficulty.PEACEFUL,false,false),true,WorldDataConfiguration.DEFAULT);
                    mc.createWorldOpenFlows().createFreshLevel("EasyLAN-smoke-"+UUID.randomUUID(),settings,
                        new WorldOptions(0L,false,false),r->r.lookupOrThrow(Registries.WORLD_PRESET)
                        .getOrThrow(WorldPresets.FLAT).value().createWorldDimensions(),title);
                    step=1;
                }
                case 1 -> {
                    if(server==null||mc.player==null) return;
                    mc.gui.setScreen(new PauseScreen(true));
                    ticks=0;step=11;
                }
                case 11 -> {
                    if(++ticks<3)return;
                    if(Screens.getWidgets(mc.gui.screen()).stream().noneMatch(w->w.getMessage().getString().contains("EasyLAN")))
                        throw new IOException("Pause LAN button absent");
                    EasyLAN.onlineMode=true; mc.gui.setScreen(new EasyLanPublishScreen(mc.gui.screen()));
                    ticks=0;step=2;
                }
                case 2 -> {
                    if(++ticks<20) return;
                    screenshot(mc,"01-lan-online.png");
                    try(var free=new ServerSocket(0)) {port=free.getLocalPort();}
                    if(!ShareToLan.publish(server,port,8,true)||!server.usesAuthentication()) throw new IOException("ONLINE publish failed");
                    challenge=CompletableFuture.supplyAsync(()->{try{return onlineChallenge(port);}catch(Exception e){throw new RuntimeException(e);}});
                    step=3;
                }
                case 3 -> {
                    if(!challenge.isDone()) return;
                    if(challenge.join()!=1) throw new IOException("ONLINE did not request session encryption");
                    if(!server.unpublishServer()) throw new IOException("Unpublish failed");
                    new ShareToLan().handleStop();
                    mc.gui.setScreen(new EasyLanPublishScreen(new PauseScreen(true)));
                    ticks=0;step=12;
                }
                case 12 -> {
                    if(++ticks<3)return;
                    var fields=Screens.getWidgets(mc.gui.screen()).stream().filter(w->w instanceof EditBox).map(w->(EditBox)w).toList();
                    fields.get(0).setValue(Integer.toString(port)); fields.get(1).setValue("8");
                    var auth=Screens.getWidgets(mc.gui.screen()).stream().filter(w->w instanceof Button&&w.getMessage().getString().contains("ONLINE")).findFirst().orElseThrow();
                    ((Button)auth).onPress(new KeyEvent(257,0,0)); ticks=0;step=4;
                }
                case 4 -> {
                    if(++ticks<20)return; screenshot(mc,"02-lan-offline.png");
                    ((EasyLanPublishScreen)mc.gui.screen()).publish();
                    if(!server.isPublished()||server.usesAuthentication()||server.getPort()!=port||server.getMaxPlayers()!=8)
                        throw new IOException("OFFLINE GUI publish or max players failed");
                    EasyLAN.allowFlight=false; if(server.allowFlight())throw new IOException("Flight setting not applied"); EasyLAN.allowFlight=true;
                    Files.writeString(mc.gameDirectory.toPath().resolve("port.txt"),Integer.toString(port));
                    ticks=0;step=5;
                }
                case 5 -> {
                    if(++ticks==20) screenshot(mc,"03-lan-addresses.png");
                    if(server.getPlayerCount()<2) return;
                    if(!Files.exists(peer.resolve("smoke-result.txt")))return;
                    String result=Files.readString(peer.resolve("smoke-result.txt"));
                    if(!result.startsWith("PASS"))throw new IOException("Guest: "+result);
                    Path report=LanDiagnostics.export(mc);
                    try(var zip=new java.util.zip.ZipFile(report.toFile())) {
                        if(zip.getEntry("environment.json")==null||zip.getEntry("events.jsonl")==null)throw new IOException("Report incomplete");
                    }
                    finish(mc,"PASS: ONLINE encryption challenge; OFFLINE opened through LAN GUI; 2 real Minecraft players; max players; flight; ZIP report");
                }
            }
        }catch(Exception e){finish(mc,"FAIL: "+e);}
    }
    private void guest(Minecraft mc) throws Exception {
        if(step==0) {
            if(!(mc.gui.screen() instanceof TitleScreen title)||!Files.exists(peer.resolve("port.txt")))return;
            int p=Integer.parseInt(Files.readString(peer.resolve("port.txt")).trim());
            String address="127.0.0.1:"+p;
            var data=new ServerData("EasyLAN smoke",address,ServerData.Type.LAN);
            ConnectScreen.startConnecting(title,mc,ServerAddress.parseString(address),data,false,null);step=1;
        }else if(step==1) {
            if(mc.gui.screen() instanceof DisconnectedScreen)throw new IOException("Guest disconnected");
            if(mc.level==null||mc.player==null||mc.isLocalServer())return;
            LanDiagnostics.export(mc);
            Files.writeString(mc.gameDirectory.toPath().resolve("smoke-result.txt"),"PASS: offline client reached PLAY and loaded host world");
            step=2;
        }else if(step==2) {
            if(Files.exists(peer.resolve("smoke-result.txt"))) finish(mc,"PASS: offline client reached PLAY and loaded host world");
        }
    }
    private void screenshot(Minecraft mc,String name) { net.minecraft.client.Screenshot.grab(mc.gameDirectory,name,mc.gameRenderer.mainRenderTarget(),1,c->{}); }
    private void finish(Minecraft mc,String result) {
        finished=true;
        try{Files.writeString(mc.gameDirectory.toPath().resolve("smoke-result.txt"),result);}catch(IOException e){throw new RuntimeException(e);}
        org.slf4j.LoggerFactory.getLogger("EasyLAN smoke").info(result);mc.stop();
    }
    private static int onlineChallenge(int port) throws Exception {
        try(var s=new Socket("127.0.0.1",port)) {
            s.setSoTimeout(10000);
            var handshake=new ByteArrayOutputStream();var h=new DataOutputStream(handshake);
            vi(h,0);vi(h,SharedConstants.getProtocolVersion());str(h,"127.0.0.1");h.writeShort(port);vi(h,2);send(s,handshake.toByteArray());
            var hello=new ByteArrayOutputStream();var l=new DataOutputStream(hello);
            vi(l,0);str(l,"LANAuthProbe");UUID id=UUID.nameUUIDFromBytes("OfflinePlayer:LANAuthProbe".getBytes(StandardCharsets.UTF_8));
            l.writeLong(id.getMostSignificantBits());l.writeLong(id.getLeastSignificantBits());send(s,hello.toByteArray());
            int len=vi(s.getInputStream());if(len<1||len>65536)throw new IOException("Bad login frame");
            return vi(new ByteArrayInputStream(s.getInputStream().readNBytes(len)));
        }
    }
    private static void send(Socket s,byte[] data)throws IOException {vi(s.getOutputStream(),data.length);s.getOutputStream().write(data);s.getOutputStream().flush();}
    private static void str(OutputStream out,String s)throws IOException{byte[] b=s.getBytes(StandardCharsets.UTF_8);vi(out,b.length);out.write(b);}
    private static void vi(OutputStream out,int value)throws IOException{while((value&~127)!=0){out.write((value&127)|128);value>>>=7;}out.write(value);}
    private static int vi(InputStream in)throws IOException{int value=0;for(int i=0;i<5;i++){int b=in.read();if(b<0)throw new EOFException();value|=(b&127)<<(7*i);if((b&128)==0)return value;}throw new IOException("VarInt overflow");}
}
