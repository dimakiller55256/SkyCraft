package dev.skycraft.client;

import dev.skycraft.SkyCraft;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPreset;

/** Opens (or creates) the void "mirror" world automatically once Skyrim is connected. */
public final class MirrorWorld {
	private static final ResourceKey<WorldPreset> PRESET =
		ResourceKey.create(Registries.WORLD_PRESET, Identifier.fromNamespaceAndPath(SkyCraft.MOD_ID, "mirror"));
	private static boolean attempted;
	private static long lastLog;
	private static boolean readConfiguredJoin = true;
	// A friend's world for this session; null: our own.
	private static @org.jspecify.annotations.Nullable String sessionJoin;
	// Shown in chat once the player is in a world again (why they're back in their own, ...).
	private static @org.jspecify.annotations.Nullable String pendingNote;

	private MirrorWorld() {
	}

	/** /join: validate before leaving the current world. */
	public static void joinFriend(Minecraft minecraft, String link) {
		String address;
		try {
			address = dev.skycraft.network.Endpoint.parse(link).authority();
			NetworkClient.config(minecraft); // Invalid proxy settings must not unload the current world.
		} catch (java.io.IOException | IllegalArgumentException e) {
			minecraft.gui.hud.getChat().addClientSystemMessage(net.minecraft.network.chat.Component.literal("SkyCraft: " + e.getMessage()));
			return;
		}
		SkyCraft.LOG.info("SkyCraft: /join {}", address);
		sessionJoin = address;
		readConfiguredJoin = false;
		leaveWorld(minecraft);
	}

	/** The friend's world we're in (the address we joined), or null in our own. */
	public static @org.jspecify.annotations.Nullable String friendAddress(Minecraft minecraft) {
		if (sessionJoin != null) {
			return sessionJoin;
		}
		var server = minecraft.isLocalServer() ? null : minecraft.getCurrentServer();
		return server != null ? server.ip : null;
	}

	/** /leave: back to our own world. */
	public static void leaveFriend(Minecraft minecraft) {
		if (sessionJoin == null) {
			minecraft.gui.hud.getChat().addClientSystemMessage(net.minecraft.network.chat.Component.literal("You're already in your own world."));
			return;
		}
		SkyCraft.LOG.info("SkyCraft: /leave {}", sessionJoin);
		sessionJoin = null;
		readConfiguredJoin = false;
		pendingNote = "Back in your own world.";
		leaveWorld(minecraft);
	}

	private static void leaveWorld(Minecraft minecraft) {
		NetworkClient.cancel();
		attempted = false;
		minecraft.disconnectFromWorld(net.minecraft.client.multiplayer.ClientLevel.DEFAULT_QUIT_MESSAGE);
		minecraft.gui.setScreen(new TitleScreen());  // openWhenReady takes it from the title screen
	}

	/** Every client tick: a note for the player once they're in a world again. */
	public static void tick(Minecraft minecraft) {
		NetworkClient.tick(minecraft);
		if (pendingNote != null && minecraft.player != null) {
			minecraft.gui.hud.getChat().addClientSystemMessage(net.minecraft.network.chat.Component.literal(pendingNote));
			pendingNote = null;
		}
	}

	public static void openWhenReady(Minecraft minecraft) {
		if (attempted && sessionJoin != null && minecraft.level == null && minecraft.gui.screen() instanceof TitleScreen) {
			connectionFailed(minecraft, "Подключение отменено.");
			return;
		}
		// Couldn't reach a friend's world, or it closed under us: back to our own, and say why.
		if (minecraft.gui.screen() instanceof net.minecraft.client.gui.screens.DisconnectedScreen && minecraft.level == null) {
			connectionFailed(minecraft, NetworkClient.lastFailure() != null ? NetworkClient.lastFailure()
				: "Соединение закрыто. Проверьте адрес, доступность хоста и журнал Minecraft.");
			return;
		}
		if (attempted && minecraft.level == null && minecraft.gui.screen() != null && System.currentTimeMillis() - lastLog > 5000) {
			lastLog = System.currentTimeMillis();
			SkyCraft.LOG.info("SkyCraft: still not in the mirror world; current screen {}", minecraft.gui.screen().getClass().getName());
		}
		if (attempted || minecraft.level != null || minecraft.gui.overlay() != null) {
			return;
		}
		// Wait for the menu to settle on the title screen; skip any first-launch prompts in front of it.
		if (!(minecraft.gui.screen() instanceof TitleScreen)) {
			if (minecraft.gui.screen() != null && System.currentTimeMillis() - lastLog > 5000) {
				lastLog = System.currentTimeMillis();
				SkyCraft.LOG.info("SkyCraft: waiting on screen {} before opening the mirror world", minecraft.gui.screen().getClass().getName());
			}
			if (minecraft.gui.screen() == null || minecraft.gui.screen().getClass().getName().contains("Onboarding")) {
				minecraft.gui.setScreen(new TitleScreen());
			}
			return;
		}
		TitleScreen title = (TitleScreen) minecraft.gui.screen();
		attempted = true;
		// Read auto-join only once: /leave or a failed join must return to our world.
		if (readConfiguredJoin) {
			readConfiguredJoin = false;
			try {
				String configured = NetworkClient.config(minecraft).join();
				if (!configured.isEmpty()) sessionJoin = dev.skycraft.network.Endpoint.parse(configured).authority();
			} catch (java.io.IOException | IllegalArgumentException e) {
				pendingNote = "SkyCraft: настройки сети: " + e.getMessage() + ". Открыт свой мир.";
			}
		}
		String join = sessionJoin;
		if (join != null) {
			SkyCraft.LOG.info("SkyCraft: joining {}", join);
			pendingNote = "Joined " + join + ". Type /leave to go back to your own world.";
			NetworkClient.connect(minecraft, title, dev.skycraft.network.Endpoint.parse(join));
			return;
		}
		if (minecraft.getLevelSource().levelExists(SkyCraft.WORLD_NAME)) {
			SkyCraft.LOG.info("SkyCraft: opening mirror world");
			minecraft.createWorldOpenFlows().openWorld(SkyCraft.WORLD_NAME, () -> minecraft.gui.setScreen(title));
			return;
		}
		SkyCraft.LOG.info("SkyCraft: creating mirror world");
		LevelSettings settings = new LevelSettings(
			SkyCraft.WORLD_NAME,
			GameType.SURVIVAL,
			new LevelSettings.DifficultySettings(Difficulty.NORMAL, false, false),
			true,
			WorldDataConfiguration.DEFAULT
		);
		minecraft.createWorldOpenFlows().createFreshLevel(
			SkyCraft.WORLD_NAME,
			settings,
			new WorldOptions(0L, false, false),
			registries -> registries.lookupOrThrow(Registries.WORLD_PRESET).getOrThrow(PRESET).value().createWorldDimensions(),
			title
		);
	}

	public static void connectionFailed(Minecraft minecraft, String reason) {
		NetworkDiagnostics.event("return_to_own_world", java.util.Map.of("attempt_id", NetworkClient.activeAttempt()));
		pendingNote = "SkyCraft: " + reason + " Возвращаюсь в свой мир.";
		SkyCraft.LOG.info("SkyCraft network: {}", pendingNote);
		sessionJoin = null;
		readConfiguredJoin = false;
		attempted = false;
		NetworkClient.cancel();
		minecraft.gui.setScreen(new TitleScreen());
	}
}
