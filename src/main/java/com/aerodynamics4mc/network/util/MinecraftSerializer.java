package com.aerodynamics4mc.network.util;

import com.aerodynamics4mc.api.A4mcBlockPos;
import com.aerodynamics4mc.api.A4mcId;
import com.github.razorplay.packet_handler.exceptions.PacketSerializationException;
import com.github.razorplay.packet_handler.network.network_util.PacketDataSerializer;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;

import java.util.Objects;

public final class MinecraftSerializer {

	private MinecraftSerializer() {}

	public static void writeIdentifier(PacketDataSerializer s, Identifier id) {
		writeA4mcId(s, fromMinecraftId(id));
	}

	public static Identifier readIdentifier(PacketDataSerializer s) throws PacketSerializationException {
		return toMinecraftId(readA4mcId(s));
	}

	public static void writeA4mcId(PacketDataSerializer s, A4mcId id) {
		A4mcId safeId = Objects.requireNonNull(id, "id");
		s.writeString(safeId.namespace());
		s.writeString(safeId.path());
	}

	public static A4mcId readA4mcId(PacketDataSerializer s) throws PacketSerializationException {
		String namespace = s.readString();
		String path = s.readString();
		return new A4mcId(namespace, path);
	}

	public static Identifier toMinecraftId(A4mcId id) {
		A4mcId safeId = Objects.requireNonNull(id, "id");
		return Identifier.fromNamespaceAndPath(safeId.namespace(), safeId.path());
	}

	public static A4mcId fromMinecraftId(Identifier id) {
		Identifier safeId = Objects.requireNonNull(id, "id");
		return new A4mcId(safeId.getNamespace(), safeId.getPath());
	}

	public static void writeBlockPos(PacketDataSerializer s, BlockPos pos) {
		writeA4mcBlockPos(s, fromMinecraftBlockPos(pos));
	}

	public static BlockPos readBlockPos(PacketDataSerializer s) throws PacketSerializationException {
		return toMinecraftBlockPos(readA4mcBlockPos(s));
	}

	public static void writeA4mcBlockPos(PacketDataSerializer s, A4mcBlockPos pos) {
		A4mcBlockPos safePos = Objects.requireNonNull(pos, "pos");
		s.writeInt(safePos.x());
		s.writeInt(safePos.y());
		s.writeInt(safePos.z());
	}

	public static A4mcBlockPos readA4mcBlockPos(PacketDataSerializer s) throws PacketSerializationException {
		return new A4mcBlockPos(s.readInt(), s.readInt(), s.readInt());
	}

	public static BlockPos toMinecraftBlockPos(A4mcBlockPos pos) {
		A4mcBlockPos safePos = Objects.requireNonNull(pos, "pos");
		return new BlockPos(safePos.x(), safePos.y(), safePos.z());
	}

	public static A4mcBlockPos fromMinecraftBlockPos(BlockPos pos) {
		BlockPos safePos = Objects.requireNonNull(pos, "pos");
		return new A4mcBlockPos(safePos.getX(), safePos.getY(), safePos.getZ());
	}

	public static void writeShortArray(PacketDataSerializer s, short[] array) {
		s.writeInt(array.length);
		for (short v : array) {
			s.writeShort(v);
		}
	}

	public static short[] readShortArray(PacketDataSerializer s) throws PacketSerializationException {
		int len = s.readInt();
		if (len < 0) throw new RuntimeException("Invalid length for short[]");
		short[] arr = new short[len];
		for (int i = 0; i < len; i++) {
			arr[i] = s.readShort();
		}
		return arr;
	}
}
