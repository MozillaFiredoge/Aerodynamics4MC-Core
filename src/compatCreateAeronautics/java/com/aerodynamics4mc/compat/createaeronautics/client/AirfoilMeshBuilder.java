package com.aerodynamics4mc.compat.createaeronautics.client;

import com.aerodynamics4mc.api.AeroAirfoilCoordinate;
import com.aerodynamics4mc.api.AeroAirfoilDefinition;
import com.aerodynamics4mc.compat.createaeronautics.client.AirfoilWingVisualLibrary.AirfoilVisual;
import net.minecraft.core.Direction;

import java.util.ArrayList;
import java.util.List;

public final class AirfoilMeshBuilder {
	private static final double BLOCK_SPAN = 0.46;
	private static final double PREVIEW_SPAN = 0.44;
	private static final MeshLayout PREVIEW_LAYOUT = new MeshLayout(
			new Point3(0.0f, 0.0f, 0.0f),
			new Point3(-1.0f, 0.0f, 0.0f),
			new Point3(0.0f, 0.0f, 1.0f),
			new Point3(0.0f, 1.0f, 0.0f),
			1.55 / AirfoilVisual.DEFAULT.chordScale(),
			1.0 / AirfoilVisual.DEFAULT.spanScale(),
			2.75 / AirfoilVisual.DEFAULT.sectionYScale(),
			AirfoilVisual.DEFAULT.sectionYOffset(),
			-PREVIEW_SPAN,
			PREVIEW_SPAN
	);

	private AirfoilMeshBuilder() {
	}

	public static AirfoilMesh buildBlock(AeroAirfoilDefinition definition, AirfoilVisual visual, Direction facing) {
		Direction right = facing.getClockWise();
		return build(definition, visual, new MeshLayout(
				new Point3(0.5f, 0.0f, 0.5f),
				new Point3(facing.getStepX(), 0.0f, facing.getStepZ()),
				new Point3(right.getStepX(), 0.0f, right.getStepZ()),
				new Point3(0.0f, 1.0f, 0.0f),
				1.0,
				1.0,
				1.0,
				0.0,
				-BLOCK_SPAN,
				BLOCK_SPAN
		));
	}

	public static AirfoilMesh buildPreview(AeroAirfoilDefinition definition, AirfoilVisual visual) {
		return build(definition, visual, PREVIEW_LAYOUT);
	}

	public static AirfoilMesh build(AeroAirfoilDefinition definition, AirfoilVisual visual, MeshLayout layout) {
		AirfoilVisual resolvedVisual = visual == null ? AirfoilVisual.DEFAULT : visual;
		return build(resolvedVisual.coordinatesOr(definition.coordinates()), resolvedVisual, layout);
	}

	public static AirfoilMesh build(List<AeroAirfoilCoordinate> coordinates, AirfoilVisual visual, MeshLayout layout) {
		AirfoilVisual resolvedVisual = visual == null ? AirfoilVisual.DEFAULT : visual;
		if (coordinates.size() < 2) {
			return AirfoilMesh.EMPTY;
		}

		List<Point3> left = new ArrayList<>(coordinates.size());
		List<Point3> right = new ArrayList<>(coordinates.size());
		for (AeroAirfoilCoordinate coordinate : coordinates) {
			left.add(point(coordinate.x(), coordinate.y(), layout.leftSpan(), resolvedVisual, layout));
			right.add(point(coordinate.x(), coordinate.y(), layout.rightSpan(), resolvedVisual, layout));
		}

		return new AirfoilMesh(
				left,
				right,
				faces(left, right),
				loop(left, SegmentKind.SECTION),
				loop(right, SegmentKind.SECTION),
				spanConnectors(left, right),
				chordGuides(resolvedVisual, layout)
		);
	}

	private static Point3 point(double chordX, double sectionY, double span, AirfoilVisual visual, MeshLayout layout) {
		double chord = 0.5 - chordX;
		double visualSectionY = sectionY * visual.sectionYScale() + visual.sectionYOffset() - layout.sectionYOffsetBaseline();
		return layout.origin()
				.add(layout.chordAxis(), chord * visual.chordScale() * layout.chordScale())
				.add(layout.spanAxis(), span * visual.spanScale() * layout.spanScale())
				.add(layout.sectionAxis(), visualSectionY * layout.sectionYScale());
	}

	private static List<Segment> loop(List<Point3> points, SegmentKind kind) {
		List<Segment> segments = new ArrayList<>(points.size());
		for (int i = 0; i < points.size(); i++) {
			segments.add(new Segment(points.get(i), points.get((i + 1) % points.size()), kind));
		}
		return segments;
	}

	private static List<Segment> spanConnectors(List<Point3> left, List<Point3> right) {
		int count = left.size();
		List<Segment> segments = new ArrayList<>(count / 2 + 4);
		int step = Math.max(1, count / 12);
		for (int i = 0; i < count; i += step) {
			segments.add(new Segment(left.get(i), right.get(i), SegmentKind.SPAN_RIB));
		}
		segments.add(new Segment(left.get(0), right.get(0), SegmentKind.SPAN_ACCENT));
		segments.add(new Segment(left.get(count - 1), right.get(count - 1), SegmentKind.SPAN_ACCENT));
		segments.add(new Segment(left.get(count / 2), right.get(count / 2), SegmentKind.SPAN_ACCENT));
		return segments;
	}

	private static List<Segment> chordGuides(AirfoilVisual visual, MeshLayout layout) {
		Point3 leftLead = point(0.0, 0.0, layout.leftSpan(), visual, layout);
		Point3 leftTrail = point(1.0, 0.0, layout.leftSpan(), visual, layout);
		Point3 rightLead = point(0.0, 0.0, layout.rightSpan(), visual, layout);
		Point3 rightTrail = point(1.0, 0.0, layout.rightSpan(), visual, layout);
		return List.of(
				new Segment(leftLead, leftTrail, SegmentKind.CHORD_GUIDE),
				new Segment(rightLead, rightTrail, SegmentKind.CHORD_GUIDE),
				new Segment(leftLead, rightLead, SegmentKind.CHORD_GUIDE),
				new Segment(leftTrail, rightTrail, SegmentKind.CHORD_GUIDE)
		);
	}

	private static List<Face> faces(List<Point3> left, List<Point3> right) {
		int count = left.size();
		List<Face> faces = new ArrayList<>(count * 3);
		for (int i = 0; i < count; i++) {
			int next = (i + 1) % count;
			faces.add(new Face(left.get(i), left.get(next), right.get(next), right.get(i), FaceKind.SKIN));
		}
		addCapFaces(faces, left, FaceKind.LEFT_CAP);
		addCapFaces(faces, right, FaceKind.RIGHT_CAP);
		return faces;
	}

	private static void addCapFaces(List<Face> faces, List<Point3> section, FaceKind kind) {
		Point3 center = center(section);
		for (int i = 0; i < section.size(); i++) {
			Point3 current = section.get(i);
			Point3 next = section.get((i + 1) % section.size());
			faces.add(new Face(center, current, next, next, kind));
		}
	}

	private static Point3 center(List<Point3> points) {
		double x = 0.0;
		double y = 0.0;
		double z = 0.0;
		for (Point3 point : points) {
			x += point.x();
			y += point.y();
			z += point.z();
		}
		double count = points.size();
		return new Point3(x / count, y / count, z / count);
	}

	public enum SegmentKind {
		SECTION,
		SPAN_RIB,
		SPAN_ACCENT,
		CHORD_GUIDE
	}

	public enum FaceKind {
		SKIN,
		LEFT_CAP,
		RIGHT_CAP
	}

	public record AirfoilMesh(
			List<Point3> leftSection,
			List<Point3> rightSection,
			List<Face> faces,
			List<Segment> leftLoop,
			List<Segment> rightLoop,
			List<Segment> spanConnectors,
			List<Segment> chordGuides
	) {
		public static final AirfoilMesh EMPTY = new AirfoilMesh(List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());

		public AirfoilMesh {
			leftSection = List.copyOf(leftSection);
			rightSection = List.copyOf(rightSection);
			faces = List.copyOf(faces);
			leftLoop = List.copyOf(leftLoop);
			rightLoop = List.copyOf(rightLoop);
			spanConnectors = List.copyOf(spanConnectors);
			chordGuides = List.copyOf(chordGuides);
		}

		public boolean isEmpty() {
			return leftSection.isEmpty() || rightSection.isEmpty();
		}
	}

	public record MeshLayout(
			Point3 origin,
			Point3 chordAxis,
			Point3 spanAxis,
			Point3 sectionAxis,
			double chordScale,
			double spanScale,
			double sectionYScale,
			double sectionYOffsetBaseline,
			double leftSpan,
			double rightSpan
	) {
		public MeshLayout {
			checkFinite("chord_scale", chordScale);
			checkFinite("span_scale", spanScale);
			checkFinite("section_y_scale", sectionYScale);
			checkFinite("section_y_offset_baseline", sectionYOffsetBaseline);
			checkFinite("left_span", leftSpan);
			checkFinite("right_span", rightSpan);
		}
	}

	public record Segment(Point3 from, Point3 to, SegmentKind kind) {
	}

	public record Face(Point3 a, Point3 b, Point3 c, Point3 d, FaceKind kind) {
	}

	public record Point3(float x, float y, float z) {
		public Point3(double x, double y, double z) {
			this((float) x, (float) y, (float) z);
		}

		private Point3 add(Point3 axis, double scale) {
			return new Point3(x + axis.x * scale, y + axis.y * scale, z + axis.z * scale);
		}
	}

	private static void checkFinite(String name, double value) {
		if (!Double.isFinite(value)) {
			throw new IllegalArgumentException(name + " must be finite");
		}
	}
}
