// EasyLAN by XiaoXianHW / Darf; YS adaptation for Minecraft 26.3 (2026-10-05).
// SPDX-License-Identifier: GPL-3.0-only
package org.xiaoxian.easylan.core.validation;

public final class ValidationRules {
    private ValidationRules() {
    }

    public static boolean isValidPort(int port) {
        return port >= 100 && port <= 65535;
    }

    public static boolean isValidMaxPlayer(int maxPlayer) {
        return maxPlayer >= 2 && maxPlayer <= 500000;
    }

    public static boolean isValidMotdLength(String motd) {
        return motd != null && motd.length() <= 100;
    }
}
