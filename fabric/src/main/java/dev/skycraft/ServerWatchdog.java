package dev.skycraft;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;

/** A server tick stuck for seconds freezes every player: the log shows where it was stuck. */
final class ServerWatchdog {
	private static final long STUCK_NANOS = 2_000_000_000L;
	private static volatile long tickAt;
	private static volatile Thread serverThread;
	private static boolean started;

	private ServerWatchdog() {
	}

	static void init() {
		ServerTickEvents.START_SERVER_TICK.register(server -> tickAt = System.nanoTime());
		ServerLifecycleEvents.SERVER_STARTED.register(ServerWatchdog::watch);
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> serverThread = null);
	}

	private static void watch(MinecraftServer server) {
		serverThread = server.getRunningThread();
		tickAt = System.nanoTime();
		if (started) {
			return;
		}
		started = true;
		Thread.ofPlatform().daemon().name("SkyCraft watchdog").start(() -> {
			long reported = 0;
			while (true) {
				try {
					Thread.sleep(1000);
				} catch (InterruptedException e) {
					return;
				}
				Thread thread = serverThread;
				long tick = tickAt;
				if (thread != null && tick != reported && System.nanoTime() - tick > STUCK_NANOS) {
					reported = tick;
					StringBuilder stack = new StringBuilder();
					for (var frame : thread.getStackTrace()) {
						stack.append("\n\tat ").append(frame);
					}
					SkyCraft.LOG.warn("SkyCraft: server tick stuck for over 2 s in:{}", stack);
				}
			}
		});
	}
}
