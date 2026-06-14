package com.aerodynamics4mc.client;

import com.aerodynamics4mc.network.packet.AeroMesoscaleMapPacket;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.Locale;

public final class MeteorologicalMapScreen extends Screen {
	private static final int BACKGROUND = 0xF0101418;
	private static final int PANEL_BACKGROUND = 0xD81B2328;
	private static final int PANEL_BORDER = 0xFF52606A;
	private static final int TEXT = 0xFFE6EDF1;
	private static final int MUTED_TEXT = 0xFF9AA8AF;
	private static final int STRONG_WIND = 0xE8FFFFFF;
	private static final int PLAYER_MARKER = 0xFFFFF2A6;

	private final ClientMeteorologicalMapData data;
	private int refreshTicks;

	public MeteorologicalMapScreen(ClientMeteorologicalMapData data) {
		super(Component.translatable("screen.aerodynamics4mc.meteorological_map.title"));
		this.data = data;
	}

	@Override
	public void tick() {
		AeroMesoscaleMapPacket packet = data.latest();
		if (--refreshTicks <= 0) {
			data.requestRefresh(packet != null ? packet.getLayer() : 0);
			refreshTicks = 20;
		}
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
		graphics.fill(0, 0, width, height, BACKGROUND);
		graphics.drawString(font, title, 18, 12, TEXT, false);

		AeroMesoscaleMapPacket packet = data.latest();
		if (packet == null) {
			graphics.drawString(
					font,
					Component.translatable("screen.aerodynamics4mc.meteorological_map.no_data"),
					18,
					32,
					MUTED_TEXT,
					false
			);
			return;
		}

		ClientMeteorologicalMapData.Metrics metrics = data.metrics(packet);
		int layerMinY = packet.getVerticalBaseY() + packet.getLayer() * packet.getLayerHeightBlocks();
		int layerMaxY = layerMinY + packet.getLayerHeightBlocks();
		Component stats = Component.translatable(
				"screen.aerodynamics4mc.meteorological_map.stats",
				Integer.toString(packet.getLayer() + 1),
				Integer.toString(packet.getActiveLayers()),
				Integer.toString(layerMinY),
				Integer.toString(layerMaxY),
				Integer.toString(packet.getCellSizeBlocks()),
				format(metrics.maxSpeed()),
				format(metrics.meanSpeed()),
				Integer.toString(metrics.strongCellCount())
		);
		graphics.drawString(font, stats, 18, 26, MUTED_TEXT, false);
		graphics.drawString(font, Component.translatable("screen.aerodynamics4mc.meteorological_map.legend"), 18, 38, MUTED_TEXT, false);

		int margin = 18;
		int gap = 10;
		int top = 56;
		int columns = width >= 560 ? 2 : 1;
		int rows = columns == 2 ? 2 : 4;
		int panelWidth = Math.max(120, (width - margin * 2 - gap * (columns - 1)) / columns);
		int panelHeight = Math.max(92, (height - top - margin - gap * (rows - 1)) / rows);

		drawPanel(graphics, packet, margin, top, panelWidth, panelHeight, Field.WIND);
		drawPanel(graphics, packet, margin + (columns == 2 ? panelWidth + gap : 0), top + (columns == 1 ? panelHeight + gap : 0), panelWidth, panelHeight, Field.LIFT);
		drawPanel(graphics, packet, margin, top + (columns == 2 ? panelHeight + gap : (panelHeight + gap) * 2), panelWidth, panelHeight, Field.HUMIDITY);
		drawPanel(graphics, packet, margin + (columns == 2 ? panelWidth + gap : 0), top + (columns == 2 ? panelHeight + gap : (panelHeight + gap) * 3), panelWidth, panelHeight, Field.SURFACE);
	}

	private void drawPanel(GuiGraphics graphics, AeroMesoscaleMapPacket packet, int x, int y, int width, int height, Field field) {
		graphics.fill(x, y, x + width, y + height, PANEL_BACKGROUND);
		graphics.hLine(x, x + width - 1, y, PANEL_BORDER);
		graphics.hLine(x, x + width - 1, y + height - 1, PANEL_BORDER);
		graphics.vLine(x, y, y + height - 1, PANEL_BORDER);
		graphics.vLine(x + width - 1, y, y + height - 1, PANEL_BORDER);
		graphics.drawString(font, field.title, x + 8, y + 7, TEXT, false);

		int mapSize = Math.max(48, Math.min(width - 16, height - 30));
		int mapX = x + 8;
		int mapY = y + height - mapSize - 8;
		drawField(graphics, packet, mapX, mapY, mapSize, field);
	}

	private void drawField(GuiGraphics graphics, AeroMesoscaleMapPacket packet, int x, int y, int size, Field field) {
		int grid = packet.getGridWidth();
		if (grid <= 0) {
			return;
		}
		for (int localX = 0; localX < grid; localX++) {
			for (int localZ = 0; localZ < grid; localZ++) {
				int cellX0 = x + localX * size / grid;
				int cellX1 = x + (localX + 1) * size / grid;
				int cellY0 = y + localZ * size / grid;
				int cellY1 = y + (localZ + 1) * size / grid;
				if (cellX1 <= cellX0) {
					cellX1 = cellX0 + 1;
				}
				if (cellY1 <= cellY0) {
					cellY1 = cellY0 + 1;
				}
				graphics.fill(cellX0, cellY0, cellX1, cellY1, colorFor(packet, localX, localZ, field));
				if (field == Field.WIND && data.windSpeed(packet, localX, localZ) >= 3.0f && cellX1 - cellX0 >= 3 && cellY1 - cellY0 >= 3) {
					graphics.hLine(cellX0, cellX1 - 1, cellY0, STRONG_WIND);
					graphics.hLine(cellX0, cellX1 - 1, cellY1 - 1, STRONG_WIND);
					graphics.vLine(cellX0, cellY0, cellY1 - 1, STRONG_WIND);
					graphics.vLine(cellX1 - 1, cellY0, cellY1 - 1, STRONG_WIND);
				}
			}
		}
		drawWindVectors(graphics, packet, x, y, size);
		drawPlayerMarker(graphics, packet, x, y, size);
		graphics.hLine(x, x + size, y, PANEL_BORDER);
		graphics.hLine(x, x + size, y + size, PANEL_BORDER);
		graphics.vLine(x, y, y + size, PANEL_BORDER);
		graphics.vLine(x + size, y, y + size, PANEL_BORDER);
	}

	private void drawWindVectors(GuiGraphics graphics, AeroMesoscaleMapPacket packet, int x, int y, int size) {
		int grid = packet.getGridWidth();
		int stride = Math.max(4, grid / 7);
		for (int localX = stride / 2; localX < grid; localX += stride) {
			for (int localZ = stride / 2; localZ < grid; localZ += stride) {
				float windX = data.windX(packet, localX, localZ);
				float windZ = data.windZ(packet, localX, localZ);
				float speed = (float) Math.sqrt(windX * windX + windZ * windZ);
				if (speed < 0.2f) {
					continue;
				}
				int centerX = x + (localX * size + size / 2) / grid;
				int centerY = y + (localZ * size + size / 2) / grid;
				int length = Math.max(3, size / 28);
				int dx = Math.round(windX / speed * length);
				int dz = Math.round(windZ / speed * length);
				drawLine(graphics, centerX - dx, centerY - dz, centerX + dx, centerY + dz, 0xD8F4FAFF);
			}
		}
	}

	private void drawPlayerMarker(GuiGraphics graphics, AeroMesoscaleMapPacket packet, int x, int y, int size) {
		int minCellX = packet.getCenterCellX() - packet.getRadiusCells();
		int minCellZ = packet.getCenterCellZ() - packet.getRadiusCells();
		int localX = packet.getPlayerCellX() - minCellX;
		int localZ = packet.getPlayerCellZ() - minCellZ;
		if (localX < 0 || localZ < 0 || localX >= packet.getGridWidth() || localZ >= packet.getGridWidth()) {
			return;
		}
		int markerX = x + (localX * size + size / 2) / packet.getGridWidth();
		int markerY = y + (localZ * size + size / 2) / packet.getGridWidth();
		graphics.hLine(markerX - 4, markerX + 4, markerY, PLAYER_MARKER);
		graphics.vLine(markerX, markerY - 4, markerY + 4, PLAYER_MARKER);
	}

	private void drawLine(GuiGraphics graphics, int x0, int y0, int x1, int y1, int color) {
		int dx = Math.abs(x1 - x0);
		int dy = Math.abs(y1 - y0);
		int sx = x0 < x1 ? 1 : -1;
		int sy = y0 < y1 ? 1 : -1;
		int err = dx - dy;
		while (true) {
			graphics.fill(x0, y0, x0 + 1, y0 + 1, color);
			if (x0 == x1 && y0 == y1) {
				return;
			}
			int e2 = err * 2;
			if (e2 > -dy) {
				err -= dy;
				x0 += sx;
			}
			if (e2 < dx) {
				err += dx;
				y0 += sy;
			}
		}
	}

	private int colorFor(AeroMesoscaleMapPacket packet, int localX, int localZ, Field field) {
		return switch (field) {
			case WIND -> speedColor(data.windSpeed(packet, localX, localZ));
			case LIFT -> divergingColor(data.lift(packet, localX, localZ), AeroMesoscaleMapPacket.DIAGNOSTIC_RANGE);
			case HUMIDITY -> humidityColor(data.humidity(packet, localX, localZ));
			case SURFACE -> surfaceColor(data.surfaceClass(packet, localX, localZ), data.terrainSolid(packet, localX, localZ));
		};
	}

	private int speedColor(float speed) {
		float t = clamp(speed / 8.0f, 0.0f, 1.0f);
		if (t < 0.35f) {
			return rgb(lerp(29, 38, t / 0.35f), lerp(88, 181, t / 0.35f), lerp(173, 201, t / 0.35f));
		}
		if (t < 0.70f) {
			float u = (t - 0.35f) / 0.35f;
			return rgb(lerp(38, 236, u), lerp(181, 205, u), lerp(201, 76, u));
		}
		float u = (t - 0.70f) / 0.30f;
		return rgb(lerp(236, 209, u), lerp(205, 65, u), lerp(76, 54, u));
	}

	private int divergingColor(float value, float range) {
		float t = clamp(value / range, -1.0f, 1.0f);
		if (t < 0.0f) {
			float u = -t;
			return rgb(lerp(43, 28, u), lerp(120, 74, u), lerp(180, 132, u));
		}
		return rgb(lerp(70, 224, t), lerp(92, 147, t), lerp(92, 54, t));
	}

	private int humidityColor(float humidity) {
		float t = clamp(humidity, 0.0f, 1.0f);
		return rgb(lerp(94, 36, t), lerp(97, 158, t), lerp(92, 202, t));
	}

	private int surfaceColor(int surfaceClass, float solid) {
		int color = switch (surfaceClass) {
			case 1 -> rgb(112, 116, 113);
			case 2 -> rgb(126, 92, 57);
			case 3 -> rgb(58, 132, 66);
			case 4 -> rgb(216, 226, 228);
			case 5 -> rgb(54, 112, 190);
			case 6 -> rgb(206, 74, 42);
			default -> rgb(45, 52, 57);
		};
		if (solid > 0.5f) {
			return mix(color, rgb(18, 22, 24), 0.35f);
		}
		return color;
	}

	private int mix(int a, int b, float t) {
		t = clamp(t, 0.0f, 1.0f);
		int ar = (a >> 16) & 255;
		int ag = (a >> 8) & 255;
		int ab = a & 255;
		int br = (b >> 16) & 255;
		int bg = (b >> 8) & 255;
		int bb = b & 255;
		return rgb(lerp(ar, br, t), lerp(ag, bg, t), lerp(ab, bb, t));
	}

	private int rgb(int r, int g, int b) {
		return 0xFF000000 | (clampInt(r, 0, 255) << 16) | (clampInt(g, 0, 255) << 8) | clampInt(b, 0, 255);
	}

	private int lerp(int from, int to, float t) {
		return Math.round(from + (to - from) * clamp(t, 0.0f, 1.0f));
	}

	private int clampInt(int value, int min, int max) {
		return Math.max(min, Math.min(max, value));
	}

	private float clamp(float value, float min, float max) {
		if (!Float.isFinite(value)) {
			return min;
		}
		return Math.max(min, Math.min(max, value));
	}

	private static String format(float value) {
		return String.format(Locale.ROOT, "%.2f", value);
	}

	private enum Field {
		WIND(Component.translatable("screen.aerodynamics4mc.meteorological_map.wind")),
		LIFT(Component.translatable("screen.aerodynamics4mc.meteorological_map.lift")),
		HUMIDITY(Component.translatable("screen.aerodynamics4mc.meteorological_map.humidity")),
		SURFACE(Component.translatable("screen.aerodynamics4mc.meteorological_map.surface"));

		private final Component title;

		Field(Component title) {
			this.title = title;
		}
	}
}
