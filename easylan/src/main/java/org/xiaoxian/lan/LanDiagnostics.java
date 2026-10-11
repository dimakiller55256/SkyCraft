// EasyLAN by XiaoXianHW / Darf; YS adaptation for Minecraft 26.3 (2026-10-05).
// SPDX-License-Identifier: GPL-3.0-only
package org.xiaoxian.lan;

import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.net.*;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.ZoneOffset;
import java.util.*;
import java.util.zip.*;
import java.io.IOException;

/** Local reports contain no launcher accounts, access tokens, or full game logs. */
public final class LanDiagnostics {
    private static final com.google.gson.Gson JSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String RUN = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC).format(Instant.now())
            + "-" + UUID.randomUUID().toString().substring(0,6);
    private static final Path DIR = FabricLoader.getInstance().getGameDir().resolve("easylan-reports");
    private static final Path EVENTS = DIR.resolve(RUN + "-events.jsonl");
    private static int count;
    private LanDiagnostics() {}
    public static synchronized void event(String type, Object... fields) {
        if (count++ >= 4000) return;
        Map<String,Object> data = new LinkedHashMap<>();
        data.put("timeUtc", Instant.now().toString()); data.put("event",type); data.put("sequence", count);
        for (int i=0;i+1<fields.length;i+=2) data.put(String.valueOf(fields[i]),fields[i+1]);
        try {
            Files.createDirectories(DIR);
            Files.writeString(EVENTS,new com.google.gson.Gson().toJson(data)+"\n",StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,StandardOpenOption.APPEND);
        } catch(IOException e) { org.slf4j.LoggerFactory.getLogger("EasyLAN").warn("Cannot write LAN diagnostics: {}",e.toString()); }
    }
    public record Address(String adapter,String ip) {
        public String endpoint(int port) { return (ip.contains(":")?"["+ip+"]":ip)+":"+port; }
    }
    public static List<Address> addresses() {
        List<Address> list = new ArrayList<>();
        try {
            for (NetworkInterface n : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!n.isUp() || n.isLoopback()) continue;
                for(InetAddress a:Collections.list(n.getInetAddresses())) {
                    if(a instanceof Inet4Address && !a.isLinkLocalAddress()) list.add(new Address(n.getDisplayName(),a.getHostAddress()));
                }
            }
        } catch(SocketException e) { event("adapter_error","type",e.getClass().getSimpleName()); }
        list.sort(Comparator.comparingInt(a -> a.adapter().toLowerCase(Locale.ROOT).contains("radmin")?0:1));
        list.add(new Address("localhost", "127.0.0.1"));
        return list;
    }
    public static Path export(Minecraft mc) throws IOException {
        event("report_requested");
        Map<String,Object> info = new LinkedHashMap<>();
        info.put("runId",RUN); info.put("timeUtc",Instant.now().toString());
        info.put("os",System.getProperty("os.name")); info.put("osVersion",System.getProperty("os.version"));
        info.put("java",System.getProperty("java.version")); info.put("adapters",addresses());
        info.put("mods",FabricLoader.getInstance().getAllMods().stream().map(m -> Map.of(
                "id",m.getMetadata().getId(),"version",m.getMetadata().getVersion().getFriendlyString())).toList());
        var server = mc.getSingleplayerServer();
        info.put("localWorld",server!=null); info.put("remoteWorld",mc.level!=null && server==null);
        if(server!=null) {
            info.put("published",server.isPublished()); info.put("port",server.getPort());
            info.put("authentication",server.usesAuthentication()?"ONLINE":"OFFLINE");
            info.put("players",server.getPlayerCount()); info.put("maxPlayers",server.getMaxPlayers());
        }
        Files.createDirectories(DIR);
        Path zip = DIR.resolve("EasyLAN-"+RUN+"-"+System.currentTimeMillis()+".zip");
        try(ZipOutputStream out=new ZipOutputStream(Files.newOutputStream(zip))) {
            put(out,"environment.json",JSON.toJson(info).getBytes(StandardCharsets.UTF_8));
            if(Files.exists(EVENTS)) put(out,"events.jsonl",Files.readAllBytes(EVENTS));
            Path config = FabricLoader.getInstance().getConfigDir().resolve("easylan.cfg");
            if(Files.exists(config)) put(out,"easylan.cfg",Files.readAllBytes(config));
            put(out,"README.txt",("Local EasyLAN diagnostics. No account databases or complete Minecraft logs are collected.\n"
                    +"Adapter addresses are local candidates; they do not prove reachability from another PC.\n").getBytes(StandardCharsets.UTF_8));
        }
        return zip;
    }
    private static void put(ZipOutputStream out,String name,byte[] bytes) throws IOException {
        out.putNextEntry(new ZipEntry(name)); out.write(bytes); out.closeEntry();
    }
}
