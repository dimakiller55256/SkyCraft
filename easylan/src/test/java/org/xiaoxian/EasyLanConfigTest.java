// EasyLAN by XiaoXianHW / Darf; YS adaptation for Minecraft 26.3 (2026-10-05).
// SPDX-License-Identifier: GPL-3.0-only
package org.xiaoxian;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.xiaoxian.easylan.core.config.EasyLanConfig;
import org.xiaoxian.easylan.core.validation.ValidationRules;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;
class EasyLanConfigTest {
    @TempDir Path dir;
    @Test void missingConfigDefaultsToOnline() { var c=new EasyLanConfig(dir.resolve("cfg"));c.load();assertTrue(c.getRuleProfile().isOnlineMode()); }
    @Test void explicitOfflinePersistsWithPortAndUnicode() {
        var c=new EasyLanConfig(dir.resolve("cfg"));c.getRuleProfile().setOnlineMode(false);
        c.getRuleProfile().setMotd("Тест LAN");c.setCustomPort("25570");c.setCustomMaxPlayer("8");c.save();
        var reloaded=new EasyLanConfig(dir.resolve("cfg"));reloaded.load();
        assertFalse(reloaded.getRuleProfile().isOnlineMode());assertEquals("Тест LAN",reloaded.getRuleProfile().getMotd());
        assertEquals("25570",reloaded.getCustomPort());assertEquals("8",reloaded.getCustomMaxPlayer());
    }
    @Test void malformedOnlineModeCannotDisableVerification() throws Exception {
        Files.writeString(dir.resolve("cfg"),"online-mode=garbage\n",StandardCharsets.UTF_8);
        var c=new EasyLanConfig(dir.resolve("cfg"));c.load();assertTrue(c.getRuleProfile().isOnlineMode());
    }
    @Test void readsUtf8AndUtf16Bom() throws Exception {
        for(var charset:new java.nio.charset.Charset[]{StandardCharsets.UTF_8,StandardCharsets.UTF_16}) {
            Files.write(dir.resolve("cfg"),"online-mode=false\nMotd=Мой мир\n".getBytes(charset));
            var c=new EasyLanConfig(dir.resolve("cfg"));c.load();assertFalse(c.getRuleProfile().isOnlineMode());
            assertEquals("Мой мир",c.getRuleProfile().getMotd());
        }
    }
    @Test void invalidPortsAndPlayerCountsRejected() {
        assertFalse(ValidationRules.isValidPort(0));assertFalse(ValidationRules.isValidPort(65536));
        assertTrue(ValidationRules.isValidPort(25565));assertFalse(ValidationRules.isValidMaxPlayer(1));
        assertFalse(ValidationRules.isValidMaxPlayer(500001));assertTrue(ValidationRules.isValidMaxPlayer(20));
    }
    @Test void saveFailureIsReportedAndPreservesPreviousFile() throws Exception {
        Path file=dir.resolve("cfg");Files.writeString(file,"online-mode=true\n");
        Files.createDirectory(dir.resolve("cfg.tmp"));
        var c=new EasyLanConfig(file);c.getRuleProfile().setOnlineMode(false);
        assertFalse(c.save());assertNotNull(c.getLastError());assertEquals("online-mode=true\n",Files.readString(file));
    }
    @Test void damagedPropertyFileRetainsOnlineDefault() throws Exception {
        Files.writeString(dir.resolve("cfg"),"online-mode=false\nMotd=\\uqqqq\n");
        var c=new EasyLanConfig(dir.resolve("cfg"));c.load();assertTrue(c.getRuleProfile().isOnlineMode());assertNotNull(c.getLastError());
    }
}
