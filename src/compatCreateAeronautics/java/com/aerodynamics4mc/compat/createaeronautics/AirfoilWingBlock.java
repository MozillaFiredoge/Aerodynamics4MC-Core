package com.aerodynamics4mc.compat.createaeronautics;

import com.mojang.serialization.MapCodec;
import dev.ryanhcode.sable.api.block.BlockSubLevelLiftProvider;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.joml.Vector3d;
import org.joml.Vector3dc;

public class AirfoilWingBlock extends BaseEntityBlock implements BlockSubLevelLiftProvider {
	public static final MapCodec<AirfoilWingBlock> CODEC = simpleCodec(AirfoilWingBlock::new);
	public static final EnumProperty<Direction> FACING = BlockStateProperties.HORIZONTAL_FACING;
	private static final VoxelShape SHAPE = Block.box(0.0, 6.0, 0.0, 16.0, 10.0, 16.0);

	public AirfoilWingBlock(Properties properties) {
		super(properties);
		registerDefaultState(getStateDefinition().any().setValue(FACING, Direction.NORTH));
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
		return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
	}

	@Override
	protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
		return SHAPE;
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
			wing.setAirfoilId(CreateAeronauticsAirfoilLibrary.selectedId());
		}
	}

	@Override
	public Direction sable$getNormal(BlockState state) {
		return Direction.UP;
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
		builder.add(FACING);
	}
}
