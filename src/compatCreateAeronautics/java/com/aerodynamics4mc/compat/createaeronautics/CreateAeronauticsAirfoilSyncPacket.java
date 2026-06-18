package com.aerodynamics4mc.compat.createaeronautics;

import com.aerodynamics4mc.api.AeroAirfoilDefinition;
import com.aerodynamics4mc.api.AeroAirfoilJson;
import com.github.razorplay.packet_handler.exceptions.PacketSerializationException;
import com.github.razorplay.packet_handler.network.IPacket;
import com.github.razorplay.packet_handler.network.network_util.PacketDataSerializer;

import java.util.ArrayList;
import java.util.List;

public final class CreateAeronauticsAirfoilSyncPacket implements IPacket {
	private long revision;
	private String selectedId;
	private List<String> definitionJson;

	public CreateAeronauticsAirfoilSyncPacket() {
		this(0L, "", List.of());
	}

	public CreateAeronauticsAirfoilSyncPacket(
			long revision,
			String selectedId,
			List<String> definitionJson
	) {
		this.revision = revision;
		this.selectedId = selectedId == null ? "" : selectedId;
		this.definitionJson = definitionJson == null ? List.of() : List.copyOf(definitionJson);
	}

	public static CreateAeronauticsAirfoilSyncPacket fromLibrary() {
		List<String> encoded = new ArrayList<>();
		for (AeroAirfoilDefinition definition : CreateAeronauticsAirfoilLibrary.definitions()) {
			encoded.add(AeroAirfoilJson.write(definition));
		}
		return new CreateAeronauticsAirfoilSyncPacket(
				CreateAeronauticsAirfoilLibrary.revision(),
				CreateAeronauticsAirfoilLibrary.selectedId().toString(),
				encoded
		);
	}

	public long revision() {
		return revision;
	}

	public String selectedId() {
		return selectedId;
	}

	public List<String> definitionJson() {
		return definitionJson;
	}

	@Override
	public void read(PacketDataSerializer serializer) throws PacketSerializationException {
		revision = serializer.readLong();
		selectedId = serializer.readString();
		int count = serializer.readInt();
		if (count < 0 || count > 1024) {
			throw new PacketSerializationException("Invalid airfoil definition count: " + count);
		}
		List<String> encoded = new ArrayList<>(count);
		for (int i = 0; i < count; i++) {
			encoded.add(serializer.readString());
		}
		definitionJson = List.copyOf(encoded);
	}

	@Override
	public void write(PacketDataSerializer serializer) throws PacketSerializationException {
		serializer.writeLong(revision);
		serializer.writeString(selectedId);
		serializer.writeInt(definitionJson.size());
		for (String encoded : definitionJson) {
			serializer.writeString(encoded);
		}
	}
}
