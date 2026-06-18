package com.aerodynamics4mc.compat.createaeronautics.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.neoforged.neoforge.client.event.RenderGuiEvent;

import java.util.List;
import java.util.Locale;

public final class CreateAeronauticsPolarOverlay {
	private static final int PANEL_WIDTH = 248;
	private static final int PANEL_HEIGHT = 184;
	private static final int PANEL_MARGIN = 8;
	private static final int PLOT_LEFT_PAD = 30;
	private static final int PLOT_TOP_PAD = 30;
	private static final int PLOT_RIGHT_PAD = 12;
	private static final int PLOT_HEIGHT = 82;

	private static final int BACKGROUND = 0xB0101418;
	private static final int BORDER = 0xFF64748B;
	private static final int GRID = 0x66475569;
	private static final int AXIS = 0xAA94A3B8;
	private static final int TEXT = 0xFFE2E8F0;
	private static final int MUTED_TEXT = 0xFF94A3B8;
	private static final int CURVE = 0xFF38BDF8;
	private static final int CURRENT = 0xFFFACC15;
	private static final int CURRENT_DARK = 0xFFCA8A04;
	private static final int PEAK = 0xFFFF7A18;
	private static final int STATE_OK = 0xFF86EFAC;
	private static final int STATE_WARN = 0xFFFACC15;
	private static final int STATE_ALERT = 0xFFF87171;

	private static final CreateAeronauticsClientPolarSampler SAMPLER = new CreateAeronauticsClientPolarSampler();
	private static boolean enabled;

	private CreateAeronauticsPolarOverlay() {
	}

	public static boolean enabled() {
		return enabled;
	}

	public static void setEnabled(boolean enabled) {
		CreateAeronauticsPolarOverlay.enabled = enabled;
		if (!enabled) {
			SAMPLER.clear();
		}
	}

	public static String status() {
		return enabled ? "enabled" : "disabled";
	}

	public static void render(RenderGuiEvent.Post event) {
		if (!enabled) {
			return;
		}
		Minecraft client = Minecraft.getInstance();
		if (client.level == null || client.player == null) {
			return;
		}

		GuiGraphics graphics = event.getGuiGraphics();
		Font font = client.font;
		int panelWidth = Math.min(PANEL_WIDTH, Math.max(156, graphics.guiWidth() - PANEL_MARGIN * 2));
		int x = Math.max(PANEL_MARGIN, graphics.guiWidth() - panelWidth - PANEL_MARGIN);
		int y = PANEL_MARGIN;

		CreateAeronauticsClientPolarSampler.Snapshot snapshot = SAMPLER.sample(client);
		if (!snapshot.available() || snapshot.points().isEmpty()) {
			drawUnavailable(graphics, font, x, y, panelWidth, snapshot.status());
			return;
		}

		drawPanel(graphics, x, y, panelWidth, PANEL_HEIGHT);
		graphics.drawString(font, "A4MC Flight Test", x + 8, y + 7, TEXT, false);
		String state = snapshot.flightState().isBlank() ? snapshot.status() : snapshot.flightState();
		graphics.drawString(font, state, x + panelWidth - 8 - font.width(state), y + 7, stateColor(state), false);

		int plotX = x + PLOT_LEFT_PAD;
		int plotY = y + PLOT_TOP_PAD;
		int plotWidth = Math.max(82, panelWidth - PLOT_LEFT_PAD - PLOT_RIGHT_PAD);
		PlotScale scale = scaleFor(snapshot);
		drawPlot(graphics, plotX, plotY, plotWidth, PLOT_HEIGHT, scale, snapshot);

		int textY = plotY + PLOT_HEIGHT + 7;
		graphics.drawString(
				font,
				String.format(Locale.ROOT, "IAS %.2fm/s   AoA %+5.1fdeg", snapshot.relativeWindSpeedMetersPerSecond(), snapshot.angleOfAttackDegrees()),
				x + 8,
				textY,
				TEXT,
				false
		);
		graphics.drawString(
				font,
				String.format(Locale.ROOT, "Cl %.3f  Cd %.3f  L/D %s", snapshot.liftCoefficient(), snapshot.dragCoefficient(), formatRatio(snapshot.liftDragRatio())),
				x + 8,
				textY + 11,
				TEXT,
				false
		);
		graphics.drawString(
				font,
				String.format(Locale.ROOT, "Lift %.1fN  Drag %.1fN", snapshot.liftNewtons(), snapshot.dragNewtons()),
				x + 8,
				textY + 22,
				MUTED_TEXT,
				false
		);
		graphics.drawString(
				font,
				String.format(Locale.ROOT, "Cm %.3f  M %.1fNm  margin %s",
						snapshot.momentCoefficient(),
						snapshot.pitchingMomentNewtonMeters(),
						formatDegrees(snapshot.stallMarginDegrees())),
				x + 8,
				textY + 33,
				MUTED_TEXT,
				false
		);
	}

	private static void drawUnavailable(GuiGraphics graphics, Font font, int x, int y, int width, String status) {
		int height = 34;
		drawPanel(graphics, x, y, width, height);
		graphics.drawString(font, "A4MC Airfoil Polar", x + 8, y + 7, TEXT, false);
		graphics.drawString(font, status == null || status.isBlank() ? "no sample" : status, x + 8, y + 19, MUTED_TEXT, false);
	}

	private static void drawPanel(GuiGraphics graphics, int x, int y, int width, int height) {
		graphics.fill(x, y, x + width, y + height, BACKGROUND);
		graphics.hLine(x, x + width - 1, y, BORDER);
		graphics.hLine(x, x + width - 1, y + height - 1, BORDER);
		graphics.vLine(x, y, y + height - 1, BORDER);
		graphics.vLine(x + width - 1, y, y + height - 1, BORDER);
	}

	private static void drawPlot(
			GuiGraphics graphics,
			int x,
			int y,
			int width,
			int height,
			PlotScale scale,
			CreateAeronauticsClientPolarSampler.Snapshot snapshot
	) {
		graphics.fill(x, y, x + width, y + height, 0x551E293B);
		graphics.hLine(x, x + width - 1, y, GRID);
		graphics.hLine(x, x + width - 1, y + height / 2, GRID);
		graphics.hLine(x, x + width - 1, y + height - 1, GRID);
		graphics.vLine(x, y, y + height - 1, GRID);
		graphics.vLine(x + width / 2, y, y + height - 1, GRID);
		graphics.vLine(x + width - 1, y, y + height - 1, GRID);

		if (scale.minCl() < 0.0 && scale.maxCl() > 0.0) {
			graphics.hLine(x, x + width - 1, mapY(0.0, scale, y, height), AXIS);
		}
		if (scale.minCd() <= 0.0 && scale.maxCd() >= 0.0) {
			graphics.vLine(mapX(0.0, scale, x, width), y, y + height - 1, AXIS);
		}

		List<CreateAeronauticsClientPolarSampler.PolarPoint> points = snapshot.points();
		for (int i = 1; i < points.size(); i++) {
			CreateAeronauticsClientPolarSampler.PolarPoint a = points.get(i - 1);
			CreateAeronauticsClientPolarSampler.PolarPoint b = points.get(i);
			if (isFinite(a) && isFinite(b)) {
				drawLine(
						graphics,
						mapX(a.dragCoefficient(), scale, x, width),
						mapY(a.liftCoefficient(), scale, y, height),
						mapX(b.dragCoefficient(), scale, x, width),
						mapY(b.liftCoefficient(), scale, y, height),
						CURVE
				);
			}
		}

		drawPeakMarker(graphics, points, snapshot.positivePeakAngleDegrees(), scale, x, y, width, height);
		drawPeakMarker(graphics, points, snapshot.negativePeakAngleDegrees(), scale, x, y, width, height);

		if (snapshot.hasCurrentSample()) {
			int currentX = mapX(snapshot.dragCoefficient(), scale, x, width);
			int currentY = mapY(snapshot.liftCoefficient(), scale, y, height);
			graphics.fill(currentX - 2, currentY - 2, currentX + 3, currentY + 3, CURRENT);
			graphics.hLine(currentX - 4, currentX + 4, currentY, CURRENT_DARK);
			graphics.vLine(currentX, currentY - 4, currentY + 4, CURRENT_DARK);
		}
	}

	private static void drawPeakMarker(
			GuiGraphics graphics,
			List<CreateAeronauticsClientPolarSampler.PolarPoint> points,
			double angle,
			PlotScale scale,
			int x,
			int y,
			int width,
			int height
	) {
		if (!Double.isFinite(angle)) {
			return;
		}
		CreateAeronauticsClientPolarSampler.PolarPoint point = closestAngle(points, angle);
		if (point == null) {
			return;
		}
		int peakX = mapX(point.dragCoefficient(), scale, x, width);
		int peakY = mapY(point.liftCoefficient(), scale, y, height);
		graphics.fill(peakX - 1, peakY - 1, peakX + 2, peakY + 2, PEAK);
	}

	private static CreateAeronauticsClientPolarSampler.PolarPoint closestAngle(
			List<CreateAeronauticsClientPolarSampler.PolarPoint> points,
			double angle
	) {
		CreateAeronauticsClientPolarSampler.PolarPoint best = null;
		double bestDistance = Double.POSITIVE_INFINITY;
		for (CreateAeronauticsClientPolarSampler.PolarPoint point : points) {
			double distance = Math.abs(point.angleOfAttackDegrees() - angle);
			if (distance < bestDistance) {
				best = point;
				bestDistance = distance;
			}
		}
		return best;
	}

	private static PlotScale scaleFor(CreateAeronauticsClientPolarSampler.Snapshot snapshot) {
		double minCd = 0.0;
		double maxCd = 0.08;
		double minCl = -0.25;
		double maxCl = 0.25;
		for (CreateAeronauticsClientPolarSampler.PolarPoint point : snapshot.points()) {
			if (!isFinite(point)) {
				continue;
			}
			minCd = Math.min(minCd, point.dragCoefficient());
			maxCd = Math.max(maxCd, point.dragCoefficient());
			minCl = Math.min(minCl, point.liftCoefficient());
			maxCl = Math.max(maxCl, point.liftCoefficient());
		}
		if (snapshot.hasCurrentSample()) {
			minCd = Math.min(minCd, snapshot.dragCoefficient());
			maxCd = Math.max(maxCd, snapshot.dragCoefficient());
			minCl = Math.min(minCl, snapshot.liftCoefficient());
			maxCl = Math.max(maxCl, snapshot.liftCoefficient());
		}

		double cdPad = Math.max(0.01, (maxCd - minCd) * 0.12);
		double clPad = Math.max(0.10, (maxCl - minCl) * 0.12);
		return new PlotScale(
				Math.max(0.0, minCd - cdPad),
				maxCd + cdPad,
				minCl - clPad,
				maxCl + clPad
		);
	}

	private static int mapX(double cd, PlotScale scale, int x, int width) {
		return x + clampInt((int) Math.round((cd - scale.minCd()) / (scale.maxCd() - scale.minCd()) * (width - 1)), 0, width - 1);
	}

	private static int mapY(double cl, PlotScale scale, int y, int height) {
		return y + height - 1 - clampInt((int) Math.round((cl - scale.minCl()) / (scale.maxCl() - scale.minCl()) * (height - 1)), 0, height - 1);
	}

	private static void drawLine(GuiGraphics graphics, int x0, int y0, int x1, int y1, int color) {
		int dx = Math.abs(x1 - x0);
		int sx = x0 < x1 ? 1 : -1;
		int dy = -Math.abs(y1 - y0);
		int sy = y0 < y1 ? 1 : -1;
		int error = dx + dy;
		while (true) {
			graphics.fill(x0, y0, x0 + 1, y0 + 1, color);
			if (x0 == x1 && y0 == y1) {
				break;
			}
			int twiceError = error * 2;
			if (twiceError >= dy) {
				error += dy;
				x0 += sx;
			}
			if (twiceError <= dx) {
				error += dx;
				y0 += sy;
			}
		}
	}

	private static boolean isFinite(CreateAeronauticsClientPolarSampler.PolarPoint point) {
		return point != null
				&& Double.isFinite(point.liftCoefficient())
				&& Double.isFinite(point.dragCoefficient());
	}

	private static int clampInt(int value, int min, int max) {
		return Math.max(min, Math.min(max, value));
	}

	private static String formatRatio(double value) {
		return Double.isFinite(value) ? String.format(Locale.ROOT, "%.2f", value) : "--";
	}

	private static String formatDegrees(double value) {
		return Double.isFinite(value) ? String.format(Locale.ROOT, "%+.1fdeg", value) : "--";
	}

	private static int stateColor(String state) {
		if (state == null) {
			return MUTED_TEXT;
		}
		return switch (state) {
			case "CLEAN" -> STATE_OK;
			case "HIGH AOA", "STALL EDGE" -> STATE_WARN;
			case "POST PEAK", "OUT OF RANGE", "SPEED LIMIT" -> STATE_ALERT;
			default -> MUTED_TEXT;
		};
	}

	private record PlotScale(double minCd, double maxCd, double minCl, double maxCl) {
	}
}
