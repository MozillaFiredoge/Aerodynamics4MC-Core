package com.aerodynamics4mc.vehicle;

import net.minecraft.stats.Stats;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
//? <1.21.11 {
/*import net.minecraft.world.InteractionResultHolder;
*///?}
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

public class SailboatItem extends Item {
    public SailboatItem(Properties properties) {
        super(properties);
    }

    @Override
    //? >=1.21.11 {
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        return useSailboat(level, player, hand);
    }
    //?} <1.21.11 {
    /*public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        return new InteractionResultHolder<>(useSailboat(level, player, hand), player.getItemInHand(hand));
    }
    *///?}

    private InteractionResult useSailboat(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        BlockHitResult hit = getPlayerPOVHitResult(level, player, ClipContext.Fluid.ANY);
        if (hit.getType() == HitResult.Type.MISS) {
            return InteractionResult.PASS;
        }
        if (hit.getType() != HitResult.Type.BLOCK) {
            return InteractionResult.PASS;
        }

        Vec3 pos = hit.getLocation();
        SailboatEntity sailboat = new SailboatEntity(ModEntities.sailboat(), level);
        sailboat.setPos(pos.x, pos.y, pos.z);
        sailboat.setYRot(player.getYRot());
        if (!level.noCollision(sailboat, sailboat.getBoundingBox())) {
            return InteractionResult.FAIL;
        }

        if (!level.isClientSide()) {
            level.addFreshEntity(sailboat);
            if (!player.isCreative()) {
                stack.shrink(1);
            }
        }
        player.awardStat(Stats.ITEM_USED.get(this));
        return InteractionResult.SUCCESS;
    }
}
