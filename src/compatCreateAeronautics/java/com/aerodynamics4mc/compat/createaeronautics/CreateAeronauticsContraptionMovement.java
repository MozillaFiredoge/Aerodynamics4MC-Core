package com.aerodynamics4mc.compat.createaeronautics;

import com.aerodynamics4mc.ModTemplate;
import com.aerodynamics4mc.api.A4mcId;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

public final class CreateAeronauticsContraptionMovement {
	private static final String BLOCK_MOVEMENT_CHECKS_CLASS =
			"com.simibubi.create.api.contraption.BlockMovementChecks";
	private static final String ATTACHED_CHECK_CLASS =
			BLOCK_MOVEMENT_CHECKS_CLASS + "$AttachedCheck";
	private static final String NOT_SUPPORTIVE_CHECK_CLASS =
			BLOCK_MOVEMENT_CHECKS_CLASS + "$NotSupportiveCheck";
	private static final String CHECK_RESULT_CLASS =
			BLOCK_MOVEMENT_CHECKS_CLASS + "$CheckResult";

	private static boolean registered;

	private CreateAeronauticsContraptionMovement() {
	}

	public static synchronized void register() {
		if (registered) {
			return;
		}
		try {
			ClassLoader loader = CreateAeronauticsContraptionMovement.class.getClassLoader();
			Class<?> movementChecks = Class.forName(BLOCK_MOVEMENT_CHECKS_CLASS, false, loader);
			Class<?> attachedCheck = Class.forName(ATTACHED_CHECK_CLASS, false, loader);
			Class<?> notSupportiveCheck = Class.forName(NOT_SUPPORTIVE_CHECK_CLASS, false, loader);
			Class<?> checkResult = Class.forName(CHECK_RESULT_CLASS, false, loader);

			Object attachedProxy = Proxy.newProxyInstance(
					loader,
					new Class<?>[] {attachedCheck},
					new AttachedCheckHandler(checkResult)
			);
			Object notSupportiveProxy = Proxy.newProxyInstance(
					loader,
					new Class<?>[] {notSupportiveCheck},
					new NotSupportiveCheckHandler(checkResult)
			);
			movementChecks.getMethod("registerAttachedCheck", attachedCheck).invoke(null, attachedProxy);
			movementChecks.getMethod("registerNotSupportiveCheck", notSupportiveCheck).invoke(null, notSupportiveProxy);
			registered = true;
		} catch (ReflectiveOperationException | LinkageError e) {
			ModTemplate.LOGGER.warn("Failed to register Create contraption movement checks for airfoil wings", e);
		}
	}

	private static Object isWingAttachedTowards(
			Class<?> checkResult,
			BlockState state,
			Level level,
			BlockPos pos,
			Direction direction
	) {
		if (!(state.getBlock() instanceof AirfoilWingBlock)) {
			return checkResult(checkResult, "PASS");
		}
		if (direction.getAxis() == AirfoilWingBlock.normalDirection(state).getAxis()) {
			return checkResult(checkResult, false);
		}
		return checkResult(checkResult, isCompatibleWingNeighbor(state, level, pos, direction));
	}

	private static Object isWingNotSupportive(Class<?> checkResult, BlockState state, Direction direction) {
		if (!(state.getBlock() instanceof AirfoilWingBlock)) {
			return checkResult(checkResult, "PASS");
		}
		return checkResult(checkResult, direction.getAxis() == AirfoilWingBlock.normalDirection(state).getAxis());
	}

	private static Object handleObjectMethod(Object proxy, Method method, Object[] args) {
		return switch (method.getName()) {
			case "equals" -> proxy == args[0];
			case "hashCode" -> System.identityHashCode(proxy);
			case "toString" -> "A4MC airfoil wing contraption movement check";
			default -> null;
		};
	}

	@SuppressWarnings({"unchecked", "rawtypes"})
	private static Object checkResult(Class<?> checkResult, String name) {
		return Enum.valueOf((Class<? extends Enum>) checkResult.asSubclass(Enum.class), name);
	}

	private static Object checkResult(Class<?> checkResult, boolean value) {
		return checkResult(checkResult, value ? "SUCCESS" : "FAIL");
	}

	private static boolean isCompatibleWingNeighbor(BlockState state, Level level, BlockPos pos, Direction direction) {
		if (level == null || pos == null || direction == null) {
			return false;
		}
		BlockPos neighborPos = pos.relative(direction);
		BlockState neighborState = level.getBlockState(neighborPos);
		if (!(neighborState.getBlock() instanceof AirfoilWingBlock)) {
			return false;
		}
		if (AirfoilWingBlock.chordDirection(state) != AirfoilWingBlock.chordDirection(neighborState)) {
			return false;
		}
		if (AirfoilWingBlock.isVertical(state) != AirfoilWingBlock.isVertical(neighborState)) {
			return false;
		}
		AirfoilWingVariant variant = variant(state);
		if (variant != variant(neighborState)) {
			return false;
		}
		if (variant != AirfoilWingVariant.CUSTOM) {
			return true;
		}
		A4mcId airfoilId = airfoilId(level, pos);
		return airfoilId != null && airfoilId.equals(airfoilId(level, neighborPos));
	}

	private static AirfoilWingVariant variant(BlockState state) {
		return state != null && state.hasProperty(AirfoilWingBlock.VARIANT)
				? state.getValue(AirfoilWingBlock.VARIANT)
				: AirfoilWingVariant.NACA_0012;
	}

	private static A4mcId airfoilId(Level level, BlockPos pos) {
		BlockEntity blockEntity = level.getBlockEntity(pos);
		return blockEntity instanceof AirfoilWingBlockEntity wing ? wing.airfoilId() : null;
	}

	private record AttachedCheckHandler(Class<?> checkResult) implements InvocationHandler {
		@Override
		public Object invoke(Object proxy, Method method, Object[] args) {
			if (method.getDeclaringClass() == Object.class) {
				return handleObjectMethod(proxy, method, args);
			}
			if ("isBlockAttachedTowards".equals(method.getName())) {
				return isWingAttachedTowards(
						checkResult,
						(BlockState) args[0],
						(Level) args[1],
						(BlockPos) args[2],
						(Direction) args[3]
				);
			}
			return CreateAeronauticsContraptionMovement.checkResult(checkResult, "PASS");
		}
	}

	private record NotSupportiveCheckHandler(Class<?> checkResult) implements InvocationHandler {
		@Override
		public Object invoke(Object proxy, Method method, Object[] args) {
			if (method.getDeclaringClass() == Object.class) {
				return handleObjectMethod(proxy, method, args);
			}
			if ("isNotSupportive".equals(method.getName())) {
				return isWingNotSupportive(checkResult, (BlockState) args[0], (Direction) args[1]);
			}
			return CreateAeronauticsContraptionMovement.checkResult(checkResult, "PASS");
		}
	}
}
