package com.aerodynamics4mc.compat.createaeronautics;

import com.aerodynamics4mc.api.A4mcId;
import com.aerodynamics4mc.api.A4mcVec3;
import com.aerodynamics4mc.api.AeroAirfoilProfile;
import com.aerodynamics4mc.api.AeroSurfaceDescriptor;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class CreateAeronauticsWingScanner {
	private static final Comparator<BlockPos> BLOCK_POS_ORDER = Comparator
			.<BlockPos>comparingInt(pos -> pos.getX())
			.thenComparingInt(pos -> pos.getY())
			.thenComparingInt(pos -> pos.getZ());

	public WingScanResult scan(Object serverSubLevel) {
		Objects.requireNonNull(serverSubLevel, "serverSubLevel");

		Object plot = invoke(serverSubLevel, "getPlot");
		Object plotBounds = invoke(plot, "getBoundingBox");
		BlockBounds bounds = BlockBounds.fromBoundingBox(plotBounds);
		if (bounds.isEmpty()) {
			return new WingScanResult(List.of(), null, bounds);
		}

		Map<BlockPos, WingBlockInfo> wingBlocks = new HashMap<>();
		for (Object holder : loadedChunkHolders(plot)) {
			Object chunkObject = invoke(holder, "getChunk");
			if (chunkObject instanceof LevelChunk chunk) {
				scanChunk(chunk, bounds, wingBlocks);
			}
		}

		List<WingGroup> groups = buildGroups(wingBlocks);
		WingGroup selected = groups.stream()
				.max(Comparator.comparingInt(WingGroup::blockCount)
						.thenComparingDouble(group -> group.surface().areaSquareMeters()))
				.orElse(null);
		return new WingScanResult(groups, selected, bounds);
	}

	public static String keywordSummary() {
		return CreateAeronauticsCompatBlocks.supportedWingBlockSummary();
	}

	private static void scanChunk(LevelChunk chunk, BlockBounds bounds, Map<BlockPos, WingBlockInfo> wingBlocks) {
		ChunkPos chunkPos = chunk.getPos();
		int minX = Math.max(bounds.minX(), chunkPos.getMinBlockX());
		int maxX = Math.min(bounds.maxX(), chunkPos.getMaxBlockX());
		int minZ = Math.max(bounds.minZ(), chunkPos.getMinBlockZ());
		int maxZ = Math.min(bounds.maxZ(), chunkPos.getMaxBlockZ());
		if (minX > maxX || minZ > maxZ) {
			return;
		}

		BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
		for (int x = minX; x <= maxX; x++) {
			for (int z = minZ; z <= maxZ; z++) {
				for (int y = bounds.minY(); y <= bounds.maxY(); y++) {
					cursor.set(x, y, z);
					BlockState state = chunk.getBlockState(cursor);
					if (state.isAir()) {
						continue;
					}
					String blockId = blockId(state);
					WingBlockInfo info = wingBlockInfo(chunk, cursor, state, blockId);
					if (info != null) {
						wingBlocks.put(cursor.immutable(), info);
					}
				}
			}
		}
	}

	private static String blockId(BlockState state) {
		Identifier id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
		return id == null ? "unknown:unknown" : id.toString();
	}

	private static WingBlockInfo wingBlockInfo(LevelChunk chunk, BlockPos pos, BlockState state, String blockId) {
		if (!CreateAeronauticsCompatBlocks.isA4mcWingBlockId(blockId)) {
			return null;
		}
		AeroAirfoilProfile profile = airfoilProfile(chunk, pos);
		return new WingBlockInfo(
				blockId,
				profile,
				directionVector(AirfoilWingBlock.chordDirection(state)),
				directionVector(AirfoilWingBlock.spanDirection(state)),
				directionVector(AirfoilWingBlock.normalDirection(state))
		);
	}

	private static AeroAirfoilProfile airfoilProfile(LevelChunk chunk, BlockPos pos) {
		BlockEntity blockEntity = chunk.getBlockEntity(pos);
		if (blockEntity instanceof AirfoilWingBlockEntity wing) {
			return CreateAeronauticsAirfoilLibrary.profileOrSelected(wing.airfoilId());
		}
		return CreateAeronauticsAirfoilLibrary.selectedProfile();
	}

	private static A4mcVec3 directionVector(Direction direction) {
		return switch (direction) {
			case DOWN -> A4mcVec3.of(0.0, -1.0, 0.0);
			case UP -> A4mcVec3.of(0.0, 1.0, 0.0);
			case NORTH -> A4mcVec3.of(0.0, 0.0, -1.0);
			case SOUTH -> A4mcVec3.of(0.0, 0.0, 1.0);
			case WEST -> A4mcVec3.of(-1.0, 0.0, 0.0);
			case EAST -> A4mcVec3.of(1.0, 0.0, 0.0);
		};
	}

	private static List<WingGroup> buildGroups(Map<BlockPos, WingBlockInfo> wingBlocks) {
		Set<BlockPos> unvisited = new HashSet<>(wingBlocks.keySet());
		List<WingGroup> groups = new ArrayList<>();
		while (!unvisited.isEmpty()) {
			BlockPos seed = unvisited.iterator().next();
			unvisited.remove(seed);

			WingBlockInfo seedInfo = wingBlocks.get(seed);
			List<BlockPos> blocks = new ArrayList<>();
			ArrayDeque<BlockPos> queue = new ArrayDeque<>();
			queue.add(seed);
			while (!queue.isEmpty()) {
				BlockPos current = queue.removeFirst();
				blocks.add(current);
				for (Direction direction : Direction.values()) {
					BlockPos neighbor = current.relative(direction);
					if (compatibleWingBlocks(seedInfo, wingBlocks.get(neighbor)) && unvisited.remove(neighbor)) {
						queue.add(neighbor);
					}
				}
			}

			groups.add(toWingGroup(blocks, wingBlocks));
		}
		groups.sort(Comparator.comparingInt(WingGroup::blockCount).reversed()
				.thenComparing(group -> group.bounds().minX())
				.thenComparing(group -> group.bounds().minY())
				.thenComparing(group -> group.bounds().minZ())
				.thenComparing(group -> group.surface().shapeHash()));
		List<WingGroup> indexedGroups = new ArrayList<>(groups.size());
		for (int i = 0; i < groups.size(); i++) {
			indexedGroups.add(groups.get(i).withIndex(i));
		}
		return List.copyOf(indexedGroups);
	}

	private static boolean compatibleWingBlocks(WingBlockInfo a, WingBlockInfo b) {
		return a != null
				&& b != null
				&& Objects.equals(a.blockId(), b.blockId())
				&& Objects.equals(a.profile().id(), b.profile().id())
				&& Objects.equals(vectorKey(a.chordDirection()), vectorKey(b.chordDirection()))
				&& Objects.equals(vectorKey(a.spanDirection()), vectorKey(b.spanDirection()))
				&& Objects.equals(vectorKey(a.normalDirection()), vectorKey(b.normalDirection()));
	}

	private static WingGroup toWingGroup(List<BlockPos> blocks, Map<BlockPos, WingBlockInfo> wingBlocks) {
		BlockBounds bounds = BlockBounds.fromPositions(blocks);
		WingBlockInfo orientation = chooseOrientation(blocks, wingBlocks);
		double spanMeters = Math.max(1.0, extentAlong(blocks, orientation.spanDirection()));
		double chordMeters = Math.max(1.0, extentAlong(blocks, orientation.chordDirection()));
		double areaMeters = Math.max(1.0, blocks.size());
		AeroAirfoilProfile profile = orientation.profile();
		String shapeHash = shapeHash(blocks, wingBlocks, orientation);
		AeroSurfaceDescriptor surface = new AeroSurfaceDescriptor(
				A4mcId.of("aerodynamics4mc", "create_aeronautics/wing_" + shapeHash),
				shapeHash,
				profile,
				spanMeters,
				chordMeters,
				areaMeters,
				0.0,
				A4mcVec3.of(bounds.centerX(), bounds.centerY(), bounds.centerZ()),
				orientation.chordDirection(),
				orientation.spanDirection(),
				orientation.normalDirection()
		);
		return new WingGroup(-1, surface, blocks.size(), bounds);
	}

	private static WingBlockInfo chooseOrientation(List<BlockPos> blocks, Map<BlockPos, WingBlockInfo> wingBlocks) {
		Map<WingBlockInfo, Integer> counts = new HashMap<>();
		for (BlockPos block : blocks) {
			WingBlockInfo info = wingBlocks.get(block);
			if (info != null) {
				counts.merge(info, 1, Integer::sum);
			}
		}
		return counts.entrySet().stream()
				.max(Map.Entry.comparingByValue())
				.map(Map.Entry::getKey)
				.orElseGet(() -> new WingBlockInfo(
						CreateAeronauticsCompatBlocks.AIRFOIL_WING_ID,
						CreateAeronauticsAirfoilLibrary.selectedProfile(),
						A4mcVec3.of(0.0, 0.0, -1.0),
						A4mcVec3.of(1.0, 0.0, 0.0),
						A4mcVec3.of(0.0, 1.0, 0.0)
				));
	}

	private static double extentAlong(List<BlockPos> blocks, A4mcVec3 direction) {
		double min = Double.POSITIVE_INFINITY;
		double max = Double.NEGATIVE_INFINITY;
		for (BlockPos block : blocks) {
			double projection = (block.getX() + 0.5) * direction.x()
					+ (block.getY() + 0.5) * direction.y()
					+ (block.getZ() + 0.5) * direction.z();
			min = Math.min(min, projection);
			max = Math.max(max, projection);
		}
		if (!Double.isFinite(min) || !Double.isFinite(max)) {
			return 1.0;
		}
		return max - min + 1.0;
	}

	private static String shapeHash(List<BlockPos> blocks, Map<BlockPos, WingBlockInfo> wingBlocks, WingBlockInfo orientation) {
		List<BlockPos> sorted = new ArrayList<>(blocks);
		sorted.sort(BLOCK_POS_ORDER);
		MessageDigest digest = sha256();
		update(digest, orientation.profile().id().toString());
		update(digest, vectorKey(orientation.chordDirection()));
		update(digest, vectorKey(orientation.spanDirection()));
		update(digest, vectorKey(orientation.normalDirection()));
		for (BlockPos block : sorted) {
			WingBlockInfo info = wingBlocks.get(block);
			update(digest, block.getX() + "," + block.getY() + "," + block.getZ() + ":" + info.blockId()
					+ ":" + vectorKey(info.chordDirection())
					+ ":" + vectorKey(info.spanDirection())
					+ ":" + vectorKey(info.normalDirection()));
		}
		byte[] bytes = digest.digest();
		StringBuilder builder = new StringBuilder(16);
		for (int i = 0; i < 8; i++) {
			int value = bytes[i] & 0xff;
			if (value < 16) {
				builder.append('0');
			}
			builder.append(Integer.toHexString(value));
		}
		return builder.toString();
	}

	private static String vectorKey(A4mcVec3 vector) {
		return vector.x() + "," + vector.y() + "," + vector.z();
	}

	private static MessageDigest sha256() {
		try {
			return MessageDigest.getInstance("SHA-256");
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 digest is unavailable", e);
		}
	}

	private static void update(MessageDigest digest, String value) {
		digest.update(value.getBytes(StandardCharsets.UTF_8));
		digest.update((byte) '\n');
	}

	@SuppressWarnings("unchecked")
	private static Collection<Object> loadedChunkHolders(Object plot) {
		Object loadedChunks = invoke(plot, "getLoadedChunks");
		if (loadedChunks instanceof Collection<?> collection) {
			return (Collection<Object>) collection;
		}
		throw new IllegalStateException("LevelPlot.getLoadedChunks did not return a Collection");
	}

	private static Object invoke(Object target, String methodName) {
		Objects.requireNonNull(target, methodName + " target");
		try {
			Method method = target.getClass().getMethod(methodName);
			return method.invoke(target);
		} catch (NoSuchMethodException e) {
			throw new IllegalStateException(target.getClass().getName() + " has no method " + methodName + "()", e);
		} catch (IllegalAccessException e) {
			throw new IllegalStateException("Cannot access " + target.getClass().getName() + "." + methodName + "()", e);
		} catch (InvocationTargetException e) {
			Throwable cause = e.getCause();
			if (cause instanceof RuntimeException runtimeException) {
				throw runtimeException;
			}
			if (cause instanceof Error error) {
				throw error;
			}
			throw new IllegalStateException(target.getClass().getName() + "." + methodName + "() failed", cause);
		}
	}

	private static int invokeInt(Object target, String methodName) {
		Object value = invoke(target, methodName);
		if (value instanceof Number number) {
			return number.intValue();
		}
		throw new IllegalStateException(target.getClass().getName() + "." + methodName + "() did not return a number");
	}

	public record WingScanResult(List<WingGroup> groups, WingGroup selectedGroup, BlockBounds plotBounds) {
		public boolean hasSelectedGroup() {
			return selectedGroup != null;
		}
	}

	public record WingGroup(int index, AeroSurfaceDescriptor surface, int blockCount, BlockBounds bounds) {
		WingGroup withIndex(int index) {
			return new WingGroup(index, surface, blockCount, bounds);
		}
	}

	private record WingBlockInfo(
			String blockId,
			AeroAirfoilProfile profile,
			A4mcVec3 chordDirection,
			A4mcVec3 spanDirection,
			A4mcVec3 normalDirection
	) {
	}

	public record BlockBounds(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
		static BlockBounds fromBoundingBox(Object boundingBox) {
			return new BlockBounds(
					invokeInt(boundingBox, "minX"),
					invokeInt(boundingBox, "minY"),
					invokeInt(boundingBox, "minZ"),
					invokeInt(boundingBox, "maxX"),
					invokeInt(boundingBox, "maxY"),
					invokeInt(boundingBox, "maxZ")
			);
		}

		static BlockBounds fromPositions(List<BlockPos> positions) {
			if (positions.isEmpty()) {
				throw new IllegalArgumentException("positions must not be empty");
			}
			int minX = Integer.MAX_VALUE;
			int minY = Integer.MAX_VALUE;
			int minZ = Integer.MAX_VALUE;
			int maxX = Integer.MIN_VALUE;
			int maxY = Integer.MIN_VALUE;
			int maxZ = Integer.MIN_VALUE;
			for (BlockPos position : positions) {
				minX = Math.min(minX, position.getX());
				minY = Math.min(minY, position.getY());
				minZ = Math.min(minZ, position.getZ());
				maxX = Math.max(maxX, position.getX());
				maxY = Math.max(maxY, position.getY());
				maxZ = Math.max(maxZ, position.getZ());
			}
			return new BlockBounds(minX, minY, minZ, maxX, maxY, maxZ);
		}

		boolean isEmpty() {
			return minX > maxX || minY > maxY || minZ > maxZ;
		}

		int sizeX() {
			return maxX - minX + 1;
		}

		int sizeY() {
			return maxY - minY + 1;
		}

		int sizeZ() {
			return maxZ - minZ + 1;
		}

		double centerX() {
			return 0.5 * (minX + maxX + 1.0);
		}

		double centerY() {
			return 0.5 * (minY + maxY + 1.0);
		}

		double centerZ() {
			return 0.5 * (minZ + maxZ + 1.0);
		}
	}

}
