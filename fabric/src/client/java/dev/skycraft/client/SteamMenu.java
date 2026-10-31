package dev.skycraft.client;

import dev.skycraft.network.HostAuthentication;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** "SkyCraft: Steam": play with Steam friends through SkyCraft Link, without Radmin or open ports. */
public final class SteamMenu extends Screen {
	private static final int FRIEND_ROWS = 4;
	private final Screen parent;
	private boolean online = true;
	private int seen = -1;

	public SteamMenu(Screen parent) {
		super(Component.literal("SkyCraft: Steam"));
		this.parent = parent;
	}

	@Override protected void init() {
		seen = SteamLink.version();
		int w = Math.min(340, width - 20), x = (width - w) / 2;
		var server = minecraft.getSingleplayerServer();
		boolean hosting = SteamLink.role() == SteamLink.Role.HOST && SteamLink.running();
		boolean visiting = SteamLink.role() == SteamLink.Role.GUEST && SteamLink.running();  // a new search would cut us off
		Button host = Button.builder(Component.literal(hosting ? "Мир открыт друзьям в Steam" : "Открыть свой мир друзьям в Steam"), b -> openWorld())
			.bounds(x, 50, w - 124, 20).build();
		host.active = server != null && !hosting;
		addRenderableWidget(host);
		Button auth = Button.builder(authLabel(), b -> { online = !online; b.setMessage(authLabel()); }).bounds(x + w - 120, 50, 120, 20).build();
		auth.active = server != null && !server.isPublished();
		addRenderableWidget(auth);
		Button find = Button.builder(Component.literal("Найти друзей с открытым миром"), b -> SteamLink.findFriends(minecraft)).bounds(x, 74, w, 20).build();
		find.active = !hosting && !visiting;
		addRenderableWidget(find);
		if (!hosting && !visiting) {
			int y = 98;
			for (var friend : SteamLink.friends().subList(0, Math.min(FRIEND_ROWS, SteamLink.friends().size()))) {
				String name = friend.name().isEmpty() ? friend.id() : friend.name();
				addRenderableWidget(Button.builder(Component.literal("Войти к " + name), b -> SteamLink.join(minecraft, friend.id())).bounds(x, y, w, 20).build());
				y += 24;
			}
		}
		Button stop = Button.builder(Component.literal("Остановить Steam"), b -> SteamLink.stop()).bounds(x, height - 25, w / 2 - 2, 20).build();
		stop.active = SteamLink.running();
		addRenderableWidget(stop);
		addRenderableWidget(Button.builder(Component.literal("Назад"), b -> onClose()).bounds(x + w / 2 + 2, height - 25, w / 2 - 2, 20).build());
	}

	/** Opens the world to LAN first if needed (as "Open my world" does), then to friends in Steam. */
	private void openWorld() {
		var server = minecraft.getSingleplayerServer();
		if (server == null) return;
		if (!server.isPublished()) NetworkClient.host(minecraft, null, online ? HostAuthentication.ONLINE : HostAuthentication.OFFLINE);
		if (server.isPublished()) SteamLink.host(minecraft, server.getPort());
	}

	private Component authLabel() { return Component.literal(online ? "Сессия: ONLINE" : "Сессия: OFFLINE"); }

	@Override public void tick() {
		if (seen != SteamLink.version()) rebuildWidgets();  // the link reported something new
	}

	@Override public void onClose() { minecraft.gui.setScreen(parent); }

	@Override public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
		super.extractRenderState(g, mouseX, mouseY, delta);
		g.centeredText(font, title, width / 2, 20, 0xFFFFFFFF);
		g.centeredText(font, Component.literal("Через Steam (Spacewar): без Radmin и открытых портов, только друзья"), width / 2, 34, 0xFFAAAAAA);
		int line = 102;
		if (SteamLink.role() == SteamLink.Role.HOST && SteamLink.running()) {
			var guests = SteamLink.guests();
			g.centeredText(font, Component.literal(guests.isEmpty() ? "Пока никто не подключился." : "Подключены: " + String.join(", ", guests)), width / 2, line, 0xFFFFFFFF);
			g.centeredText(font, Component.literal("Skyrim Together: запустите у себя его сервер, порт " + SteamLink.TOGETHER_PORT + "."), width / 2, line + 14, 0xFFAAAAAA);
		} else if (SteamLink.role() == SteamLink.Role.GUEST && SteamLink.running()) {
			g.centeredText(font, Component.literal("Вы в мире друга через Steam. «Остановить Steam» вернёт вас в свой мир."), width / 2, line, 0xFFFFFFFF);
			if (SteamLink.togetherPort() > 0) {
				g.centeredText(font, Component.literal("Skyrim Together: сервер 127.0.0.1:" + SteamLink.togetherPort()), width / 2, line + 14, 0xFFAAAAAA);
			}
		} else if (SteamLink.friendsKnown() && SteamLink.friends().size() > FRIEND_ROWS) {
			g.centeredText(font, Component.literal("И ещё " + (SteamLink.friends().size() - FRIEND_ROWS) + ": закройте лишние миры у друзей."), width / 2, 98 + FRIEND_ROWS * 24, 0xFFAAAAAA);
		}
		g.centeredText(font, font.plainSubstrByWidth(SteamLink.status(), width - 20), width / 2, height - 42, 0xFFFFFFFF);
	}
}
