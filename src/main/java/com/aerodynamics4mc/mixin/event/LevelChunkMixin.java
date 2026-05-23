package com.aerodynamics4mc.mixin.event;

//? neoforge {
import com.aerodynamics4mc.runtime.AeroServerRuntime;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelChunk.class)
public abstract class LevelChunkMixin {
	@Shadow
	public abstract Level getLevel();
	@ModifyExpressionValue(
			method = "setBlockEntity",
			at = @At(value = "INVOKE", target = "Ljava/util/Map;put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;")
	)
	private <V> V onLoadBlockEntity(V removedBlockEntity, BlockEntity blockEntity) {
		if (blockEntity != null && blockEntity != removedBlockEntity) {
			if (this.getLevel() instanceof ServerLevel) {
				AeroServerRuntime.getInstance().onBlockEntityLoad(blockEntity, (ServerLevel) this.getLevel());
			}
		}
		return removedBlockEntity;
	}

	@Inject(method = "setBlockEntity", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/block/entity/BlockEntity;setRemoved()V", shift = At.Shift.AFTER))
	private void onRemoveBlockEntity(BlockEntity blockEntity, CallbackInfo info, @Local(name = "blockentity") BlockEntity previousEntry) {
		if (this.getLevel() instanceof ServerLevel) {
			AeroServerRuntime.getInstance().onBlockEntityUnload(previousEntry,(ServerLevel) this.getLevel());
		}
	}

	@Inject(method = "removeBlockEntity", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/block/entity/BlockEntity;setRemoved()V"))
	private void onRemoveBlockEntity(BlockPos pos, CallbackInfo ci, @Local(name = "blockentity") @Nullable BlockEntity removeThis) {
		if (removeThis != null && this.getLevel() instanceof ServerLevel) {
			AeroServerRuntime.getInstance().onBlockEntityUnload(removeThis,(ServerLevel) this.getLevel());
		}
	}
}
//?}
