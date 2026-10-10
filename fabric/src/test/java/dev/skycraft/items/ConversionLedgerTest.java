package dev.skycraft.items;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ConversionLedgerTest {
    private final String id=UUID.randomUUID().toString(),character=UUID.randomUUID().toString();
    @Test void replaySurvivesRestartAndDoesNotReopenDelivery(){
        var ledger=new ConversionLedger();var receipt=ledger.prepare(id,character,"Skyrim.esm",0x5ace4,"minecraft:iron_ingot",90);
        ledger.receipts.put(id,receipt.delivered(54));
        var restarted=ConversionLedger.parse(ledger.json());var replay=restarted.prepare(id,character,"Skyrim.esm",0x5ace4,"minecraft:iron_ingot",90);
        assertTrue(replay.delivered());assertEquals(54,replay.overflow());assertEquals(1,restarted.receipts.size());
        restarted.receipts.put(id,replay.remainder(3));assertEquals(3,ConversionLedger.parse(restarted.json()).receipts.get(id).overflow());
    }
    @Test void rejectsPayloadChangesForSameTransaction(){
        var l=new ConversionLedger();l.prepare(id,character,"Skyrim.esm",42,"minecraft:iron_ingot",1);
        assertThrows(IllegalArgumentException.class,()->l.prepare(id,character,"Skyrim.esm",42,"minecraft:diamond",1));
        assertThrows(IllegalArgumentException.class,()->l.prepare(id,UUID.randomUUID().toString(),"Skyrim.esm",42,"minecraft:iron_ingot",1));
        assertThrows(IllegalArgumentException.class,()->l.prepare(id,character,"Skyrim.esm",43,"minecraft:iron_ingot",1));
        assertThrows(IllegalArgumentException.class,()->l.prepare(id,character,"Skyrim.esm",42,"minecraft:iron_ingot",2));
    }
    @Test void rejectsInvalidCountsAndMissingIdentity(){
        for(int n:new int[]{0,-1,4097,Integer.MAX_VALUE})assertThrows(IllegalArgumentException.class,()->new ConversionLedger().prepare(id,character,"Skyrim.esm",42,"minecraft:iron_ingot",n));
        assertThrows(IllegalArgumentException.class,()->new ConversionLedger().prepare("",character,"Skyrim.esm",42,"minecraft:iron_ingot",1));
        assertThrows(IllegalArgumentException.class,()->ConversionLedger.parse("{\"receipts\":null}"));
    }
    @Test void rejectsCorruptOutboxRatherThanLosingIt(){
        var l=new ConversionLedger();var r=l.prepare(id,character,"Skyrim.esm",42,"minecraft:iron_ingot",10);l.receipts.put(id,r.delivered(11));
        assertThrows(IllegalArgumentException.class,()->ConversionLedger.parse(l.json()));
        l.receipts.put(id,r.remainder(1));assertThrows(IllegalArgumentException.class,()->ConversionLedger.parse(l.json()));
    }
    @Test void largeJournalIsChunkedBelowNbtStringLimit(){
        String text="предметы𐐷".repeat(20000);var chunks=ConversionLedger.chunks(text);
        assertEquals(text,String.join("",chunks));assertTrue(chunks.stream().allMatch(part->part.length()<=1024));
        assertTrue(chunks.stream().allMatch(part->part.getBytes(java.nio.charset.StandardCharsets.UTF_8).length<65535));
        assertTrue(chunks.stream().noneMatch(part->Character.isHighSurrogate(part.charAt(part.length()-1))));
    }
}
