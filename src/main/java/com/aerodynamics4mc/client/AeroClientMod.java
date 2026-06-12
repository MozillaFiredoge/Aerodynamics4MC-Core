package com.aerodynamics4mc.client;

import com.aerodynamics4mc.ModTemplate;
import com.aerodynamics4mc.api.A4mcId;
import com.aerodynamics4mc.api.A4mcVec3;
import com.aerodynamics4mc.api.A4mcWorldRef;
import com.aerodynamics4mc.api.AeroWindSample;
import com.aerodynamics4mc.api.SamplePolicy;
import com.aerodynamics4mc.api.client.AeroClientWindApi;
import com.aerodynamics4mc.api.client.AeroClientWindRuntimeProvider;
import com.aerodynamics4mc.api.minecraft.AeroMinecraftVectors;
import com.aerodynamics4mc.network.packet.AeroClientL2PreferencePacket;
import com.aerodynamics4mc.network.packet.AeroCoarseWindPacket;
import com.aerodynamics4mc.network.packet.AeroFlowAnalysisPacket;
import com.aerodynamics4mc.network.packet.AeroFlowPacket;
import com.aerodynamics4mc.network.packet.AeroLocalWeatherPacket;
import com.aerodynamics4mc.network.packet.AeroMesoscaleMapPacket;
import com.aerodynamics4mc.network.packet.AeroRuntimeStatePacket;
import lombok.Getter;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
//? fabric{
/*import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
*///?} neoforge{
import net.neoforged.neoforge.network.handling.IPayloadContext;
//?}

@Getter
public final class AeroClientMod implements AeroClientWindRuntimeProvider {

	private static AeroClientMod instance = null;
	private final AeroVisualizer visualizer = new AeroVisualizer();
	private final IrisWindBridge irisWindBridge = new IrisWindBridge(visualizer);
	private final ClientWindAmbienceManager windAmbienceManager = new ClientWindAmbienceManager();
	private final ClientWindPresenceManager windPresenceManager = new ClientWindPresenceManager();
	private final GroundDustWindController groundDustWindController = new GroundDustWindController();
	private final ClientMeteorologicalMapData meteorologicalMapData = new ClientMeteorologicalMapData();
	private final ClientLocalWeatherData localWeatherData = new ClientLocalWeatherData();
	public final ClientL2Solver clientL2Solver = new ClientL2Solver(visualizer);

	private AeroClientMod() {
		AeroClientWindApi.registerProvider(this);
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

	public static void onMesoscaleMap(AeroMesoscaleMapPacket packet, /*? fabric{ */ /*ClientPlayNetworking.Context *//*?} neoforge{ */ IPayloadContext /*?} */ context) {
		context./*? fabric{ */ /*client().execute *//*?} neoforge{ */ enqueueWork /*?} */
		(() -> {
			AeroClientMod client = getInstance();
			client.getMeteorologicalMapData().update(packet);
			if (packet.isOpenScreen()) {
				client.openMeteorologicalMapScreen();
			}
		});
	}

	public static void onLocalWeather(AeroLocalWeatherPacket packet, /*? fabric{ */ /*ClientPlayNetworking.Context *//*?} neoforge{ */ IPayloadContext /*?} */ context) {
		context./*? fabric{ */ /*client().execute *//*?} neoforge{ */ enqueueWork /*?} */
		(() -> getInstance().getLocalWeatherData().update(packet));
	}

	private void openMeteorologicalMapScreen() {
		net.minecraft.client.Minecraft minecraft = net.minecraft.client.Minecraft.getInstance();
		if (minecraft.screen instanceof MeteorologicalMapScreen) {
			return;
		}
		minecraft.setScreen(new MeteorologicalMapScreen(meteorologicalMapData));
	}

	// ====================== Static API ======================

	public static A4mcWorldRef worldRef(ClientLevel world) {
		if (world == null) {
			return null;
		}
		return A4mcWorldRef.client(fromMinecraftId(world.dimension().identifier()), world);
	}

	public static AeroWindSample sampleFlow(ClientLevel world, Vec3 position) {
		AeroClientMod active = instance;
		return sampleFlow(world, position, defaultSamplePolicy(active));
	}

	public static AeroWindSample sampleFlow(ClientLevel world, Vec3 position, SamplePolicy policy) {
		AeroClientMod active = instance;
		if (active == null || world == null || position == null) {
			return AeroWindSample.ZERO;
		}
		AeroWindSample sample = active.visualizer.sampleFlow(world.dimension().identifier(), position, policy);
		return active.applyCinematicSampleOverride(world, position, sample);
	}

	public static AeroWindSample sampleFlow(A4mcWorldRef world, A4mcVec3 position) {
		AeroClientMod active = instance;
		return sampleFlow(world, position, defaultSamplePolicy(active));
	}

	public static AeroWindSample sampleFlow(A4mcWorldRef world, A4mcVec3 position, SamplePolicy policy) {
		AeroClientMod active = instance;
		if (active == null || world == null || position == null) {
			return AeroWindSample.ZERO;
		}
		if (world.platformHandle() instanceof ClientLevel clientWorld) {
			return sampleFlow(clientWorld, toMinecraftVector(position), policy);
		}
		if (world.side() == A4mcWorldRef.Side.SERVER) {
			return AeroWindSample.ZERO;
		}
		return active.visualizer.sampleFlow(toMinecraftId(world.dimensionId()), toMinecraftVector(position), policy);
	}

	@Override
	public AeroWindSample sample(A4mcWorldRef world, A4mcVec3 position, SamplePolicy policy) {
		return policy == null ? sampleFlow(world, position) : sampleFlow(world, position, policy);
	}

	public static Vec3 sampleWind(ClientLevel world, Vec3 position) {
		return AeroMinecraftVectors.velocity(sampleFlow(world, position));
	}

	public static A4mcVec3 sampleWind(A4mcWorldRef world, A4mcVec3 position) {
		return sampleFlow(world, position).velocityVector();
	}

	private static SamplePolicy defaultSamplePolicy(AeroClientMod active) {
		return active != null && active.clientL2Solver.isExperimentalEnabled()
				? SamplePolicy.CLIENT_LOCAL_PREFERRED
				: SamplePolicy.SERVER_COARSE_ONLY;
	}

	private static Vec3 toMinecraftVector(A4mcVec3 position) {
		return new Vec3(position.x(), position.y(), position.z());
	}

	private static Identifier toMinecraftId(A4mcId id) {
		return Identifier.fromNamespaceAndPath(id.namespace(), id.path());
	}

	private static A4mcId fromMinecraftId(Identifier id) {
		return new A4mcId(id.getNamespace(), id.getPath());
	}

	private AeroWindSample applyCinematicSampleOverride(ClientLevel world, Vec3 position, AeroWindSample sample) {
		AeroWindSample base = sample == null ? AeroWindSample.ZERO : sample;
		float intensity = localWeatherData.stormVisualOverrideIntensity(world);
		if (intensity <= 0.05f) {
			return base;
		}
		Vec3 wind = ClientCinematicWind.stormWind(world, position, intensity, 2.40, 7.20);
		if (wind.lengthSqr() <= 0.01) {
			return base;
		}

		long time = world == null ? 0L : world.getGameTime();
		Vec3 basePosition = position == null ? Vec3.ZERO : position;
		double gustScale = 0.24
				+ intensity * 0.32
				+ Math.max(0.0, Math.sin(time * 0.11 + basePosition.x * 0.035 + basePosition.z * 0.021)) * 0.14;
		Vec3 gust = wind.scale(gustScale);
		float turbulence = Math.max(base.turbulenceIntensity(), 1.2f + intensity * 2.4f);
		float mixing = Math.max(base.ablMixingStrength(), 0.45f + intensity * 0.55f);
		return new AeroWindSample(
				(float) wind.x,
				(float) wind.y,
				(float) wind.z,
				base.pressure(),
				AeroWindSample.Level.L2,
				AeroWindSample.Authority.CLIENT_LOCAL,
				base.l1Epoch(),
				base.worldDeltaEpoch(),
				base.l2Epoch(),
				1.0f,
				base.temperatureKelvin(),
				base.humidity(),
				turbulence,
				(float) gust.x,
				(float) gust.y,
				(float) gust.z,
				base.windShearXPerBlock(),
				base.windShearZPerBlock(),
				base.ablStability(),
				mixing
		);
	}

	public static void notifyBlockStateChanged(ClientLevel world, BlockPos pos, BlockState oldState, BlockState newState) {
		AeroClientMod active = instance;
		if (active == null) {
			return;
		}
		active.clientL2Solver.onBlockStateChanged(world, pos, oldState, newState);
	}
}
