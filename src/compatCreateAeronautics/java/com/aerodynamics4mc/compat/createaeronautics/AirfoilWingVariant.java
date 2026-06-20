package com.aerodynamics4mc.compat.createaeronautics;

import com.aerodynamics4mc.api.A4mcId;
import com.aerodynamics4mc.api.AeroAirfoilPresets;
import net.minecraft.util.StringRepresentable;

public enum AirfoilWingVariant implements StringRepresentable {
	FLAT_PLATE("flat_plate", AeroAirfoilPresets.FLAT_PLATE.id()),
	NACA_0012("naca_0012", AeroAirfoilPresets.NACA_0012.id()),
	NACA_2412("naca_2412", AeroAirfoilPresets.NACA_2412.id()),
	NACA_4412("naca_4412", AeroAirfoilPresets.NACA_4412.id()),
	CUSTOM("custom", null);

	private final String serializedName;
	private final A4mcId airfoilId;

	AirfoilWingVariant(String serializedName, A4mcId airfoilId) {
		this.serializedName = serializedName;
		this.airfoilId = airfoilId;
	}

	public static AirfoilWingVariant fromAirfoilId(A4mcId id) {
		for (AirfoilWingVariant variant : values()) {
			if (variant.airfoilId != null && variant.airfoilId.equals(id)) {
				return variant;
			}
		}
		return CUSTOM;
	}

	@Override
	public String getSerializedName() {
		return serializedName;
	}
}
