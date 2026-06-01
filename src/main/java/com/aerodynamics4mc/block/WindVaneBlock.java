package com.aerodynamics4mc.block;

import com.aerodynamics4mc.api.GameplayWindSample;
import com.mojang.serialization.MapCodec;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.Locale;

public class WindVaneBlock extends BaseEntityBlock {
    public static final MapCodec<WindVaneBlock> CODEC = simpleCodec(WindVaneBlock::new);
    public static final EnumProperty<Direction> FACING = BlockStateProperties.HORIZONTAL_FACING;
    private static final VoxelShape SHAPE = Block.box(5.0, 0.0, 5.0, 11.0, 16.0, 11.0);

    protected WindVaneBlock(Properties properties) {
        super(properties);
        registerDefaultState(getStateDefinition().any().setValue(FACING, Direction.NORTH));
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos blockPos, BlockState blockState) {
        return new WindVaneBlockEntity(blockPos, blockState);
    }

    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState blockState, BlockEntityType<T> blockEntityType) {
        if (level.isClientSide()) {
            return null;
        }
        return createTickerHelper(blockEntityType, ModBlocks.WIND_VANE_BLOCK_ENTITY/*? neoforge{ */.get()/*?} */, WindVaneBlockEntity::tick);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext ctx) {
        return defaultBlockState().setValue(FACING, ctx.getHorizontalDirection().getOpposite());
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (level.isClientSide()) {
            return InteractionResult.SUCCESS;
        }
        if (!(level instanceof ServerLevel serverWorld)) {
            return InteractionResult.PASS;
        }
        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (!(blockEntity instanceof WindVaneBlockEntity vane)) {
            return InteractionResult.PASS;
        }
        vane.sampleNow(serverWorld, state);
        if (!vane.hasSample()) {
            player.displayClientMessage(Component.translatable("message.aerodynamics4mc.wind_vane.no_flow").withStyle(ChatFormatting.GRAY), false);
            return InteractionResult.SUCCESS;
        }

        GameplayWindSample sample = vane.lastSample();
        player.displayClientMessage(
                Component.translatable(
                        "message.aerodynamics4mc.wind_vane.status",
                        directionComponent(vane.lastDirection()),
                        format(sample.effectiveSpeedMetersPerSecond()),
                        format(sample.gustVelocity().length())
                ).withStyle(ChatFormatting.AQUA),
                false
        );
        player.displayClientMessage(
                Component.translatable(
                        "message.aerodynamics4mc.wind_vane.source",
                        sample.sourceLevel().name(),
                        percent(sample.confidence())
                ).withStyle(ChatFormatting.GRAY),
                false
        );
        return InteractionResult.SUCCESS;
    }

    private static Component directionComponent(Direction direction) {
        return switch (direction) {
            case NORTH -> Component.translatable("message.aerodynamics4mc.wind_meter.direction.north");
            case EAST -> Component.translatable("message.aerodynamics4mc.wind_meter.direction.east");
            case SOUTH -> Component.translatable("message.aerodynamics4mc.wind_meter.direction.south");
            case WEST -> Component.translatable("message.aerodynamics4mc.wind_meter.direction.west");
            default -> Component.translatable("message.aerodynamics4mc.wind_meter.direction.calm");
        };
    }

    private static String format(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    private static String percent(double value) {
        double clamped = Math.max(0.0, Math.min(1.0, Double.isFinite(value) ? value : 0.0));
        return String.format(Locale.ROOT, "%.0f%%", clamped * 100.0);
    }
}
