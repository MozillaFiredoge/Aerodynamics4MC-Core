package com.aerodynamics4mc.compat.createaeronautics;

import com.aerodynamics4mc.api.AeroL2Request;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.Objects;

public final class CreateAeronauticsSubLevelAdapter {
	private static final int MIN_GRID_SIZE = 4;
	private static final int MAX_GRID_SIZE = 256;
	private static final double DOMAIN_PADDING_FRACTION = 0.25;
	private static final int MIN_DOMAIN_PADDING_BLOCKS = 2;
	private static final float DEFAULT_WIND_SPEED_METERS_PER_SECOND = 8.0f;

	public ScanResult scan(Object serverSubLevel, int gridSize) {
		Objects.requireNonNull(serverSubLevel, "serverSubLevel");
		validateGridSize(gridSize);

		Object plot = invoke(serverSubLevel, "getPlot");
		Object plotBounds = invoke(plot, "getBoundingBox");
		BlockBounds bodyBounds = BlockBounds.fromBoundingBox(plotBounds);
		if (bodyBounds.isEmpty()) {
			return emptyScan(serverSubLevel, gridSize, bodyBounds);
		}

		DomainBounds domain = DomainBounds.around(bodyBounds);
		byte[] solidMask = AeroL2Request.createSolidMask(gridSize, gridSize, gridSize);
		ScanAccumulator accumulator = new ScanAccumulator();

		for (Object holder : loadedChunkHolders(plot)) {
			Object chunkObject = invoke(holder, "getChunk");
			if (chunkObject instanceof LevelChunk chunk) {
				scanChunk(chunk, bodyBounds, domain, gridSize, solidMask, accumulator);
			}
		}

		float cellSize = (float) domain.cellSize(gridSize);
		SubLevelMaskFrame frame = new SubLevelMaskFrame(
				serverSubLevel,
				invoke(serverSubLevel, "logicalPose"),
				bodyBounds.minX(),
				bodyBounds.minY(),
				bodyBounds.minZ(),
				bodyBounds.maxX(),
				bodyBounds.maxY(),
				bodyBounds.maxZ(),
				domain.minX(),
				domain.minY(),
				domain.minZ(),
				domain.maxX(),
				domain.maxY(),
				domain.maxZ(),
				cellSize,
				gridSize,
				gridSize,
				gridSize,
				accumulator.solidBlocks,
				accumulator.solidCells
		);

		AeroL2Request request = AeroL2Request.builder(gridSize, gridSize, gridSize)
				.cellSizeMeters(cellSize)
				.inlet(0.0f, 0.0f, DEFAULT_WIND_SPEED_METERS_PER_SECOND)
				.solidMask(solidMask)
				.forceMomentReference(
						(float) (bodyBounds.centerX() - domain.minX()),
						(float) (bodyBounds.centerY() - domain.minY()),
						(float) (bodyBounds.centerZ() - domain.minZ())
				)
				.build();
		return new ScanResult(request, frame);
	}

	public void apply(Object serverSubLevel, Object rigidBodyHandle, ForceMomentImpulse impulse) {
		throw new UnsupportedOperationException("Create Aeronautics force application is not implemented yet");
	}

	private static ScanResult emptyScan(Object serverSubLevel, int gridSize, BlockBounds bodyBounds) {
		DomainBounds domain = DomainBounds.empty();
		byte[] solidMask = AeroL2Request.createSolidMask(gridSize, gridSize, gridSize);
		float cellSize = (float) domain.cellSize(gridSize);
		SubLevelMaskFrame frame = new SubLevelMaskFrame(
				serverSubLevel,
				invoke(serverSubLevel, "logicalPose"),
				bodyBounds.minX(),
				bodyBounds.minY(),
				bodyBounds.minZ(),
				bodyBounds.maxX(),
				bodyBounds.maxY(),
				bodyBounds.maxZ(),
				domain.minX(),
				domain.minY(),
				domain.minZ(),
				domain.maxX(),
				domain.maxY(),
				domain.maxZ(),
				cellSize,
				gridSize,
				gridSize,
				gridSize,
				0,
				0
		);
		AeroL2Request request = AeroL2Request.builder(gridSize, gridSize, gridSize)
				.cellSizeMeters(cellSize)
				.inlet(0.0f, 0.0f, DEFAULT_WIND_SPEED_METERS_PER_SECOND)
				.solidMask(solidMask)
				.build();
		return new ScanResult(request, frame);
	}

	private static void scanChunk(
			LevelChunk chunk,
			BlockBounds bodyBounds,
			DomainBounds domain,
			int gridSize,
			byte[] solidMask,
			ScanAccumulator accumulator
	) {
		ChunkPos chunkPos = chunk.getPos();
		int minX = Math.max(bodyBounds.minX(), chunkPos.getMinBlockX());
		int maxX = Math.min(bodyBounds.maxX(), chunkPos.getMaxBlockX());
		int minZ = Math.max(bodyBounds.minZ(), chunkPos.getMinBlockZ());
		int maxZ = Math.min(bodyBounds.maxZ(), chunkPos.getMaxBlockZ());
		if (minX > maxX || minZ > maxZ) {
			return;
		}

		BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
		for (int x = minX; x <= maxX; x++) {
			for (int z = minZ; z <= maxZ; z++) {
				for (int y = bodyBounds.minY(); y <= bodyBounds.maxY(); y++) {
					cursor.set(x, y, z);
					BlockState state = chunk.getBlockState(cursor);
					if (state.isAir()) {
						continue;
					}
					accumulator.solidBlocks++;
					markBlock(solidMask, gridSize, domain, x, y, z, accumulator);
				}
			}
		}
	}

	private static void markBlock(
			byte[] solidMask,
			int gridSize,
			DomainBounds domain,
			int blockX,
			int blockY,
			int blockZ,
			ScanAccumulator accumulator
	) {
		int minX = domain.cellFloorX(blockX, gridSize);
		int minY = domain.cellFloorY(blockY, gridSize);
		int minZ = domain.cellFloorZ(blockZ, gridSize);
		int maxX = domain.cellCeilX(blockX + 1.0, gridSize) - 1;
		int maxY = domain.cellCeilY(blockY + 1.0, gridSize) - 1;
		int maxZ = domain.cellCeilZ(blockZ + 1.0, gridSize) - 1;

		minX = clampCell(minX, gridSize);
		minY = clampCell(minY, gridSize);
		minZ = clampCell(minZ, gridSize);
		maxX = clampCell(maxX, gridSize);
		maxY = clampCell(maxY, gridSize);
		maxZ = clampCell(maxZ, gridSize);

		for (int x = minX; x <= maxX; x++) {
			for (int y = minY; y <= maxY; y++) {
				for (int z = minZ; z <= maxZ; z++) {
					int cell = AeroL2Request.cellIndex(gridSize, gridSize, gridSize, x, y, z);
					if (solidMask[cell] == 0) {
						solidMask[cell] = 1;
						accumulator.solidCells++;
					}
				}
			}
		}
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

	private static int clampCell(int value, int gridSize) {
		return Math.max(0, Math.min(gridSize - 1, value));
	}

	private static void validateGridSize(int gridSize) {
		if (gridSize < MIN_GRID_SIZE || gridSize > MAX_GRID_SIZE) {
			throw new IllegalArgumentException(
					"gridSize must be in [" + MIN_GRID_SIZE + ", " + MAX_GRID_SIZE + "]"
			);
		}
	}

	public record ScanResult(AeroL2Request request, SubLevelMaskFrame referenceFrame) {
	}

	public record SubLevelMaskFrame(
			Object serverSubLevel,
			Object logicalPose,
			int bodyMinX,
			int bodyMinY,
			int bodyMinZ,
			int bodyMaxX,
			int bodyMaxY,
			int bodyMaxZ,
			double domainMinX,
			double domainMinY,
			double domainMinZ,
			double domainMaxX,
			double domainMaxY,
			double domainMaxZ,
			float cellSizeMeters,
			int nx,
			int ny,
			int nz,
			int solidBlocks,
			int solidCells
	) {
	}

	public record ForceMomentImpulse(
			double forceX,
			double forceY,
			double forceZ,
			double torqueX,
			double torqueY,
			double torqueZ
	) {
	}

	private record BlockBounds(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
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

	private record DomainBounds(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
		static DomainBounds around(BlockBounds bodyBounds) {
			int longestBodyAxis = Math.max(bodyBounds.sizeX(), Math.max(bodyBounds.sizeY(), bodyBounds.sizeZ()));
			int padding = Math.max(
					MIN_DOMAIN_PADDING_BLOCKS,
					(int) Math.ceil(longestBodyAxis * DOMAIN_PADDING_FRACTION)
			);
			double sideLength = Math.max(1.0, longestBodyAxis + 2.0 * padding);
			double halfSide = 0.5 * sideLength;
			return new DomainBounds(
					bodyBounds.centerX() - halfSide,
					bodyBounds.centerY() - halfSide,
					bodyBounds.centerZ() - halfSide,
					bodyBounds.centerX() + halfSide,
					bodyBounds.centerY() + halfSide,
					bodyBounds.centerZ() + halfSide
			);
		}

		static DomainBounds empty() {
			return new DomainBounds(0.0, 0.0, 0.0, 1.0, 1.0, 1.0);
		}

		double cellSize(int gridSize) {
			return (maxX - minX) / gridSize;
		}

		int cellFloorX(double value, int gridSize) {
			return cellFloor(value, minX, gridSize);
		}

		int cellFloorY(double value, int gridSize) {
			return cellFloor(value, minY, gridSize);
		}

		int cellFloorZ(double value, int gridSize) {
			return cellFloor(value, minZ, gridSize);
		}

		int cellCeilX(double value, int gridSize) {
			return cellCeil(value, minX, gridSize);
		}

		int cellCeilY(double value, int gridSize) {
			return cellCeil(value, minY, gridSize);
		}

		int cellCeilZ(double value, int gridSize) {
			return cellCeil(value, minZ, gridSize);
		}

		private int cellFloor(double value, double min, int gridSize) {
			return (int) Math.floor((value - min) / cellSize(gridSize));
		}

		private int cellCeil(double value, double min, int gridSize) {
			return (int) Math.ceil((value - min) / cellSize(gridSize));
		}
	}

	private static final class ScanAccumulator {
		private int solidBlocks;
		private int solidCells;
	}
}
