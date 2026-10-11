package dev.skycraft.items;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class EnchantedBookSpecTest {
    @Test void fixesSingleAndTwinContentsWithoutRandomness() {
        assertEquals(Map.of("minecraft:fire_aspect",2),EnchantedBookSpec.parse("fire_aspect=2"));
        var twin=EnchantedBookSpec.parse("unbreaking=2,mending=1");
        assertEquals(Map.of("minecraft:unbreaking",2,"minecraft:mending",1),twin);
        assertEquals(twin,EnchantedBookSpec.parse("unbreaking=2,mending=1"));
    }
    @Test void rejectsMalformedAndDuplicateEffects() {
        for(String text:new String[]{"","sharpness","sharpness=0","sharpness=-1","sharpness=256","sharpness=2,",
            "Sharpness=1","sharpness=01","sharpness=1=2","sharpness=1,minecraft:sharpness=2"," =1"})
            assertThrows(IllegalArgumentException.class,()->EnchantedBookSpec.parse(text),text);
    }
    @Test void acceptsNamespacedEnchantmentAndPreservesLevels() {
        assertEquals(Map.of("example:arcane/sigil",3),EnchantedBookSpec.parse("example:arcane/sigil=3"));
    }
    @Test void replayKeepsBothEffectsAndRejectsChangedBook() {
        String id=UUID.randomUUID().toString(),character=UUID.randomUUID().toString();
        String book="minecraft:enchanted_book#unbreaking=2,mending=1";
        var ledger=new ConversionLedger();var receipt=ledger.prepare(id,character,"Skyrim.esm",0x2f839,book,2);
        ledger.receipts.put(id,receipt.delivered(2));var loaded=ConversionLedger.parse(ledger.json());
        assertEquals(book,loaded.prepare(id,character,"Skyrim.esm",0x2f839,book,2).item());
        assertThrows(IllegalArgumentException.class,()->loaded.prepare(id,character,"Skyrim.esm",0x2f839,
            "minecraft:enchanted_book#unbreaking=3,mending=1",2));
    }
}
