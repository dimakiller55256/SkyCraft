package dev.skycraft.link;

/** A short input lease is separate from the longer loading/reconnect link timeout. */
public final class InputSafety {
    private InputSafety() {}
    public static boolean fresh(long heartbeatAgeMs) {
        return heartbeatAgeMs >= 0 && heartbeatAgeMs < 350;
    }
    public static boolean accept(int type, boolean controlsEnabled) {
        return controlsEnabled || (type != Proto.IN_KEY && type != Proto.IN_MOUSE_BUTTON
            && type != Proto.IN_SCROLL && type != Proto.IN_CURSOR && type != Proto.IN_TEXT
            && type != Proto.IN_OPEN_MENU);
    }
}
