package com.aerodynamics4mc.client;

import com.aerodynamics4mc.ModTemplate;
import com.aerodynamics4mc.api.AeroWindSample;
import com.aerodynamics4mc.api.SamplePolicy;
import com.aerodynamics4mc.network.packet.AeroClientL2PreferencePacket;
import com.aerodynamics4mc.network.packet.AeroCoarseWindPacket;
import com.aerodynamics4mc.network.packet.AeroFlowAnalysisPacket;
import com.aerodynamics4mc.network.packet.AeroFlowPacket;
import com.aerodynamics4mc.network.packet.AeroRuntimeStatePacket;
import lombok.Getter;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
//? fabric{
/*import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
*///?} neoforge{
import net.neoforged.neoforge.network.handling.IPayloadContext;
//?}

@Getter
public final class AeroClientMod {

	private static AeroClientMod instance = null;
	private final AeroVisualizer visualizer = new AeroVisualizer();
	private final IrisWindBridge irisWindBridge = new IrisWindBridge(visualizer);
	public final ClientL2Solver clientL2Solver = new ClientL2Solver(visualizer);

	private AeroClientMod() {
		// private constructor
	}

	public static synchronized AeroClientMod getInstance() {
		if (instance == null)
			instance = new AeroClientMod();

		return instance;
	}

	public void onInitializeClient() {
		clientL2Solver.initialize();
	}

	Component renderStatusText() {
		return Component.literal(
				"Render vectors=" + visualizer.renderVelocityVectorsEnabled()
						+ " streamlines=" + visualizer.renderStreamlinesEnabled()
		);
	}

	// ====================== Network Handlers ======================

	public static void onRuntimeState(AeroRuntimeStatePacket packet, /*? fabric{ */ /*ClientPlayNetworking.Context *//*?} neoforge{ */ IPayloadContext /*?} */ context) {
		context./*? fabric{ */ /*client().execute *//*?} neoforge{ */ enqueueWork /*?} */
		(() -> {
			getInstance().getVisualizer().onRuntimeState(new AeroVisualizer.AeroFlowState(
					packet.isStreamingEnabled(),
					packet.isRenderVelocityVectors(),
					packet.isRenderStreamlines()
			));
			getInstance().getIrisWindBridge().onRuntimeState(packet.isStreamingEnabled());
			getInstance().getClientL2Solver().onRuntimeState(packet.isStreamingEnabled());
			ModTemplate.xplat().sendPacketToServer(new AeroClientL2PreferencePacket(getInstance().getClientL2Solver().isExperimentalEnabled() && packet.isStreamingEnabled()));
		});
	}

	public static void onFlowField(AeroFlowPacket packet, /*? fabric{ */ /*ClientPlayNetworking.Context *//*?} neoforge{ */ IPayloadContext /*?} */ context) {
		context./*? fabric{ */ /*client().execute *//*?} neoforge{ */ enqueueWork /*?} */
		(() -> {
			getInstance().getVisualizer().onFlowField(packet);
			getInstance().getIrisWindBridge().markDirty();
		});
	}

	public static void onCoarseWindField(AeroCoarseWindPacket packet, /*? fabric{ */ /*ClientPlayNetworking.Context *//*?} neoforge{ */ IPayloadContext /*?} */ context) {
		context./*? fabric{ */ /*client().execute *//*?} neoforge{ */ enqueueWork /*?} */
		(() -> {
			getInstance().getVisualizer().onCoarseWindField(packet);
			getInstance().getClientL2Solver().onCoarseWindField(packet);
			getInstance().getIrisWindBridge().markDirty();
		});
	}

	public static void onFlowAnalysis(AeroFlowAnalysisPacket packet, /*? fabric{ */ /*ClientPlayNetworking.Context *//*?} neoforge{ */ IPayloadContext /*?} */ context) {
		context./*? fabric{ */ /*client().execute *//*?} neoforge{ */ enqueueWork /*?} */
		(() -> getInstance().getVisualizer().onFlowAnalysis(packet));
	}

	// ====================== Static API ======================

	public static AeroWindSample sampleFlow(ClientLevel world, Vec3 position) {
		AeroClientMod active = instance;
		SamplePolicy policy = active != null && active.clientL2Solver.isExperimentalEnabled()
				? SamplePolicy.CLIENT_LOCAL_PREFERRED
				: SamplePolicy.SERVER_COARSE_ONLY;
		return sampleFlow(world, position, policy);
	}

	public static AeroWindSample sampleFlow(ClientLevel world, Vec3 position, SamplePolicy policy) {
		AeroClientMod active = instance;
		if (active == null || world == null) {
			return AeroWindSample.ZERO;
		}
		return active.visualizer.sampleFlow(world.dimension().identifier(), position, policy);
	}

	public static Vec3 sampleWind(ClientLevel world, Vec3 position) {
		return sampleFlow(world, position).velocity();
	}

	public static void notifyBlockStateChanged(ClientLevel world, BlockPos pos, BlockState oldState, BlockState newState) {
		AeroClientMod active = instance;
		if (active == null) {
			return;
		}
		active.clientL2Solver.onBlockStateChanged(world, pos, oldState, newState);
	}
}
