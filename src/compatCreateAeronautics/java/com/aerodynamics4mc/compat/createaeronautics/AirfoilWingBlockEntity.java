package com.aerodynamics4mc.compat.createaeronautics;

import com.aerodynamics4mc.api.A4mcId;
import com.aerodynamics4mc.api.AeroAirfoilPresets;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
//? >=1.21.11 {
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
//?} <1.21.11 {
/*import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
*///?}

public final class AirfoilWingBlockEntity extends BlockEntity {
	private static final String AIRFOIL_ID_KEY = "airfoil_id";

	private A4mcId airfoilId = AeroAirfoilPresets.NACA_0012.id();

	public AirfoilWingBlockEntity(BlockPos pos, BlockState state) {
		super(CreateAeronauticsCompatBlocks.AIRFOIL_WING_BLOCK_ENTITY.get(), pos, state);
	}

	public A4mcId airfoilId() {
		return airfoilId;
	}

	public void setAirfoilId(A4mcId airfoilId) {
		this.airfoilId = airfoilId == null ? AeroAirfoilPresets.NACA_0012.id() : airfoilId;
		setChanged();
		if (level != null && !level.isClientSide()) {
			level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
		}
	}

	@Override
	public ClientboundBlockEntityDataPacket getUpdatePacket() {
		return ClientboundBlockEntityDataPacket.create(this);
	}

	//? >=1.21.11 {
	@Override
	protected void saveAdditional(ValueOutput output) {
		super.saveAdditional(output);
		output.putString(AIRFOIL_ID_KEY, airfoilId.toString());
	}

	@Override
	protected void loadAdditional(ValueInput input) {
		super.loadAdditional(input);
		input.getString(AIRFOIL_ID_KEY).ifPresent(this::setAirfoilIdFromString);
	}
	//?} <1.21.11 {
	/*@Override
	protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
		super.saveAdditional(tag, registries);
		tag.putString(AIRFOIL_ID_KEY, airfoilId.toString());
	}

	@Override
	protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
		super.loadAdditional(tag, registries);
		if (tag.contains(AIRFOIL_ID_KEY)) {
			setAirfoilIdFromString(tag.getString(AIRFOIL_ID_KEY));
		}
	}
	*///?}

	private void setAirfoilIdFromString(String value) {
		try {
			airfoilId = A4mcId.parse(value);
		} catch (IllegalArgumentException ignored) {
			airfoilId = AeroAirfoilPresets.NACA_0012.id();
		}
	}
}
