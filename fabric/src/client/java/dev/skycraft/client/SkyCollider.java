package dev.skycraft.client;

import dev.skycraft.world.SkyCollision;
import dev.skycraft.world.SkyTri;
import dev.skycraft.world.TriCollider;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Feeds the local player's movement through {@link TriCollider} against nearby Skyrim triangles. */
public final class SkyCollider {
	private static long nextDiagnostic;
	private SkyCollider() {
	}

	public static Vec3 collide(LocalPlayer player, Vec3 move) {
		AABB box = player.getBoundingBox();
		if (SkyClient.sky().takingOver()) { player.resetFallDistance(); return Vec3.ZERO; }
		if (SkyClient.sky().inGame() && !SkyCollision.movementKnown(box.expandTowards(move).inflate(0.02))) {
			diagnose(player,"collision_not_ready",move,Double.NaN);
			return Vec3.ZERO;
		}
		double step = player.maxUpStep();
		if(move.y<0 && player.fallDistance>3)diagnose(player,"fall_probe",move,Double.NaN);
		List<SkyTri> tris = new ArrayList<>();
		SkyCollision.trianglesNear(box.expandTowards(move).inflate(1.0, 1.0 + step, 1.0), tris);
		if (tris.isEmpty()) {
			return move;
		}
		double x=(box.minX+box.maxX)*0.5,z=(box.minZ+box.maxZ)*0.5;
		double recovery=move.y<=0 && player.fallDistance<0.75 ? TriCollider.terrainAboveFeet(tris,x,box.minY,z,Math.min(step,0.6)) : Double.NaN;
		double lift=Double.isFinite(recovery) ? recovery-box.minY : 0;
		if(lift>0)diagnose(player,"terrain_penetration_repaired",move,recovery);
		double[] r = TriCollider.resolve(
			tris, x, box.minY+lift, z, box.getXsize() * 0.5, box.getYsize(), step, player.onGround()||lift>0,
			move.x, move.y, move.z
		);
		r[1]+=lift;
		if (r[0] == move.x && r[1] == move.y && r[2] == move.z) {
			return move;
		}
		// The triangle pass (snapping down a slope, pushing out of a wall) can move the player into a
		// Minecraft block placed on the terrain; collide that result with Minecraft blocks again.
		return Entity.collideBoundingBox(player, new Vec3(r[0], r[1], r[2]), box, player.level(), List.of());
	}
	private static void diagnose(LocalPlayer player,String reason,Vec3 move,double surface) {
		long now=System.nanoTime();if(now<nextDiagnostic)return;nextDiagnostic=now+1_000_000_000L;
		var data=new java.util.LinkedHashMap<String,Object>();
		data.put("reason",reason);data.put("world",SkyClient.sky().worldId);data.put("epoch",SkyClient.sky().collisionEpoch);
		data.put("x",player.getX());data.put("y",player.getY());data.put("z",player.getZ());
		data.put("dy",move.y);data.put("on_ground",player.onGround());data.put("fall_distance",player.fallDistance);
		data.put("known_regions",SkyCollision.regionCount());data.put("triangles",SkyCollision.triangleCount());
		var pos=net.minecraft.core.BlockPos.containing(player.getX(),player.getY()-.1,player.getZ());
		data.put("feet_known",SkyCollision.isKnown(pos.getX(),pos.getY(),pos.getZ()));
		data.put("below_known",SkyCollision.isKnown(pos.getX(),pos.getY()-1,pos.getZ()));
		data.put("dug_at_feet",dev.skycraft.world.SkyDig.isDug(player.level(),SkyClient.sky().worldId,pos));
		List<SkyTri> nearby=new ArrayList<>();SkyCollision.trianglesNear(player.getBoundingBox().inflate(1,4,1),nearby);
		data.put("nearby_triangles",nearby.size());
		data.put("geometry",nearby.stream().limit(32).map(t->new double[]{t.ax,t.ay,t.az,t.bx,t.by,t.bz,t.cx,t.cy,t.cz,t.terrain?1:0}).toList());
		if(Double.isFinite(surface))data.put("surface",surface);
		NetworkDiagnostics.event("collision_guard",data);
		dev.skycraft.SkyCraft.LOG.info("SkyCraft: collision guard {} at {} {} {}, dy {}, fall {}, regions {}",reason,player.getX(),player.getY(),player.getZ(),move.y,player.fallDistance,SkyCollision.regionCount());
	}

	/** Highest Skyrim surface at or below {@code maxAbove} over the feet at (x, y, z), or NaN. */
	public static double groundAt(double x, double y, double z, double maxAbove) {
		List<SkyTri> tris = new ArrayList<>();
		SkyCollision.trianglesNear(new AABB(x - 1, y - 4, z - 1, x + 1, y + maxAbove + 1, z + 1), tris);
		return TriCollider.groundAt(tris, x, y, z, maxAbove);
	}
}
