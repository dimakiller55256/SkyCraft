package dev.skycraft.combat;

import dev.skycraft.SkyCraft;
import dev.skycraft.link.Proto;
import dev.skycraft.link.SkyLink;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageSources;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobCategory;
import org.jspecify.annotations.Nullable;

/**
 * Combat between the Minecraft player and Skyrim actors, server side.
 *
 * <p>Every Skyrim actor near the player gets an invisible {@link SkyrimActorEntity} at its exact
 * position. Minecraft weapons hit those like any mob; the resulting damage is sent to Skyrim, which
 * applies it to the real actor (scaled by level) and makes it fight back. Skyrim's hits on the player
 * come back as Minecraft damage from the attacker's stand-in, so armor, shields, knockback, hurt
 * sounds and death all work the Minecraft way.
 */
public final class SkyCombat {
	public static final ResourceKey<EntityType<?>> SKYRIM_ACTOR_KEY =
		ResourceKey.create(Registries.ENTITY_TYPE, Identifier.fromNamespaceAndPath(SkyCraft.MOD_ID, "skyrim_actor"));
	public static final EntityType<SkyrimActorEntity> SKYRIM_ACTOR = Registry.register(
		BuiltInRegistries.ENTITY_TYPE,
		SKYRIM_ACTOR_KEY,
		EntityType.Builder.<SkyrimActorEntity>of(SkyrimActorEntity::new, MobCategory.MISC)
			.sized(0.6F, 1.8F)
			.noSave()
			.noSummon()
			.noLootTable()
			.clientTrackingRange(10)
			.updateInterval(1)
			.build(SKYRIM_ACTOR_KEY)
	);

	/** Skyrim damage is divided by this for Minecraft (a 15-damage bandit swing = 3 = 1.5 hearts). */
	public static final float SKYRIM_TO_MC_DAMAGE = 5.0F;

	private static final Map<java.util.UUID, Map<Integer, SkyrimActorEntity>> TABLES = new HashMap<>();
	private record GuestActors(List<SkyLink.Actor> actors,int tick) {}
	private static final Map<java.util.UUID, GuestActors> GUESTS = new HashMap<>();
	private static java.util.UUID hostOwner;
	private static final SkyLink.SkyState HOST_WORLD=new SkyLink.SkyState();
	public static void guestActors(ServerPlayer player,List<SkyLink.Actor> actors) {
		if(dev.skycraft.net.SkyNet.isHost(player)) return;
		int tick=player.level().getServer().getTickCount();
		var previous=GUESTS.get(player.getUUID());
		if(previous!=null && tick-previous.tick()<2) return;
		var valid=actors.stream().filter(a -> a.formId()!=0 && Float.isFinite(a.x()) && Float.isFinite(a.y()) && Float.isFinite(a.z())
			&& Float.isFinite(a.yaw()) && Float.isFinite(a.width()) && Float.isFinite(a.height()) && a.width()>=0.3f && a.width()<=6
			&& a.height()>=0.3f && a.height()<=12 && player.distanceToSqr(a.x(),a.y(),a.z())<=96*96).limit(Proto.MAX_ACTORS).toList();
		GUESTS.put(player.getUUID(),new GuestActors(valid,tick));
	}
	public static @Nullable SkyrimActorEntity proxy(ServerPlayer owner,int id) {
		return TABLES.getOrDefault(owner.getUUID(),Map.of()).get(id);
	}
	private static final List<SkyLink.Actor> ACTORS = new ArrayList<>();

	private SkyCombat() {
	}

	public static void init() {
		FabricDefaultAttributeRegistry.register(SKYRIM_ACTOR, LivingEntity.createLivingAttributes());
		ServerTickEvents.END_SERVER_TICK.register(SkyCombat::serverTick);
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STOPPED.register(server->{TABLES.clear();GUESTS.clear();hostOwner=null;});
	}

	public static @Nullable SkyrimActorEntity proxy(int formId) {
		return hostOwner==null ? null : TABLES.getOrDefault(hostOwner,Map.of()).get(formId);
	}

	private static void serverTick(MinecraftServer server) {
		List<ServerPlayer> players = server.getPlayerList().getPlayers();
		var owners=players.stream().map(ServerPlayer::getUUID).collect(java.util.stream.Collectors.toSet());
		TABLES.entrySet().removeIf(e->{if(owners.contains(e.getKey()))return false;e.getValue().values().forEach(Entity::discard);return true;});
		GUESTS.keySet().retainAll(owners);
		for (ServerPlayer player : players) {
			pickUpNearby(player);
			boolean host=dev.skycraft.net.SkyNet.isHost(player);
			if(host) {
				hostOwner=player.getUUID();
				if(SkyLink.active() && SkyLink.inputFresh() && SkyLink.readActors(ACTORS)) sync(player,ACTORS);
				// A paused host keeps its last stand-ins. Its heartbeat is not a disconnect.
			} else {
				var snapshot=GUESTS.get(player.getUUID());
				if(snapshot!=null && server.getTickCount()-snapshot.tick()<=60) sync(player,snapshot.actors());
				else sync(player,List.of());
			}
			if(server.getTickCount()%10==0 && dev.skycraft.net.SkyNet.isHost(player)) {
				boolean ready=SkyLink.readSkyState(HOST_WORLD) && HOST_WORLD.inGame() && !HOST_WORLD.loading();
				for(var guest:players) if(!dev.skycraft.net.SkyNet.isHost(guest) && net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.canSend(guest,dev.skycraft.net.ActorSync.WorldContext.TYPE))
					net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(guest,new dev.skycraft.net.ActorSync.WorldContext(HOST_WORLD.worldId,ready));
			}
			for(var proxy:TABLES.getOrDefault(player.getUUID(),Map.of()).values()) {
				float[] hit=proxy.takeHit();
				if(hit==null || !(hit[0]>0 || hit[3]>0)) continue;
				int flags=Float.floatToRawIntBits(hit[4]),weapon=Float.floatToRawIntBits(hit[5]);
				if(host) SkyLink.pushEvent(Proto.EV_HIT_ACTOR,proxy.formId(),hit[0],hit[1],hit[2],hit[3],flags,weapon);
				else if(net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.canSend(player,dev.skycraft.net.ActorSync.Hit.TYPE))
					net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(player,new dev.skycraft.net.ActorSync.Hit(proxy.formId(),hit[0],hit[1],hit[2],hit[3],flags,weapon));
				SkyCraft.LOG.info("SkyCraft: actor hit routed to {}: {}, damage {}",host?"host":"guest",Integer.toHexString(proxy.formId()),hit[0]);
			}
		}
	}

	private static void sync(ServerPlayer owner,List<SkyLink.Actor> actors) {
		ServerLevel level=owner.level();
		var PROXIES=TABLES.computeIfAbsent(owner.getUUID(),id->new HashMap<>());
		Map<Integer, SkyLink.Actor> live = new HashMap<>();
		for (SkyLink.Actor a : actors) {
			if (!a.dead()) {
				live.put(a.formId(), a);
			}
		}
		for (Iterator<Map.Entry<Integer, SkyrimActorEntity>> it = PROXIES.entrySet().iterator(); it.hasNext(); ) {
			Map.Entry<Integer, SkyrimActorEntity> e = it.next();
			SkyrimActorEntity proxy = e.getValue();
			if (!live.containsKey(e.getKey()) || proxy.isRemoved() || proxy.level() != level) {
				proxy.discard();
				it.remove();
			}
		}
		int before = PROXIES.size();
		for (SkyLink.Actor a : live.values()) {
			SkyrimActorEntity proxy = PROXIES.get(a.formId());
			if (proxy == null) {
				proxy = new SkyrimActorEntity(SKYRIM_ACTOR, level);
				proxy.setFormId(a.formId());
				proxy.setOwner(owner.getUUID());
				proxy.setSize(a.width(), a.height());
				proxy.snapTo(a.x(), a.y(), a.z(), a.yaw(), 0.0F);
				if (!a.name().isEmpty()) {
					proxy.setCustomName(Component.literal(a.name()));
				}
				if (!level.addFreshEntity(proxy)) {
					continue;
				}
				PROXIES.put(a.formId(), proxy);
				continue;
			}
			proxy.setSize(a.width(), a.height());
			proxy.setPos(a.x(), a.y(), a.z());
			proxy.setYRot(a.yaw());
			proxy.setYHeadRot(a.yaw());
			stepOnTriggers(level, proxy);
		}
		if (PROXIES.size() != before && (PROXIES.size() % 5 == 0 || PROXIES.size() < 5)) {
			SkyCraft.LOG.info("SkyCraft: {} Skyrim actors mirrored as hittable stand-ins", PROXIES.size());
		}
	}

	/**
	 * Skyrim's NPCs press pressure plates and trip tripwires. Their stand-ins are placed, not moved
	 * (no physics), so Minecraft never checks what they step into; do it for those blocks here.
	 */
	private static void stepOnTriggers(ServerLevel level, SkyrimActorEntity proxy) {
		var box = proxy.getBoundingBox().deflate(1.0E-5);
		var from = net.minecraft.core.BlockPos.containing(box.minX, box.minY, box.minZ);
		var to = net.minecraft.core.BlockPos.containing(box.maxX, box.maxY, box.maxZ);
		for (var pos : net.minecraft.core.BlockPos.betweenClosed(from, to)) {
			var state = level.getBlockState(pos);
			if (state.getBlock() instanceof net.minecraft.world.level.block.BasePressurePlateBlock
				|| state.getBlock() instanceof net.minecraft.world.level.block.TripWireBlock) {
				state.entityInside(level, pos, proxy, net.minecraft.world.entity.InsideBlockEffectApplier.NOOP, true);
			}
		}
	}

	/**
	 * Items and stuck arrows on Skyrim ground rest on its collision voxels, which on steep or rough
	 * terrain can sit a little off from where the player (on Skyrim's exact triangles) stands.
	 * Touch them over a slightly bigger area than vanilla's so walking over them picks them up.
	 * playerTouch applies all of Minecraft's own rules (pickup delay, owner, inventory space).
	 */
	private static void pickUpNearby(ServerPlayer player) {
		if (!player.isAlive() || player.isSpectator()) {
			return;
		}
		for (Entity entity : player.level().getEntities(player, player.getBoundingBox().inflate(1.25, 1.0, 1.25))) {
			if (!entity.isRemoved() && (entity instanceof net.minecraft.world.entity.item.ItemEntity
				|| entity instanceof net.minecraft.world.entity.projectile.arrow.AbstractArrow)) {
				entity.playerTouch(player);
			}
		}
	}


	/**
	 * Skyrim hit the player. Runs on the server thread. {@code kind} is a Proto.HURT_* value and
	 * {@code skyrimDamage} is what Skyrim would have taken off the player's health.
	 */
	public static void hurtPlayer(ServerPlayer player, int kind, float skyrimDamage, int attackerFormId, int flags) {
		hurtPlayer(player,kind,skyrimDamage,attackerFormId,flags,null);
	}

	public static void hurtPlayer(ServerPlayer player, int kind, float skyrimDamage, int attackerFormId, int flags, net.minecraft.world.phys.Vec3 origin) {
		if (!player.isAlive() || skyrimDamage <= 0.0F) {
			return;
		}
		ServerLevel level = player.level();
		SkyrimActorEntity attacker = proxy(player,attackerFormId);
		if (attacker != null && attacker.distanceToSqr(player) > 24.0 * 24.0) {
			attacker = null; // a guest's own NPC with the same form id as one of the host's
		}
		DamageSources sources = level.damageSources();
		DamageSource source = switch (kind) {
			case Proto.HURT_MELEE -> attacker != null ? sources.mobAttack(attacker) : new DamageSource(level.registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.DAMAGE_TYPE).getOrThrow(net.minecraft.world.damagesource.DamageTypes.MOB_ATTACK));
			case Proto.HURT_PROJECTILE -> attacker != null ? sources.mobProjectile(attacker, attacker) : new DamageSource(level.registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.DAMAGE_TYPE).getOrThrow(net.minecraft.world.damagesource.DamageTypes.MOB_PROJECTILE));
			case Proto.HURT_MAGIC -> attacker != null ? sources.indirectMagic(attacker, attacker) : sources.magic();
			default -> sources.generic();
		};
		if (origin!=null && origin.distanceToSqr(player.position())<=24*24) {
			// Preserve the guest's own attack direction so vanilla shields can test the front arc.
			source=new DamageSource(source.typeHolder(),origin);
		}
		float damage = DamageBalance.convert(kind, skyrimDamage, SKYRIM_TO_MC_DAMAGE, 12.0F);
		float healthBefore = player.getHealth();
		boolean blocking = player.isBlocking();
		boolean hurt = player.hurtServer(level, source, damage);
		trainDefence(player, damage, blocking && player.getHealth() >= healthBefore - 1.0E-3F);
		SkyCraft.LOG.info("SkyCraft: Skyrim hit the player for {} ({} Minecraft): health {} -> {}{}", skyrimDamage, damage, healthBefore, player.getHealth(),
			hurt ? "" : " (blocked/immune)");
		if (hurt && attacker != null && (flags & Proto.HURT_POWER_ATTACK) != 0 && !player.isBlocking()) {
			// Power attacks shove harder, like a sprint hit does in Minecraft.
			player.knockback(0.5, attacker.getX() - player.getX(), attacker.getZ() - player.getZ(), source, damage);
		}
	}

	/**
	 * Skyrim skills for taking a hit: Block when the shield caught it, otherwise Light or Heavy
	 * Armor by what the player mostly wears (leather, chainmail, gold, copper and turtle count as
	 * light; iron, diamond and netherite as heavy). Only the host's own Skyrim is told.
	 */
	private static void trainDefence(ServerPlayer player, float damage, boolean blocked) {
		if (!dev.skycraft.net.SkyNet.isHost(player) || damage <= 0.0F) {
			return;
		}
		if (blocked) {
			SkyLink.pushEvent(Proto.EV_SKILL_USE, Proto.SKILL_BLOCK, damage, 0.0F, 0.0F, 0.0F, 0);
			return;
		}
		int light = 0, heavy = 0;
		for (var slot : new net.minecraft.world.entity.EquipmentSlot[] { net.minecraft.world.entity.EquipmentSlot.HEAD, net.minecraft.world.entity.EquipmentSlot.CHEST,
			net.minecraft.world.entity.EquipmentSlot.LEGS, net.minecraft.world.entity.EquipmentSlot.FEET }) {
			var stack = player.getItemBySlot(slot);
			if (stack.isEmpty()) {
				continue;
			}
			String path = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
			if (path.startsWith("iron_") || path.startsWith("diamond_") || path.startsWith("netherite_")) {
				heavy++;
			} else {
				light++;
			}
		}
		if (light + heavy > 0) {
			SkyLink.pushEvent(Proto.EV_SKILL_USE, heavy > light ? Proto.SKILL_HEAVY_ARMOR : Proto.SKILL_LIGHT_ARMOR, damage * (light + heavy) / 4.0F, 0.0F, 0.0F,
				0.0F, 0);
		}
	}

	/** Form id of the Skyrim actor behind a damage source, or 0. */
	public static int attackerFormId(DamageSource source) {
		return source.getEntity() instanceof SkyrimActorEntity proxy ? proxy.formId() : 0;
	}
}
