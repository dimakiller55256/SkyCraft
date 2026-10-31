package dev.skycraft.world;

import static dev.skycraft.link.Proto.*;
import static java.lang.foreign.ValueLayout.*;

import dev.skycraft.SkyCraft;
import dev.skycraft.link.SkyLink;
import java.lang.foreign.MemorySegment;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.shapes.BitSetDiscreteVoxelShape;
import net.minecraft.world.phys.shapes.CubeVoxelShape;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jspecify.annotations.Nullable;

/**
 * Skyrim's world geometry as Minecraft sees it: an 8x8x8 sub-voxel collision shape per block
 * position, streamed from the SKSE plugin. These are not blocks; they are merged into block
 * collision queries (see BlockCollisionsMixin) so vanilla movement code collides with them.
 */
public final class SkyCollision {
	/** Skyrim regions are streamed as cubes of this many blocks. Must match the SKSE side. */
	public static final int REGION_SIZE = 8;

	private static final ConcurrentHashMap<Long, VoxelShape> SHAPES = new ConcurrentHashMap<>();
	// Per block: sub-voxel count (bits 0-9), any in the lower half (bit 10), any in the upper half (bit 11).
	private static final ConcurrentHashMap<Long, Integer> FILL = new ConcurrentHashMap<>();
	private static final int FILL_LOWER = 1 << 10;
	private static final int FILL_UPPER = 1 << 11;
	private static final int FILL_TOP_SHIFT = 12; // highest occupied of the 8 voxel layers (3 bits)
	private static final ConcurrentHashMap<Long, SkyTri[]> TRIS = new ConcurrentHashMap<>();
	// Diggable surfaces as they were before blocks were dug out of them (Proto.TRI_GHOST).
	private static final ConcurrentHashMap<Long, SkyTri[]> GHOSTS = new ConcurrentHashMap<>();
	// A hash of each region's triangles as last received, and the regions whose triangles changed
	// since the client last looked (the walls of dug holes are drawn from them).
	private static final ConcurrentHashMap<Long, Long> TRI_HASH = new ConcurrentHashMap<>();
	private static final java.util.concurrent.ConcurrentLinkedQueue<Long> CHANGED = new java.util.concurrent.ConcurrentLinkedQueue<>();

	/** Regions (min corner, as BlockPos longs) whose triangles changed since the last call. */
	public static void takeChangedRegions(java.util.function.LongConsumer out) {
		Long key;
		while ((key = CHANGED.poll()) != null) {
			out.accept(key);
		}
	}
	private static volatile java.util.function.Predicate<net.minecraft.world.entity.Entity> smoothCollider = e -> false;
	private static final Set<Long> KNOWN_REGIONS = ConcurrentHashMap.newKeySet();
	private static volatile int epoch = -1;
	// Skyrim sends its world's geometry in that world's coordinates; it is kept at its square of the
	// Minecraft map (SkyOrigin), so every query here is in map coordinates. Null: not placed yet.
	private static volatile SkyOrigin.@Nullable Origin stored;
	private static Thread consumer;

	private SkyCollision() {
	}

	public static @Nullable VoxelShape shapeAt(BlockPos pos) {
		return SHAPES.isEmpty() ? null : SHAPES.get(pos.asLong());
	}

	/** Entities (the local player) that collide with Skyrim's exact triangles instead of its voxels. */
	public static void setSmoothCollider(java.util.function.Predicate<net.minecraft.world.entity.Entity> predicate) {
		smoothCollider = predicate;
	}

	public static boolean usesSmoothCollider(net.minecraft.world.entity.@Nullable Entity entity) {
		return entity != null && smoothCollider.test(entity);
	}

	/** Adds every Skyrim triangle whose bounds overlap {@code box}. */
	public static void trianglesNear(net.minecraft.world.phys.AABB box, java.util.List<SkyTri> out) {
		if (TRIS.isEmpty()) {
			return;
		}
		int rx0 = Math.floorDiv((int) Math.floor(box.minX), REGION_SIZE), rx1 = Math.floorDiv((int) Math.floor(box.maxX), REGION_SIZE);
		int ry0 = Math.floorDiv((int) Math.floor(box.minY), REGION_SIZE), ry1 = Math.floorDiv((int) Math.floor(box.maxY), REGION_SIZE);
		int rz0 = Math.floorDiv((int) Math.floor(box.minZ), REGION_SIZE), rz1 = Math.floorDiv((int) Math.floor(box.maxZ), REGION_SIZE);
		for (int rx = rx0; rx <= rx1; rx++) {
			for (int ry = ry0; ry <= ry1; ry++) {
				for (int rz = rz0; rz <= rz1; rz++) {
					SkyTri[] tris = TRIS.get(regionKey(rx, ry, rz));
					if (tris == null) {
						continue;
					}
					for (SkyTri t : tris) {
						if (t.maxX >= box.minX && t.minX <= box.maxX && t.maxY >= box.minY && t.minY <= box.maxY && t.maxZ >= box.minZ && t.minZ <= box.maxZ) {
							out.add(t);
						}
					}
				}
			}
		}
	}

	/**
	 * Every Skyrim surface whose bounds overlap {@code box} as it was before anything was dug out
	 * of it: what's behind these is inside Skyrim's geometry (SkyDig).
	 */
	public static void originalSurfacesNear(net.minecraft.world.phys.AABB box, java.util.List<SkyTri> out) {
		trianglesNear(box, out);
		near(GHOSTS, box, out);
	}

	private static void near(ConcurrentHashMap<Long, SkyTri[]> store, net.minecraft.world.phys.AABB box, java.util.List<SkyTri> out) {
		if (store.isEmpty()) {
			return;
		}
		int rx0 = Math.floorDiv((int) Math.floor(box.minX), REGION_SIZE), rx1 = Math.floorDiv((int) Math.floor(box.maxX), REGION_SIZE);
		int ry0 = Math.floorDiv((int) Math.floor(box.minY), REGION_SIZE), ry1 = Math.floorDiv((int) Math.floor(box.maxY), REGION_SIZE);
		int rz0 = Math.floorDiv((int) Math.floor(box.minZ), REGION_SIZE), rz1 = Math.floorDiv((int) Math.floor(box.maxZ), REGION_SIZE);
		for (int rx = rx0; rx <= rx1; rx++) {
			for (int ry = ry0; ry <= ry1; ry++) {
				for (int rz = rz0; rz <= rz1; rz++) {
					SkyTri[] tris = store.get(regionKey(rx, ry, rz));
					if (tris == null) {
						continue;
					}
					for (SkyTri t : tris) {
						if (t.maxX >= box.minX && t.minX <= box.maxX && t.maxY >= box.minY && t.minY <= box.maxY && t.maxZ >= box.minZ && t.minZ <= box.maxZ) {
							out.add(t);
						}
					}
				}
			}
		}
	}

	/** True once Skyrim has sent the region containing this block (even if it was empty). */
	public static boolean isKnown(int x, int y, int z) {
		return KNOWN_REGIONS.contains(regionKey(Math.floorDiv(x, REGION_SIZE), Math.floorDiv(y, REGION_SIZE), Math.floorDiv(z, REGION_SIZE)));
	}
	public static boolean movementKnown(net.minecraft.world.phys.AABB box) {
		int before=epoch;
		boolean ready=CollisionCoverage.ready(box.minX,box.minY-1,box.minZ,box.maxX,box.maxY,box.maxZ,
			(x,y,z)->{long key=regionKey(x,y,z);return KNOWN_REGIONS.contains(key) && TRIS.containsKey(key);});
		return ready && before==epoch;
	}

	/** True if any Skyrim geometry exists in the 3x3 column below (x, y, z), down to {@code depth} blocks. */
	public static boolean hasSolidBelow(int x, int y, int z, int depth) {
		for (int dy = 0; dy <= depth; dy++) {
			for (int dx = -1; dx <= 1; dx++) {
				for (int dz = -1; dz <= 1; dz++) {
					if (SHAPES.containsKey(BlockPos.asLong(x + dx, y - dy, z + dz))) {
						return true;
					}
				}
			}
		}
		return false;
	}

	/** Fraction (0..1) of this block's volume that is Skyrim geometry. */
	public static float solidFraction(BlockPos pos) {
		Integer fill = FILL.isEmpty() ? null : FILL.get(pos.asLong());
		return fill == null ? 0.0F : (fill & 0x3FF) / 512.0F;
	}

	/** True if any Skyrim geometry is in this cell. */
	public static boolean hasGeometry(BlockPos pos) {
		return !FILL.isEmpty() && FILL.containsKey(pos.asLong());
	}

	/**
	 * How high (0..1) Skyrim geometry reaches in this cell: the top of its highest part. Terrain
	 * arrives as a thin surface, so what lies below that surface counts as ground too.
	 */
	public static float groundTop(BlockPos pos) {
		Integer fill = FILL.isEmpty() ? null : FILL.get(pos.asLong());
		return fill == null ? 0.0F : (((fill >> FILL_TOP_SHIFT) & 7) + 1) / 8.0F;
	}

	/** True if Skyrim ground holds up whatever is in this cell (terrain in its lower half or the top of the cell below). */
	public static boolean supportsFromBelow(BlockPos pos) {
		if (FILL.isEmpty()) {
			return false;
		}
		Integer here = FILL.get(pos.asLong());
		if (here != null && (here & FILL_LOWER) != 0) {
			return true;
		}
		Integer below = FILL.get(BlockPos.asLong(pos.getX(), pos.getY() - 1, pos.getZ()));
		return below != null && (below & FILL_UPPER) != 0;
	}

	public static int blockCount() {
		return SHAPES.size();
	}

	public static int regionCount() {
		return KNOWN_REGIONS.size();
	}

	/** Skyrim is describing its world around the player (false in a plain Minecraft world). */
	public static boolean active() {
		return !KNOWN_REGIONS.isEmpty();
	}

	private static long regionKey(int rx, int ry, int rz) {
		return BlockPos.asLong(rx, ry, rz);
	}

	public static synchronized void startConsumer() {
		if (consumer != null) {
			return;
		}
		consumer = new Thread(SkyCollision::consumeLoop, "SkyCraft collision");
		consumer.setDaemon(true);
		consumer.start();
	}

	private static void consumeLoop() {
		while (true) {
			try {
				if (!drainOnce()) {
					Thread.sleep(2);
				}
			} catch (InterruptedException e) {
				return;
			} catch (Throwable t) {
				SkyCraft.LOG.error("SkyCraft: collision consumer error", t);
				try {
					Thread.sleep(500);
				} catch (InterruptedException e) {
					return;
				}
			}
		}
	}

	/** Processes all pending collision messages. Returns true if anything was consumed. */
	private static boolean drainOnce() {
		MemorySegment s = SkyLink.segment();
		if (s == null) {
			return false;
		}
		followOrigin();
		long head = SkyLink.collisionHead();
		long tail = SkyLink.collisionTail();
		if (tail >= head) {
			return false;
		}
		long data = OFF_COLLISION_RING + CR_DATA;
		long start = tail;
		while (tail < head) {
			long pos = tail % CR_DATA_BYTES;
			int type = s.get(JAVA_INT, data + pos);
			int payloadBytes = s.get(JAVA_INT, data + pos + 4);
			if (type == COL_PAD) {
				tail += CR_DATA_BYTES - pos;
				continue;
			}
			long payload = data + pos + 8;
			int msgEpoch = s.get(JAVA_INT, type == COL_CLEAR ? payload : payload + 24);
			// A new epoch's geometry goes into its world's square of the map: wait until it's placed.
			if ((type == COL_CLEAR || epoch == -1) && !place(msgEpoch)) {
				break;
			}
			switch (type) {
				case COL_CLEAR -> clear(msgEpoch);
				case COL_REGION -> readRegion(s, payload);
				case COL_TRIS -> readTris(s, payload);
				default -> SkyCraft.LOG.warn("SkyCraft: unknown collision message {}", type);
			}
			tail += align8(8 + payloadBytes);
		}
		SkyLink.setCollisionTail(tail);
		return tail != start;
	}

	/**
	 * Finds the world a new collision epoch belongs to (Skyrim states both each frame) and its square
	 * of the map. False: not yet (Skyrim hasn't published the epoch, or the server hasn't answered).
	 */
	private static boolean place(int msgEpoch) {
		SkyLink.SkyState sky = new SkyLink.SkyState();
		if (!SkyLink.readSkyState(sky)) {
			return false;
		}
		if (Integer.compareUnsigned(sky.collisionEpoch, msgEpoch) < 0) {
			return false; // Skyrim's worker wrote it before the frame that announces it
		}
		if (sky.collisionEpoch != msgEpoch) {
			stored = null; // superseded already: its geometry is dropped, the newer epoch follows
			return true;
		}
		SkyOrigin.Origin origin = SkyOrigin.of(sky.worldId);
		if (origin == null) {
			return false;
		}
		stored = origin;
		return true;
	}

	/** The server moved this world's square (another server was joined): move what is held with it. */
	private static void followOrigin() {
		SkyOrigin.Origin held = stored;
		SkyOrigin.Origin now = held != null ? SkyOrigin.of(held.worldId()) : null;
		if (held == null || now == null || (now.x() == held.x() && now.z() == held.z())) {
			return;
		}
		int dx = now.x() - held.x(), dz = now.z() - held.z();
		int rdx = Math.floorDiv(dx, REGION_SIZE), rdz = Math.floorDiv(dz, REGION_SIZE);
		moveKeys(SHAPES, dx, dz);
		moveKeys(FILL, dx, dz);
		moveKeys(TRI_HASH, rdx, rdz);
		java.util.Map<Long, SkyTri[]> tris = new java.util.HashMap<>(TRIS), ghosts = new java.util.HashMap<>(GHOSTS);
		TRIS.clear();
		GHOSTS.clear();
		tris.forEach((k, v) -> TRIS.put(moved(k, rdx, rdz), shiftAll(v, dx, dz)));
		ghosts.forEach((k, v) -> GHOSTS.put(moved(k, rdx, rdz), shiftAll(v, dx, dz)));
		java.util.List<Long> regions = new java.util.ArrayList<>(KNOWN_REGIONS);
		KNOWN_REGIONS.clear();
		CHANGED.clear();
		for (long r : regions) {
			long key = moved(r, rdx, rdz);
			KNOWN_REGIONS.add(key);
			CHANGED.add(BlockPos.asLong(BlockPos.getX(key) * REGION_SIZE, BlockPos.getY(key) * REGION_SIZE, BlockPos.getZ(key) * REGION_SIZE));
		}
		stored = now;
		SkyCraft.LOG.info("SkyCraft: Skyrim's geometry moved with its world's square to {} {}", now.x(), now.z());
	}

	private static long moved(long key, int dx, int dz) {
		return BlockPos.asLong(BlockPos.getX(key) + dx, BlockPos.getY(key), BlockPos.getZ(key) + dz);
	}

	private static <V> void moveKeys(ConcurrentHashMap<Long, V> map, int dx, int dz) {
		java.util.Map<Long, V> copy = new java.util.HashMap<>(map);
		map.clear();
		copy.forEach((k, v) -> map.put(moved(k, dx, dz), v));
	}

	private static SkyTri[] shiftAll(SkyTri[] tris, double dx, double dz) {
		SkyTri[] out = new SkyTri[tris.length];
		for (int i = 0; i < tris.length; i++) {
			out[i] = tris[i].shifted(dx, dz);
		}
		return out;
	}

	private static long align8(long v) {
		return (v + 7) & ~7L;
	}

	/** A freshly started client joins whatever collision epoch Skyrim is already on. */
	private static void adoptEpochIfFresh(int msgEpoch) {
		if (epoch == -1) {
			epoch = msgEpoch;
			SkyCraft.LOG.info("SkyCraft: joined collision epoch {} already in progress", msgEpoch);
		}
	}

	private static void clear(int newEpoch) {
		SHAPES.clear();
		FILL.clear();
		TRIS.clear();
		GHOSTS.clear();
		TRI_HASH.clear();
		KNOWN_REGIONS.clear();
		epoch = newEpoch;
		SkyCraft.LOG.info("SkyCraft: collision cleared (epoch {})", newEpoch);
	}

	private static void readRegion(MemorySegment s, long p) {
		SkyOrigin.Origin origin = stored;
		int ox = origin != null ? origin.x() : 0, oz = origin != null ? origin.z() : 0;
		int minX = s.get(JAVA_INT, p) + ox;
		int minY = s.get(JAVA_INT, p + 4);
		int minZ = s.get(JAVA_INT, p + 8) + oz;
		int maxX = s.get(JAVA_INT, p + 12) + ox;
		int maxY = s.get(JAVA_INT, p + 16);
		int maxZ = s.get(JAVA_INT, p + 20) + oz;
		int msgEpoch = s.get(JAVA_INT, p + 24);
		int count = s.get(JAVA_INT, p + 28);
		adoptEpochIfFresh(msgEpoch);
		if (msgEpoch != epoch || origin == null) {
			return; // stale region from before a world change
		}

		// Build the new shapes first so readers never see a half-empty region.
		java.util.HashMap<Long, VoxelShape> fresh = new java.util.HashMap<>(count * 2);
		java.util.HashMap<Long, Integer> freshFill = new java.util.HashMap<>(count * 2);
		long e = p + COL_REGION_HEADER_BYTES;
		for (int i = 0; i < count; i++, e += COL_BLOCK_BYTES) {
			int x = s.get(JAVA_INT, e) + ox;
			int y = s.get(JAVA_INT, e + 4);
			int z = s.get(JAVA_INT, e + 8) + oz;
			VoxelShape shape = buildShape(s, e + 16);
			if (shape != null) {
				long key = BlockPos.asLong(x, y, z);
				fresh.put(key, shape);
				freshFill.put(key, fillInfo(s, e + 16));
			}
		}

		for (int x = minX; x <= maxX; x++) {
			for (int y = minY; y <= maxY; y++) {
				for (int z = minZ; z <= maxZ; z++) {
					long key = BlockPos.asLong(x, y, z);
					VoxelShape shape = fresh.get(key);
					if (shape != null) {
						SHAPES.put(key, shape);
						FILL.put(key, freshFill.get(key));
					} else {
						SHAPES.remove(key);
						FILL.remove(key);
					}
				}
			}
		}

		for (int rx = Math.floorDiv(minX, REGION_SIZE); rx <= Math.floorDiv(maxX, REGION_SIZE); rx++) {
			for (int ry = Math.floorDiv(minY, REGION_SIZE); ry <= Math.floorDiv(maxY, REGION_SIZE); ry++) {
				for (int rz = Math.floorDiv(minZ, REGION_SIZE); rz <= Math.floorDiv(maxZ, REGION_SIZE); rz++) {
					KNOWN_REGIONS.add(regionKey(rx, ry, rz));
				}
			}
		}
	}

	private static void readTris(MemorySegment s, long p) {
		SkyOrigin.Origin origin = stored;
		int ox = origin != null ? origin.x() : 0, oz = origin != null ? origin.z() : 0;
		int minX = s.get(JAVA_INT, p) + ox;
		int minY = s.get(JAVA_INT, p + 4);
		int minZ = s.get(JAVA_INT, p + 8) + oz;
		int msgEpoch = s.get(JAVA_INT, p + 24);
		int count = s.get(JAVA_INT, p + 28);
		adoptEpochIfFresh(msgEpoch);
		if (msgEpoch != epoch || origin == null) {
			return;
		}
		SkyTri[] tris = new SkyTri[count];
		java.util.List<SkyTri> ghosts = new java.util.ArrayList<>();
		float[] v = new float[9];
		int kept = 0;
		long hash = count;
		long e = p + COL_REGION_HEADER_BYTES;
		for (int i = 0; i < count; i++, e += COL_TRI_BYTES) {
			for (int k = 0; k < 9; k++) {
				v[k] = s.get(JAVA_FLOAT, e + k * 4L);
				hash = hash * 31 + Float.floatToRawIntBits(v[k]);
			}
			int flags = s.get(JAVA_INT, e + 36);
			hash = hash * 31 + flags;
			SkyTri t = new SkyTri(v, 0, flags, ox, oz);
			if (t.degenerate()) {
				continue;
			}
			if ((flags & TRI_GHOST) != 0) {
				ghosts.add(t);
			} else {
				tris[kept++] = t;
			}
		}
		long region = regionKey(Math.floorDiv(minX, REGION_SIZE), Math.floorDiv(minY, REGION_SIZE), Math.floorDiv(minZ, REGION_SIZE));
		if (ghosts.isEmpty()) {
			GHOSTS.remove(region);
		} else {
			GHOSTS.put(region, ghosts.toArray(new SkyTri[0]));
		}
		TRIS.put(region, java.util.Arrays.copyOf(tris, kept));
		Long before = TRI_HASH.put(region, hash);
		if (before == null || before != hash) {
			CHANGED.add(BlockPos.asLong(minX, minY, minZ));
		}
	}

	public static int triangleCount() {
		int n = 0;
		for (SkyTri[] t : TRIS.values()) {
			n += t.length;
		}
		return n;
	}

	private static int fillInfo(MemorySegment s, long bitsOff) {
		int count = 0;
		int info = 0;
		int top = 0;
		for (int y = 0; y < 8; y++) {
			long layer = s.get(JAVA_LONG, bitsOff + y * 8L);
			count += Long.bitCount(layer);
			if (layer != 0) {
				info |= y < 4 ? FILL_LOWER : FILL_UPPER;
				top = y;
			}
		}
		return info | count | top << FILL_TOP_SHIFT;
	}

	private static @Nullable VoxelShape buildShape(MemorySegment s, long bitsOff) {
		boolean any = false;
		boolean full = true;
		long[] layers = new long[8];
		for (int y = 0; y < 8; y++) {
			layers[y] = s.get(JAVA_LONG, bitsOff + y * 8L);
			any |= layers[y] != 0;
			full &= layers[y] == -1L;
		}
		if (!any) {
			return null;
		}
		if (full) {
			return Shapes.block();
		}
		BitSetDiscreteVoxelShape discrete = new BitSetDiscreteVoxelShape(8, 8, 8);
		for (int y = 0; y < 8; y++) {
			long layer = layers[y];
			while (layer != 0) {
				int bit = Long.numberOfTrailingZeros(layer);
				layer &= layer - 1;
				discrete.fill(bit & 7, y, bit >>> 3);
			}
		}
		return new CubeVoxelShape(discrete);
	}
}
