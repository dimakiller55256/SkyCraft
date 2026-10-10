package dev.skycraft.items;

import com.google.gson.Gson;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.List;
import java.util.ArrayList;

/** Data-only state machine, persisted with the player's inventory. Never prune replay receipts. */
public final class ConversionLedger {
    public static final int LIMIT=8192;
    private static final Gson JSON=new Gson();
    public Map<String,Receipt> receipts=new LinkedHashMap<>();
    public record Receipt(String character,String plugin,int localForm,String item,int count,boolean delivered,int overflow) {
        public boolean same(String character,String plugin,int form,String item,int count) {
            return this.character.equals(character)&&this.plugin.equals(plugin)&&localForm==form&&this.item.equals(item)&&this.count==count;
        }
        public Receipt delivered(int overflow){return new Receipt(character,plugin,localForm,item,count,true,overflow);}
        public Receipt remainder(int overflow){return new Receipt(character,plugin,localForm,item,count,delivered,overflow);}
    }
    public Receipt prepare(String id,String character,String plugin,int form,String item,int count) {
        UUID.fromString(id);UUID.fromString(character);
        if(form<0||form>0xffffff||plugin.isBlank()||plugin.length()>127||item.length()>95||count<1||count>4096)throw new IllegalArgumentException("Invalid item conversion request");
        Receipt old=receipts.get(id);
        if(old!=null) {
            if(!old.same(character,plugin,form,item,count))throw new IllegalArgumentException("Transaction payload changed");
            return old;
        }
        if(receipts.size()>=LIMIT)throw new IllegalArgumentException("Receipt journal full");
        var r=new Receipt(character,plugin,form,item,count,false,0);receipts.put(id,r);return r;
    }
    public String json(){return JSON.toJson(this);}
    /** NBT string tags use a bounded UTF encoding; long histories are saved as small chunks. */
    public static List<String> chunks(String text){
        var out=new ArrayList<String>();int i=0;
        while(i<text.length()){
            int end=Math.min(text.length(),i+1024);
            if(end<text.length()&&Character.isHighSurrogate(text.charAt(end-1))&&Character.isLowSurrogate(text.charAt(end)))end--;
            out.add(text.substring(i,end));i=end;
        }
        return out;
    }
    public static ConversionLedger parse(String text) {
        if(text==null||text.isEmpty())return new ConversionLedger();
        if(text.length()>8*1024*1024)throw new IllegalArgumentException("Oversized receipt journal");
        ConversionLedger out=JSON.fromJson(text,ConversionLedger.class);
        if(out==null||out.receipts==null||out.receipts.size()>LIMIT)throw new IllegalArgumentException("Invalid receipt journal");
        for(var entry:out.receipts.entrySet()) {
            UUID.fromString(entry.getKey());var r=entry.getValue();UUID.fromString(r.character);
            if(r.count<1||r.count>4096||r.overflow<0||r.overflow>r.count||(!r.delivered&&r.overflow!=0))throw new IllegalArgumentException("Invalid receipt");
        }
        return out;
    }
}
