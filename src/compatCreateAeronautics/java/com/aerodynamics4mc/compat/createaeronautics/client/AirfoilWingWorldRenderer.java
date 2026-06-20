package com.aerodynamics4mc.compat.createaeronautics.client;

import com.aerodynamics4mc.api.AeroAirfoilDefinition;
import com.aerodynamics4mc.compat.createaeronautics.AirfoilWingBlock;
import com.aerodynamics4mc.compat.createaeronautics.AirfoilWingBlockEntity;
import com.aerodynamics4mc.compat.createaeronautics.CreateAeronauticsAirfoilLibrary;
import com.aerodynamics4mc.compat.createaeronautics.client.AirfoilMeshBuilder.AirfoilMesh;
import com.aerodynamics4mc.compat.createaeronautics.client.AirfoilMeshBuilder.Face;
import com.aerodynamics4mc.compat.createaeronautics.client.AirfoilMeshBuilder.Point3;
import com.aerodynamics4mc.compat.createaeronautics.client.AirfoilMeshBuilder.Segment;
import com.aerodynamics4mc.compat.createaeronautics.client.AirfoilWingVisualLibrary.AirfoilVisual;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.MultiBufferSource;
//? >=1.21.11 {
import net.minecraft.client.renderer.rendertype.RenderTypes;
//?} <1.21.11 {
/*import net.minecraft.client.renderer.RenderType;
*///?}
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;

import java.util.Map;

public final class AirfoilWingWorldRenderer {
	public static final AirfoilWingWorldRenderer INSTANCE = new AirfoilWingWorldRenderer();
	private static final int CHUNK_RADIUS = 4;
	private static final double MAX_RENDER_DISTANCE_SQUARED = 72.0 * 72.0;

	private AirfoilWingWorldRenderer() {
	}

	public void render(RenderLevelStageEvent event) {
		Minecraft client = Minecraft.getInstance();
		if (client.level == null || client.player == null) {
			return;
		}

		MultiBufferSource.BufferSource bufferSource = client.renderBuffers().bufferSource();
		//? >=1.21.11 {
		try (var ignored = client.collectPerTickGizmos()) {
			VertexConsumer surfaceBuffer = bufferSource.getBuffer(RenderTypes.debugQuads());
			renderWings(client, event.getPoseStack(), surfaceBuffer, RenderPass.SURFACE);
			bufferSource.endBatch(RenderTypes.debugQuads());
			VertexConsumer lineBuffer = bufferSource.getBuffer(RenderTypes.lines());
			renderWings(client, event.getPoseStack(), lineBuffer, RenderPass.LINES);
			bufferSource.endBatch(RenderTypes.lines());
		}
		//?} <1.21.11 {
		/*VertexConsumer surfaceBuffer = bufferSource.getBuffer(RenderType.debugQuads());
		renderWings(client, event.getPoseStack(), surfaceBuffer, RenderPass.SURFACE);
		bufferSource.endBatch(RenderType.debugQuads());
		VertexConsumer lineBuffer = bufferSource.getBuffer(RenderType.lines());
		renderWings(client, event.getPoseStack(), lineBuffer, RenderPass.LINES);
		bufferSource.endBatch(RenderType.lines());
		*///?}
	}

	private void renderWings(Minecraft client, PoseStack matrices, VertexConsumer buffer, RenderPass pass) {
		ClientLevel level = client.level;
		if (level == null || client.player == null) {
			return;
		}
		Vec3 cameraPos = client.gameRenderer.getMainCamera().position();
		BlockPos playerPos = client.player.blockPosition();
		int centerChunkX = playerPos.getX() >> 4;
		int centerChunkZ = playerPos.getZ() >> 4;

		for (int chunkX = centerChunkX - CHUNK_RADIUS; chunkX <= centerChunkX + CHUNK_RADIUS; chunkX++) {
			for (int chunkZ = centerChunkZ - CHUNK_RADIUS; chunkZ <= centerChunkZ + CHUNK_RADIUS; chunkZ++) {
				LevelChunk chunk = level.getChunkSource().getChunkNow(chunkX, chunkZ);
				if (chunk == null) {
					continue;
				}
				renderChunkWings(chunk, matrices, buffer, pass, cameraPos);
			}
		}
	}

	private void renderChunkWings(LevelChunk chunk, PoseStack matrices, VertexConsumer buffer, RenderPass pass, Vec3 cameraPos) {
		for (Map.Entry<BlockPos, BlockEntity> entry : chunk.getBlockEntities().entrySet()) {
			if (!(entry.getValue() instanceof AirfoilWingBlockEntity wing)) {
				continue;
			}
			BlockPos pos = entry.getKey();
			if (pos.getCenter().distanceToSqr(cameraPos) > MAX_RENDER_DISTANCE_SQUARED) {
				continue;
			}
			renderWing(wing, matrices, buffer, pass, cameraPos);
		}
	}

	private void renderWing(AirfoilWingBlockEntity wing, PoseStack matrices, VertexConsumer buffer, RenderPass pass, Vec3 cameraPos) {
		AeroAirfoilDefinition definition = CreateAeronauticsAirfoilLibrary.definitionOrDefault(wing.airfoilId());
		AirfoilVisual visual = AirfoilWingVisualLibrary.INSTANCE.find(definition.id()).orElse(AirfoilVisual.DEFAULT);
		BlockState state = wing.getBlockState();
		Direction facing = state.hasProperty(AirfoilWingBlock.FACING)
				? state.getValue(AirfoilWingBlock.FACING)
				: Direction.NORTH;
		AirfoilMesh mesh = AirfoilMeshBuilder.buildBlock(definition, visual, facing);
		if (mesh.isEmpty()) {
			return;
		}
		BlockPos pos = wing.getBlockPos();

		matrices.pushPose();
		matrices.translate(pos.getX() - cameraPos.x, pos.getY() - cameraPos.y, pos.getZ() - cameraPos.z);
		PoseStack.Pose pose = matrices.last();
		Matrix4f matrix = pose.pose();

		if (pass == RenderPass.SURFACE) {
			drawFaces(buffer, matrix, mesh.faces(), visual);
		} else {
			drawSegments(buffer, pose, matrix, mesh.leftLoop(), visual.backColor(), 1.7f);
			drawMeshSegments(buffer, pose, matrix, mesh.spanConnectors(), visual);
			drawSegments(buffer, pose, matrix, mesh.chordGuides(), visual.chordColor(), 2.0f);
			drawSegments(buffer, pose, matrix, mesh.rightLoop(), visual.frontColor(), 1.7f);
		}
		matrices.popPose();
	}

	private void drawFaces(VertexConsumer buffer, Matrix4f matrix, Iterable<Face> faces, AirfoilVisual visual) {
		int skinColor = surfaceColor(visual);
		for (Face face : faces) {
			int color = switch (face.kind()) {
				case SKIN -> skinColor;
				case LEFT_CAP -> translucent(visual.backColor(), 0x96);
				case RIGHT_CAP -> translucent(visual.frontColor(), 0x96);
			};
			surfaceVertex(buffer, matrix, face.a(), color);
			surfaceVertex(buffer, matrix, face.b(), color);
			surfaceVertex(buffer, matrix, face.c(), color);
			surfaceVertex(buffer, matrix, face.d(), color);
		}
	}

	private void drawMeshSegments(
			VertexConsumer buffer,
			PoseStack.Pose pose,
			Matrix4f matrix,
			Iterable<Segment> segments,
			AirfoilVisual visual
	) {
		for (Segment segment : segments) {
			drawLine(buffer, pose, matrix, segment.from(), segment.to(), color(segment, visual), lineWidth(segment));
		}
	}

	private void drawSegments(VertexConsumer buffer, PoseStack.Pose pose, Matrix4f matrix, Iterable<Segment> segments, int color, float lineWidth) {
		for (Segment segment : segments) {
			drawLine(buffer, pose, matrix, segment.from(), segment.to(), color, lineWidth);
		}
	}

	private int color(Segment segment, AirfoilVisual visual) {
		return switch (segment.kind()) {
			case SPAN_RIB -> visual.connectorColor();
			case SPAN_ACCENT -> visual.frontColor();
			case CHORD_GUIDE -> visual.chordColor();
			case SECTION -> visual.frontColor();
		};
	}

	private float lineWidth(Segment segment) {
		return switch (segment.kind()) {
			case SPAN_RIB -> 1.2f;
			case SPAN_ACCENT -> 1.8f;
			case CHORD_GUIDE -> 2.0f;
			case SECTION -> 1.7f;
		};
	}

	private void drawLine(
			VertexConsumer buffer,
			PoseStack.Pose pose,
			Matrix4f matrix,
			Point3 from,
			Point3 to,
			int color,
			float lineWidth
	) {
		float normalX = to.x() - from.x();
		float normalY = to.y() - from.y();
		float normalZ = to.z() - from.z();
		float length = (float) Math.sqrt(normalX * normalX + normalY * normalY + normalZ * normalZ);
		if (length > 1.0e-5f) {
			normalX /= length;
			normalY /= length;
			normalZ /= length;
		} else {
			normalY = 1.0f;
		}
		vertex(buffer, pose, matrix, from, color, normalX, normalY, normalZ, lineWidth);
		vertex(buffer, pose, matrix, to, color, normalX, normalY, normalZ, lineWidth);
	}

	private void vertex(
			VertexConsumer buffer,
			PoseStack.Pose pose,
			Matrix4f matrix,
			Point3 point,
			int color,
			float normalX,
			float normalY,
			float normalZ,
			float lineWidth
	) {
		buffer.addVertex(matrix, point.x(), point.y(), point.z())
				.setColor((color >> 16) & 0xFF, (color >> 8) & 0xFF, color & 0xFF, (color >> 24) & 0xFF)
				.setNormal(pose, normalX, normalY, normalZ)
				/*? if >=1.21.11 {*/.setLineWidth(lineWidth)/*?}*/;
	}

	private void surfaceVertex(VertexConsumer buffer, Matrix4f matrix, Point3 point, int color) {
		buffer.addVertex(matrix, point.x(), point.y(), point.z())
				.setColor((color >> 16) & 0xFF, (color >> 8) & 0xFF, color & 0xFF, (color >> 24) & 0xFF);
	}

	private int surfaceColor(AirfoilVisual visual) {
		int front = visual.frontColor();
		int back = visual.backColor();
		int alpha = Math.max(0x55, Math.min(0xB0, (((front >>> 24) & 0xFF) + ((back >>> 24) & 0xFF)) / 3));
		int red = (((front >> 16) & 0xFF) + ((back >> 16) & 0xFF)) / 2;
		int green = (((front >> 8) & 0xFF) + ((back >> 8) & 0xFF)) / 2;
		int blue = ((front & 0xFF) + (back & 0xFF)) / 2;
		return alpha << 24 | red << 16 | green << 8 | blue;
	}

	private int translucent(int color, int fallbackAlpha) {
		int alpha = (color >>> 24) & 0xFF;
		if (alpha >= 0xF0) {
			alpha = fallbackAlpha;
		}
		return alpha << 24 | color & 0x00FFFFFF;
	}

	private enum RenderPass {
		SURFACE,
		LINES
	}
}
