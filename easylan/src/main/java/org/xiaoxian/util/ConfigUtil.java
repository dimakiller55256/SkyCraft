// EasyLAN by XiaoXianHW / Darf; YS adaptation for Minecraft 26.3 (2026-10-05).
// SPDX-License-Identifier: GPL-3.0-only
package org.xiaoxian.util;

import org.xiaoxian.EasyLAN;

public class ConfigUtil {
    public static void load() {
        EasyLAN.getConfig().load();
        EasyLAN.syncFromConfig();
        if(EasyLAN.getConfig().getLastError()!=null)
            org.xiaoxian.lan.LanDiagnostics.event("config_read_error","type",EasyLAN.getConfig().getLastError());
    }

    public static boolean save() {
        EasyLAN.syncToConfig();
        boolean ok=EasyLAN.getConfig().save();
        if(!ok) org.xiaoxian.lan.LanDiagnostics.event("config_write_error","type",EasyLAN.getConfig().getLastError());
        return ok;
    }

    public static String get(String key) {
        return EasyLAN.getConfig().getRawValue(key);
    }

    public static void set(String key, String value) {
        EasyLAN.getConfig().setRawValue(key, value);
    }
}
