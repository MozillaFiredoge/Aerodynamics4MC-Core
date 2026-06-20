package com.aerodynamics4mc.compat.createaeronautics;

import com.github.razorplay.packet_handler.exceptions.PacketSerializationException;
import com.github.razorplay.packet_handler.network.IPacket;
import com.github.razorplay.packet_handler.network.network_util.PacketDataSerializer;

public final class CreateAeronauticsAirfoilItemActionPacket implements IPacket {
	public static final int ACTION_USE = 0;
	public static final int ACTION_EXPORT = 1;

	private int action;
	private boolean mainHand;
	private String airfoilId;

	public CreateAeronauticsAirfoilItemActionPacket() {
		this(ACTION_USE, true, "");
	}

	public CreateAeronauticsAirfoilItemActionPacket(int action, boolean mainHand, String airfoilId) {
		this.action = action;
		this.mainHand = mainHand;
		this.airfoilId = airfoilId == null ? "" : airfoilId;
	}

	public static CreateAeronauticsAirfoilItemActionPacket use(boolean mainHand, String airfoilId) {
		return new CreateAeronauticsAirfoilItemActionPacket(ACTION_USE, mainHand, airfoilId);
	}

	public static CreateAeronauticsAirfoilItemActionPacket export(String airfoilId) {
		return new CreateAeronauticsAirfoilItemActionPacket(ACTION_EXPORT, true, airfoilId);
	}

	public int action() {
		return action;
	}

	public boolean mainHand() {
		return mainHand;
	}

	public String airfoilId() {
		return airfoilId;
	}

	@Override
	public void read(PacketDataSerializer serializer) throws PacketSerializationException {
		action = serializer.readInt();
		mainHand = serializer.readBoolean();
		airfoilId = serializer.readString();
	}

	@Override
	public void write(PacketDataSerializer serializer) throws PacketSerializationException {
		serializer.writeInt(action);
		serializer.writeBoolean(mainHand);
		serializer.writeString(airfoilId);
	}
}
