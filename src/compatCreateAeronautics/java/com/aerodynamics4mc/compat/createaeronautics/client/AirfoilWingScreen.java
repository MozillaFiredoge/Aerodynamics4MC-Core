package com.aerodynamics4mc.compat.createaeronautics.client;

import com.aerodynamics4mc.api.A4mcId;
import com.aerodynamics4mc.api.AeroAirfoilDefinition;
import com.aerodynamics4mc.api.AeroAirfoilProfile;
import com.aerodynamics4mc.compat.createaeronautics.AirfoilWingBlockItem;
import com.aerodynamics4mc.compat.createaeronautics.CreateAeronauticsAirfoilItemActionPacket;
import com.aerodynamics4mc.compat.createaeronautics.CreateAeronauticsAirfoilLibrary;
import com.aerodynamics4mc.compat.createaeronautics.client.AirfoilMeshBuilder.AirfoilMesh;
import com.aerodynamics4mc.compat.createaeronautics.client.AirfoilMeshBuilder.Face;
import com.aerodynamics4mc.compat.createaeronautics.client.AirfoilMeshBuilder.Point3;
import com.aerodynamics4mc.compat.createaeronautics.client.AirfoilMeshBuilder.Segment;
import com.aerodynamics4mc.compat.createaeronautics.client.AirfoilWingVisualLibrary.AirfoilVisual;
import com.aerodynamics4mc.network.ClientServerboundPacketSender;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.screens.Screen;
//? >=1.21.11 {
import net.minecraft.client.input.MouseButtonEvent;
//?}
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public final class AirfoilWingScreen extends Screen {
	private static final int BACKGROUND = 0xF0101418;
	private static final int PANEL = 0xD81B2328;
	private static final int PANEL_ALT = 0xB61F2930;
	private static final int BORDER = 0xFF52606A;
	private static final int SELECTED = 0xFF356A78;
	private static final int TEXT = 0xFFE6EDF1;
	private static final int MUTED_TEXT = 0xFF9AA8AF;
	private static final int ERROR_TEXT = 0xFFFF9C8F;
	private static final int ROW_HEIGHT = 22;
	private static final int VISIBLE_ROWS = 8;
	private static final int PREVIEW_HEIGHT = 94;

	private final InteractionHand hand;
	private int selectedIndex;
	private int panelX;
	private int panelY;
	private int panelWidth;
	private int panelHeight;
	private int listX;
	private int listY;
	private int listWidth;
	private int previewX;
	private int previewY;
	private int previewWidth;
	private int previewHeight;
	private boolean previewDragging;
	private double previewYaw = -0.58;
	private double previewPitch = 0.34;
	private double previewZoom = 1.0;

	public AirfoilWingScreen(InteractionHand hand) {
		super(Component.translatable("screen.aerodynamics4mc_compat_create_aeronautics.airfoil_wing.title"));
		this.hand = hand;
	}

	public static InteractionResult open(Player player, InteractionHand hand, ItemStack stack) {
		Minecraft.getInstance().setScreen(new AirfoilWingScreen(hand));
		return InteractionResult.SUCCESS;
	}

	@Override
	protected void init() {
		panelWidth = Math.min(440, Math.max(320, width - 40));
		panelHeight = Math.min(292, Math.max(250, height - 36));
		panelX = (width - panelWidth) / 2;
		panelY = Math.max(16, (height - panelHeight) / 2);
		listX = panelX + 12;
		listY = panelY + 34;
		listWidth = Math.min(190, panelWidth / 2 - 18);
		previewX = listX + listWidth + 12;
		previewY = listY;
		previewWidth = panelX + panelWidth - previewX - 12;
		previewHeight = PREVIEW_HEIGHT;
		selectCurrentStackAirfoil();

		int buttonY = panelY + panelHeight - 28;
		int buttonWidth = Math.max(86, (panelWidth - 30) / 2);
		addRenderableWidget(Button.builder(
				Component.translatable("screen.aerodynamics4mc_compat_create_aeronautics.airfoil_wing.use"),
				button -> useSelected()
		).bounds(panelX + 12, buttonY, buttonWidth, 20).build());
		addRenderableWidget(Button.builder(
				Component.translatable("screen.aerodynamics4mc_compat_create_aeronautics.airfoil_wing.export"),
				button -> exportSelected()
		).bounds(panelX + 18 + buttonWidth, buttonY, buttonWidth, 20).build());
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
		graphics.fill(0, 0, width, height, BACKGROUND);
		drawPanel(graphics);
		drawAirfoilList(graphics, mouseX, mouseY);
		drawPreview(graphics);
		drawSelectedDetails(graphics);
		renderWidgets(graphics, mouseX, mouseY, partialTick);
	}

	//? >=1.21.11 {
	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (event.button() == 0 && isPreviewMouse(event.x(), event.y())) {
			previewDragging = true;
			return true;
		}
		if (selectRowAt(event.x(), event.y())) {
			return true;
		}
		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		if (previewDragging && event.button() == 0) {
			previewDragging = false;
			return true;
		}
		return super.mouseReleased(event);
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
		if (previewDragging && event.button() == 0) {
			rotatePreview(dragX, dragY);
			return true;
		}
		return super.mouseDragged(event, dragX, dragY);
	}
	//?} <1.21.11 {
	/*@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		if (button == 0 && isPreviewMouse(mouseX, mouseY)) {
			previewDragging = true;
			return true;
		}
		if (selectRowAt(mouseX, mouseY)) {
			return true;
		}
		return super.mouseClicked(mouseX, mouseY, button);
	}

	@Override
	public boolean mouseReleased(double mouseX, double mouseY, int button) {
		if (previewDragging && button == 0) {
			previewDragging = false;
			return true;
		}
		return super.mouseReleased(mouseX, mouseY, button);
	}

	@Override
	public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
		if (previewDragging && button == 0) {
			rotatePreview(dragX, dragY);
			return true;
		}
		return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
	}
	*///?}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		if (isPreviewMouse(mouseX, mouseY)) {
			previewZoom = clamp(previewZoom * (1.0 + scrollY * 0.08), 0.55, 2.1);
			return true;
		}
		return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
	}

	private void drawPanel(GuiGraphics graphics) {
		graphics.fill(panelX, panelY, panelX + panelWidth, panelY + panelHeight, PANEL);
		graphics.hLine(panelX, panelX + panelWidth - 1, panelY, BORDER);
		graphics.hLine(panelX, panelX + panelWidth - 1, panelY + panelHeight - 1, BORDER);
		graphics.vLine(panelX, panelY, panelY + panelHeight - 1, BORDER);
		graphics.vLine(panelX + panelWidth - 1, panelY, panelY + panelHeight - 1, BORDER);
		graphics.drawString(font, title, panelX + 12, panelY + 10, TEXT, false);
		graphics.drawString(font,
				Component.translatable("screen.aerodynamics4mc_compat_create_aeronautics.airfoil_wing.static_load_hint"),
				panelX + 12,
				panelY + panelHeight - 50,
				MUTED_TEXT,
				false);
	}

	private void renderWidgets(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
		for (Renderable renderable : renderables) {
			renderable.render(graphics, mouseX, mouseY, partialTick);
		}
	}

	private void drawAirfoilList(GuiGraphics graphics, int mouseX, int mouseY) {
		List<AeroAirfoilDefinition> definitions = CreateAeronauticsAirfoilLibrary.definitions();
		int listHeight = ROW_HEIGHT * VISIBLE_ROWS;
		graphics.fill(listX, listY, listX + listWidth, listY + listHeight, PANEL_ALT);
		graphics.hLine(listX, listX + listWidth - 1, listY, BORDER);
		graphics.hLine(listX, listX + listWidth - 1, listY + listHeight - 1, BORDER);
		graphics.vLine(listX, listY, listY + listHeight - 1, BORDER);
		graphics.vLine(listX + listWidth - 1, listY, listY + listHeight - 1, BORDER);

		for (int i = 0; i < Math.min(VISIBLE_ROWS, definitions.size()); i++) {
			AeroAirfoilDefinition definition = definitions.get(i);
			int rowY = listY + i * ROW_HEIGHT;
			boolean hovered = i == rowAt(mouseX, mouseY);
			if (i == selectedIndex) {
				graphics.fill(listX + 1, rowY + 1, listX + listWidth - 1, rowY + ROW_HEIGHT - 1, SELECTED);
			} else if (hovered) {
				graphics.fill(listX + 1, rowY + 1, listX + listWidth - 1, rowY + ROW_HEIGHT - 1, 0x7731424A);
			}
			graphics.drawString(font, truncate(definition.displayName(), listWidth - 12), listX + 6, rowY + 4, TEXT, false);
			graphics.drawString(font, truncate(definition.id().toString(), listWidth - 12), listX + 6, rowY + 13, MUTED_TEXT, false);
		}

		if (definitions.isEmpty()) {
			graphics.drawString(font,
					Component.translatable("screen.aerodynamics4mc_compat_create_aeronautics.airfoil_wing.no_airfoils"),
					listX + 6,
					listY + 8,
					ERROR_TEXT,
					false);
		}
	}

	private void drawPreview(GuiGraphics graphics) {
		graphics.fill(previewX, previewY, previewX + previewWidth, previewY + previewHeight, PANEL_ALT);
		graphics.hLine(previewX, previewX + previewWidth - 1, previewY, BORDER);
		graphics.hLine(previewX, previewX + previewWidth - 1, previewY + previewHeight - 1, BORDER);
		graphics.vLine(previewX, previewY, previewY + previewHeight - 1, BORDER);
		graphics.vLine(previewX + previewWidth - 1, previewY, previewY + previewHeight - 1, BORDER);
		drawPreviewGrid(graphics);
		AeroAirfoilDefinition definition = selectedDefinition();
		if (definition != null) {
			drawAirfoilModel(graphics, definition);
		}
	}

	private void drawPreviewGrid(GuiGraphics graphics) {
		int innerX0 = previewX + 8;
		int innerX1 = previewX + previewWidth - 8;
		int innerY0 = previewY + 8;
		int innerY1 = previewY + previewHeight - 8;
		int grid = 0x343F4A50;
		int center = 0x5A788890;
		for (int i = 1; i < 4; i++) {
			int x = innerX0 + (innerX1 - innerX0) * i / 4;
			int y = innerY0 + (innerY1 - innerY0) * i / 4;
			graphics.vLine(x, innerY0, innerY1, grid);
			graphics.hLine(innerX0, innerX1, y, grid);
		}
		graphics.hLine(innerX0, innerX1, previewY + previewHeight / 2, center);
	}

	private void drawAirfoilModel(GuiGraphics graphics, AeroAirfoilDefinition definition) {
		AirfoilVisual visual = AirfoilWingVisualLibrary.INSTANCE.find(definition.id()).orElse(AirfoilVisual.DEFAULT);
		AirfoilMesh mesh = AirfoilMeshBuilder.buildPreview(definition, visual);
		if (mesh.isEmpty()) {
			return;
		}

		drawFaces(graphics, mesh, visual);
		boolean leftBack = averageDepth(mesh.leftSection()) < averageDepth(mesh.rightSection());
		drawSegments(graphics, leftBack ? mesh.leftLoop() : mesh.rightLoop(), visual.backColor());
		drawMeshSegments(graphics, mesh.spanConnectors(), visual);
		drawSegments(graphics, mesh.chordGuides(), visual.chordColor());
		drawSegments(graphics, leftBack ? mesh.rightLoop() : mesh.leftLoop(), visual.frontColor());
	}

	private void drawFaces(GuiGraphics graphics, AirfoilMesh mesh, AirfoilVisual visual) {
		List<Face> faces = new ArrayList<>(mesh.faces());
		faces.sort(Comparator.comparingDouble(this::averageDepth));
		int skinColor = surfaceColor(visual);
		for (Face face : faces) {
			int color = switch (face.kind()) {
				case SKIN -> skinColor;
				case LEFT_CAP -> translucent(visual.backColor(), 0x58);
				case RIGHT_CAP -> translucent(visual.frontColor(), 0x58);
			};
			drawFace(graphics, face, color);
		}
	}

	private double averageDepth(List<Point3> points) {
		double depth = 0.0;
		for (Point3 point : points) {
			depth += project(point).depth();
		}
		return depth / points.size();
	}

	private double averageDepth(Face face) {
		return (project(face.a()).depth()
				+ project(face.b()).depth()
				+ project(face.c()).depth()
				+ project(face.d()).depth()) * 0.25;
	}

	private void drawMeshSegments(GuiGraphics graphics, Iterable<Segment> segments, AirfoilVisual visual) {
		for (Segment segment : segments) {
			drawLine(graphics, project(segment.from()), project(segment.to()), color(segment, visual));
		}
	}

	private void drawSegments(GuiGraphics graphics, Iterable<Segment> segments, int color) {
		for (Segment segment : segments) {
			drawLine(graphics, project(segment.from()), project(segment.to()), color);
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

	private ProjectedPoint project(Point3 point) {
		double x = point.x();
		double y = point.y();
		double z = point.z();

		double cosYaw = Math.cos(previewYaw);
		double sinYaw = Math.sin(previewYaw);
		double yawX = x * cosYaw + z * sinYaw;
		double yawZ = -x * sinYaw + z * cosYaw;

		double cosPitch = Math.cos(previewPitch);
		double sinPitch = Math.sin(previewPitch);
		double pitchY = y * cosPitch - yawZ * sinPitch;
		double depth = y * sinPitch + yawZ * cosPitch;

		double scale = Math.min(previewWidth, previewHeight) * 0.73 * previewZoom;
		int screenX = (int) Math.round(previewX + previewWidth / 2.0 + yawX * scale);
		int screenY = (int) Math.round(previewY + previewHeight / 2.0 - pitchY * scale);
		return new ProjectedPoint(screenX, screenY, depth);
	}

	private void drawFace(GuiGraphics graphics, Face face, int color) {
		ProjectedPoint a = project(face.a());
		ProjectedPoint b = project(face.b());
		ProjectedPoint c = project(face.c());
		ProjectedPoint d = project(face.d());
		fillTriangle(graphics, a, b, c, color);
		fillTriangle(graphics, a, c, d, color);
	}

	private void fillTriangle(GuiGraphics graphics, ProjectedPoint a, ProjectedPoint b, ProjectedPoint c, int color) {
		int minX = Math.max(previewX + 2, Math.min(a.x(), Math.min(b.x(), c.x())));
		int maxX = Math.min(previewX + previewWidth - 2, Math.max(a.x(), Math.max(b.x(), c.x())));
		int minY = Math.max(previewY + 2, Math.min(a.y(), Math.min(b.y(), c.y())));
		int maxY = Math.min(previewY + previewHeight - 2, Math.max(a.y(), Math.max(b.y(), c.y())));
		double area = edge(a.x(), a.y(), b.x(), b.y(), c.x(), c.y());
		if (Math.abs(area) < 0.5) {
			return;
		}
		for (int y = minY; y <= maxY; y++) {
			for (int x = minX; x <= maxX; x++) {
				double sampleX = x + 0.5;
				double sampleY = y + 0.5;
				double w0 = edge(b.x(), b.y(), c.x(), c.y(), sampleX, sampleY);
				double w1 = edge(c.x(), c.y(), a.x(), a.y(), sampleX, sampleY);
				double w2 = edge(a.x(), a.y(), b.x(), b.y(), sampleX, sampleY);
				if ((w0 >= 0.0 && w1 >= 0.0 && w2 >= 0.0) || (w0 <= 0.0 && w1 <= 0.0 && w2 <= 0.0)) {
					graphics.fill(x, y, x + 1, y + 1, color);
				}
			}
		}
	}

	private double edge(double ax, double ay, double bx, double by, double px, double py) {
		return (px - ax) * (by - ay) - (py - ay) * (bx - ax);
	}

	private void drawLine(GuiGraphics graphics, ProjectedPoint from, ProjectedPoint to, int color) {
		int x0 = from.x();
		int y0 = from.y();
		int x1 = to.x();
		int y1 = to.y();
		int dx = Math.abs(x1 - x0);
		int dy = Math.abs(y1 - y0);
		int sx = x0 < x1 ? 1 : -1;
		int sy = y0 < y1 ? 1 : -1;
		int err = dx - dy;
		while (true) {
			if (insidePreview(x0, y0)) {
				graphics.fill(x0, y0, x0 + 1, y0 + 1, color);
			}
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

	private int surfaceColor(AirfoilVisual visual) {
		int front = visual.frontColor();
		int back = visual.backColor();
		int alpha = Math.max(0x42, Math.min(0x78, (((front >>> 24) & 0xFF) + ((back >>> 24) & 0xFF)) / 4));
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

	private void drawSelectedDetails(GuiGraphics graphics) {
		AeroAirfoilDefinition definition = selectedDefinition();
		int detailX = listX + listWidth + 12;
		int detailY = listY + PREVIEW_HEIGHT + 12;
		if (definition == null) {
			graphics.drawString(font,
					Component.translatable("screen.aerodynamics4mc_compat_create_aeronautics.airfoil_wing.no_selection"),
					detailX,
					detailY,
					ERROR_TEXT,
					false);
			return;
		}
		AeroAirfoilProfile profile = definition.profile();
		graphics.drawString(font, truncate(definition.displayName(), panelX + panelWidth - detailX - 16), detailX, detailY, TEXT, false);
		graphics.drawString(font, definition.id().toString(), detailX, detailY + 13, MUTED_TEXT, false);
		graphics.drawString(font,
				Component.literal("camber " + percent(profile.maxCamberRatio())
						+ "  thickness " + percent(profile.thicknessRatio())),
				detailX,
				detailY + 30,
				MUTED_TEXT,
				false);
		graphics.drawString(font,
				Component.literal("coordinates " + definition.coordinates().size()),
				detailX,
				detailY + 43,
				MUTED_TEXT,
				false);
	}

	private void selectCurrentStackAirfoil() {
		List<AeroAirfoilDefinition> definitions = CreateAeronauticsAirfoilLibrary.definitions();
		selectedIndex = 0;
		Player player = Minecraft.getInstance().player;
		if (player == null || definitions.isEmpty()) {
			return;
		}
		A4mcId id = AirfoilWingBlockItem.airfoilId(player.getItemInHand(hand))
				.orElseGet(CreateAeronauticsAirfoilLibrary::selectedId);
		for (int i = 0; i < definitions.size(); i++) {
			if (definitions.get(i).id().equals(id)) {
				selectedIndex = i;
				return;
			}
		}
	}

	private AeroAirfoilDefinition selectedDefinition() {
		List<AeroAirfoilDefinition> definitions = CreateAeronauticsAirfoilLibrary.definitions();
		if (definitions.isEmpty()) {
			return null;
		}
		selectedIndex = Math.max(0, Math.min(selectedIndex, definitions.size() - 1));
		return definitions.get(selectedIndex);
	}

	private void useSelected() {
		AeroAirfoilDefinition definition = selectedDefinition();
		Player player = Minecraft.getInstance().player;
		if (definition == null || player == null) {
			return;
		}
		CreateAeronauticsAirfoilLibrary.select(definition.id());
		AirfoilWingBlockItem.setAirfoilId(player.getItemInHand(hand), definition.id());
		ClientServerboundPacketSender.send(CreateAeronauticsAirfoilItemActionPacket.use(
				hand == InteractionHand.MAIN_HAND,
				definition.id().toString()
		));
		player.displayClientMessage(Component.translatable(
				"screen.aerodynamics4mc_compat_create_aeronautics.airfoil_wing.local_use",
				definition.displayName()
		).withStyle(ChatFormatting.AQUA), true);
	}

	private void exportSelected() {
		AeroAirfoilDefinition definition = selectedDefinition();
		if (definition == null) {
			return;
		}
		ClientServerboundPacketSender.send(CreateAeronauticsAirfoilItemActionPacket.export(definition.id().toString()));
	}

	private int rowAt(double mouseX, double mouseY) {
		if (mouseX < listX || mouseX >= listX + listWidth || mouseY < listY || mouseY >= listY + ROW_HEIGHT * VISIBLE_ROWS) {
			return -1;
		}
		return (int) ((mouseY - listY) / ROW_HEIGHT);
	}

	private boolean selectRowAt(double mouseX, double mouseY) {
		int row = rowAt(mouseX, mouseY);
		if (row < 0) {
			return false;
		}
		List<AeroAirfoilDefinition> definitions = CreateAeronauticsAirfoilLibrary.definitions();
		if (row >= definitions.size()) {
			return false;
		}
		selectedIndex = row;
		return true;
	}

	private boolean isPreviewMouse(double mouseX, double mouseY) {
		return mouseX >= previewX
				&& mouseX < previewX + previewWidth
				&& mouseY >= previewY
				&& mouseY < previewY + previewHeight;
	}

	private boolean insidePreview(int x, int y) {
		return x > previewX + 1
				&& x < previewX + previewWidth - 1
				&& y > previewY + 1
				&& y < previewY + previewHeight - 1;
	}

	private void rotatePreview(double dragX, double dragY) {
		previewYaw += dragX * 0.012;
		previewPitch = clamp(previewPitch + dragY * 0.012, -1.15, 1.15);
	}

	private Component truncate(String text, int maxWidth) {
		if (font.width(text) <= maxWidth) {
			return Component.literal(text);
		}
		String ellipsis = "...";
		int maxTextWidth = Math.max(0, maxWidth - font.width(ellipsis));
		String value = text;
		while (!value.isEmpty() && font.width(value) > maxTextWidth) {
			value = value.substring(0, value.length() - 1);
		}
		return Component.literal(value + ellipsis);
	}

	private static String percent(double value) {
		return String.format(Locale.ROOT, "%.1f%%", value * 100.0);
	}

	private static double clamp(double value, double min, double max) {
		return Math.max(min, Math.min(max, value));
	}

	private record ProjectedPoint(int x, int y, double depth) {
	}
}
