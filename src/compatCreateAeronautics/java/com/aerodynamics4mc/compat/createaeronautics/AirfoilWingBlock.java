package com.aerodynamics4mc.compat.createaeronautics;

import com.mojang.serialization.MapCodec;
import dev.ryanhcode.sable.api.block.BlockSubLevelCustomCenterOfMass;
import dev.ryanhcode.sable.api.block.BlockSubLevelLiftProvider;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
//? >=1.21.11 {
import net.minecraft.world.InteractionResult;
//?} <1.21.11 {
/*import net.minecraft.world.ItemInteractionResult;
*///?}
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.joml.Vector3d;
import org.joml.Vector3dc;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class AirfoilWingBlock extends BaseEntityBlock implements BlockSubLevelLiftProvider, BlockSubLevelCustomCenterOfMass {
	public static final MapCodec<AirfoilWingBlock> CODEC = simpleCodec(AirfoilWingBlock::new);
	public static final EnumProperty<Direction> FACING = BlockStateProperties.HORIZONTAL_FACING;
	public static final BooleanProperty VERTICAL = BooleanProperty.create("vertical");
	public static final EnumProperty<AirfoilWingVariant> VARIANT = EnumProperty.create("variant", AirfoilWingVariant.class);
	private static final Vector3dc BLOCK_CENTER_OF_MASS = new Vector3d(0.5, 0.5, 0.5);
	private static final VoxelShape HORIZONTAL_SHAPE = Block.box(0.0, 6.0, 0.0, 16.0, 10.0, 16.0);
	private static final VoxelShape VERTICAL_NORTH_SOUTH_SHAPE = Block.box(6.0, 0.0, 0.0, 10.0, 16.0, 16.0);
	private static final VoxelShape VERTICAL_EAST_WEST_SHAPE = Block.box(0.0, 0.0, 6.0, 16.0, 16.0, 10.0);

	public AirfoilWingBlock(Properties properties) {
		super(properties);
		registerDefaultState(getStateDefinition().any()
				.setValue(FACING, Direction.NORTH)
				.setValue(VERTICAL, false)
				.setValue(VARIANT, AirfoilWingVariant.NACA_0012));
	}

	@Override
	protected MapCodec<? extends BaseEntityBlock> codec() {
		return CODEC;
	}

	@Override
	public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
		return new AirfoilWingBlockEntity(pos, state);
	}

	@Override
	public BlockState getStateForPlacement(BlockPlaceContext context) {
		return defaultBlockState()
				.setValue(FACING, context.getHorizontalDirection().getOpposite())
				.setValue(VERTICAL, placementVertical(context))
				.setValue(VARIANT, AirfoilWingVariant.fromAirfoilId(AirfoilWingBlockItem.airfoilIdOrSelected(context.getItemInHand())));
	}

	@Override
	protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
		if (!isVertical(state)) {
			return HORIZONTAL_SHAPE;
		}
		Direction facing = chordDirection(state);
		return facing == Direction.NORTH || facing == Direction.SOUTH
				? VERTICAL_NORTH_SOUTH_SHAPE
				: VERTICAL_EAST_WEST_SHAPE;
	}

	@Override
	protected RenderShape getRenderShape(BlockState state) {
		return RenderShape.MODEL;
	}

	@Override
	public void setPlacedBy(Level level, BlockPos pos, BlockState state, LivingEntity placer, ItemStack stack) {
		super.setPlacedBy(level, pos, state, placer, stack);
		if (level.isClientSide()) {
			return;
		}
		if (level.getBlockEntity(pos) instanceof AirfoilWingBlockEntity wing) {
			wing.setAirfoilId(AirfoilWingBlockItem.airfoilIdOrSelected(stack));
		}
	}

	//? >=1.21.11 {
	@Override
	protected ItemStack getCloneItemStack(LevelReader level, BlockPos pos, BlockState state, boolean includeData) {
		return cloneWingStack(super.getCloneItemStack(level, pos, state, includeData), level, pos);
	}
	//?} <1.21.11 {
	/*@Override
	public ItemStack getCloneItemStack(LevelReader level, BlockPos pos, BlockState state) {
		return cloneWingStack(super.getCloneItemStack(level, pos, state), level, pos);
	}
	*///?}

	//? >=1.21.11 {
	@Override
	protected InteractionResult useItemOn(
			ItemStack stack,
			BlockState state,
			Level level,
			BlockPos pos,
			Player player,
			InteractionHand hand,
			BlockHitResult hit
	) {
		return placeWingExtension(stack, state, level, pos, player, hit) ? InteractionResult.SUCCESS : InteractionResult.PASS;
	}
	//?} <1.21.11 {
	/*@Override
	protected ItemInteractionResult useItemOn(
			ItemStack stack,
			BlockState state,
			Level level,
			BlockPos pos,
			Player player,
			InteractionHand hand,
			BlockHitResult hit
	) {
		return placeWingExtension(stack, state, level, pos, player, hit)
				? ItemInteractionResult.SUCCESS
				: ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
	}
	*///?}

	private boolean placeWingExtension(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
		if (!(stack.getItem() instanceof AirfoilWingBlockItem) || player == null || player.isShiftKeyDown() || !player.mayBuild()) {
			return false;
		}
		BlockPos target = placementOffset(level, pos, state, hit);
		if (target == null) {
			return false;
		}
		if (!level.isClientSide()) {
			A4mcPlacement.placeWing(level, target, state, stack, player);
		}
		return true;
	}

	@Override
	public Direction sable$getNormal(BlockState state) {
		return normalDirection(state);
	}

	@Override
	public Vector3dc getCenterOfMass(BlockGetter blockGetter, BlockState state) {
		return BLOCK_CENTER_OF_MASS;
	}

	@Override
	public void sable$contributeLiftAndDrag(
			LiftProviderContext context,
			ServerSubLevel subLevel,
			Pose3d pose,
			double timeStepSeconds,
			Vector3dc linearVelocity,
			Vector3dc angularVelocity,
			Vector3d linearImpulseAccumulator,
			Vector3d angularImpulseAccumulator,
			LiftProviderGroup group
	) {
		CreateAeronauticsFlightPolarService.INSTANCE.contributeProviderLiftAndDrag(
				context,
				subLevel,
				pose,
				timeStepSeconds,
				linearVelocity,
				angularVelocity,
				linearImpulseAccumulator,
				angularImpulseAccumulator,
				group
		);
	}

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
		builder.add(FACING, VERTICAL, VARIANT);
	}

	public static Direction chordDirection(BlockState state) {
		return state != null && state.hasProperty(FACING) ? state.getValue(FACING) : Direction.NORTH;
	}

	public static Direction spanDirection(BlockState state) {
		if (isVertical(state)) {
			return Direction.UP;
		}
		return horizontalSpanDirection(chordDirection(state));
	}

	public static Direction normalDirection(BlockState state) {
		if (!isVertical(state)) {
			return Direction.UP;
		}
		return verticalNormalDirection(chordDirection(state));
	}

	public static boolean isVertical(BlockState state) {
		return state != null && state.hasProperty(VERTICAL) && state.getValue(VERTICAL);
	}

	private static boolean placementVertical(BlockPlaceContext context) {
		Direction clickedFace = context.getClickedFace();
		if (clickedFace.getAxis() != Direction.Axis.Y && context.getPlayer() != null && context.getPlayer().isShiftKeyDown()) {
			return false;
		}
		return context.getNearestLookingDirection().getAxis() != Direction.Axis.Y;
	}

	private static Direction horizontalSpanDirection(Direction chordDirection) {
		return switch (chordDirection) {
			case NORTH -> Direction.EAST;
			case SOUTH -> Direction.WEST;
			case EAST -> Direction.SOUTH;
			case WEST -> Direction.NORTH;
			default -> Direction.EAST;
		};
	}

	private static Direction verticalNormalDirection(Direction chordDirection) {
		return switch (chordDirection) {
			case NORTH -> Direction.WEST;
			case SOUTH -> Direction.EAST;
			case EAST -> Direction.NORTH;
			case WEST -> Direction.SOUTH;
			default -> Direction.WEST;
		};
	}

	private static BlockPos placementOffset(Level level, BlockPos pos, BlockState state, BlockHitResult hit) {
		if (level == null || pos == null || state == null || hit == null) {
			return null;
		}
		Direction.Axis normalAxis = normalDirection(state).getAxis();
		Vec3 hitLocation = hit.getLocation();
		List<Direction> candidates = new ArrayList<>(4);
		for (Direction direction : Direction.values()) {
			if (direction.getAxis() != normalAxis) {
				candidates.add(direction);
			}
		}
		candidates.sort(Comparator.comparingDouble(direction -> distanceToNeighborCenter(pos, direction, hitLocation)));
		for (Direction direction : candidates) {
			BlockPos target = pos.relative(direction);
			if (level.getBlockState(target).canBeReplaced()) {
				return target;
			}
		}
		return null;
	}

	private static ItemStack cloneWingStack(ItemStack stack, LevelReader level, BlockPos pos) {
		if (level != null && pos != null && level.getBlockEntity(pos) instanceof AirfoilWingBlockEntity wing) {
			AirfoilWingBlockItem.setAirfoilId(stack, wing.airfoilId());
		}
		return stack;
	}

	private static double distanceToNeighborCenter(BlockPos pos, Direction direction, Vec3 hitLocation) {
		BlockPos target = pos.relative(direction);
		double dx = hitLocation.x - (target.getX() + 0.5);
		double dy = hitLocation.y - (target.getY() + 0.5);
		double dz = hitLocation.z - (target.getZ() + 0.5);
		return dx * dx + dy * dy + dz * dz;
	}

	private static final class A4mcPlacement {
		private static void placeWing(Level level, BlockPos target, BlockState sourceState, ItemStack stack, Player player) {
			BlockState placedState = sourceState
					.setValue(VARIANT, AirfoilWingVariant.fromAirfoilId(AirfoilWingBlockItem.airfoilIdOrSelected(stack)));
			level.setBlock(target, placedState, 3);
			if (level.getBlockEntity(target) instanceof AirfoilWingBlockEntity wing) {
				wing.setAirfoilId(AirfoilWingBlockItem.airfoilIdOrSelected(stack));
			}
			if (!player.getAbilities().instabuild) {
				stack.shrink(1);
			}
		}
	}
}
