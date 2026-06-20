import fs from "node:fs";
import path from "node:path";
import zlib from "node:zlib";

const repoRoot = path.resolve(import.meta.dirname, "..");
const packRoot = path.join(repoRoot, "examples/resourcepacks/a4mc-airfoil-visuals");
const visualRoot = path.join(packRoot, "assets/aerodynamics4mc/aerodynamics4mc/airfoil_visuals");
const compatResourceRoot = path.join(repoRoot, "src/compatCreateAeronautics/resources/assets/aerodynamics4mc_compat_create_aeronautics");
const packCompatRoot = path.join(packRoot, "assets/aerodynamics4mc_compat_create_aeronautics");

const DEFAULT_MODEL = "naca_0012";
const MODEL_NAMES = ["flat_plate", "naca_0012", "naca_2412", "naca_4412"];
const VARIANTS = [...MODEL_NAMES, "custom"];
const FACINGS = [
	["north", 0],
	["east", 90],
	["south", 180],
	["west", 270]
];
const AIRFOIL_TEXTURE = "aerodynamics4mc_compat_create_aeronautics:block/airfoil_skin";
const AIRFOIL_REAR_MARK_TEXTURE = "aerodynamics4mc_compat_create_aeronautics:block/airfoil_rear_mark";
const TEXTURE_SIZE = 16;
const PANEL_Y0 = 6.0;
const PANEL_Y1 = 10.0;
const REAR_MARK_Z0 = 15.0;

const visualFiles = fs.readdirSync(visualRoot)
	.filter((file) => file.endsWith(".json"))
	.sort();

writeTextures(compatResourceRoot);
writeTextures(packCompatRoot);
writeModels(compatResourceRoot);
writeModels(packCompatRoot);
writeBlockstate(compatResourceRoot);

console.log(`Generated ${visualFiles.length} airfoil block model(s) for mod resources and example resource pack`);

function writeTextures(assetRoot) {
	const textureRoot = path.join(assetRoot, "textures/block");
	fs.mkdirSync(textureRoot, { recursive: true });
	fs.writeFileSync(path.join(textureRoot, "airfoil_skin.png"), createAirfoilSkinPng());
	fs.writeFileSync(path.join(textureRoot, "airfoil_rear_mark.png"), createAirfoilRearMarkPng());
}

function writeModels(assetRoot) {
	const modelRoot = path.join(assetRoot, "models/block/airfoil_wing");
	const generatedRoot = path.join(modelRoot, "generated");
	fs.mkdirSync(generatedRoot, { recursive: true });

	for (const file of visualFiles) {
		const name = path.basename(file, ".json");
		const model = wingPanelModel(false);
		const verticalModel = wingPanelModel(true);
		fs.writeFileSync(
			path.join(generatedRoot, `${name}.json`),
			`${JSON.stringify(model, null, 2)}\n`
		);
		fs.writeFileSync(
			path.join(generatedRoot, `${name}_vertical.json`),
			`${JSON.stringify(verticalModel, null, 2)}\n`
		);
	}

	fs.writeFileSync(
		path.join(generatedRoot, "custom.json"),
		`${JSON.stringify({
			parent: `aerodynamics4mc_compat_create_aeronautics:block/airfoil_wing/generated/${DEFAULT_MODEL}`
		}, null, 2)}\n`
	);
	fs.writeFileSync(
		path.join(generatedRoot, "custom_vertical.json"),
		`${JSON.stringify({
			parent: `aerodynamics4mc_compat_create_aeronautics:block/airfoil_wing/generated/${DEFAULT_MODEL}_vertical`
		}, null, 2)}\n`
	);

	fs.writeFileSync(
		path.join(modelRoot, "../airfoil_wing.json"),
		`${JSON.stringify({
			parent: `aerodynamics4mc_compat_create_aeronautics:block/airfoil_wing/generated/${DEFAULT_MODEL}`
		}, null, 2)}\n`
	);
}

function writeBlockstate(assetRoot) {
	const blockstateRoot = path.join(assetRoot, "blockstates");
	fs.mkdirSync(blockstateRoot, { recursive: true });
	const variants = {};
	for (const [facing, rotation] of FACINGS) {
		for (const vertical of [false, true]) {
			for (const variant of VARIANTS) {
				const modelName = vertical ? `${variant}_vertical` : variant;
				const entry = {
					model: `aerodynamics4mc_compat_create_aeronautics:block/airfoil_wing/generated/${modelName}`
				};
				if (rotation !== 0) {
					entry.y = rotation;
				}
				variants[`facing=${facing},vertical=${vertical},variant=${variant}`] = entry;
			}
		}
	}
	fs.writeFileSync(
		path.join(blockstateRoot, "airfoil_wing.json"),
		`${JSON.stringify({ variants }, null, 2)}\n`
	);
}

function wingPanelModel(vertical) {
	return {
		parent: "minecraft:block/block",
		ambientocclusion: false,
		textures: {
			skin: AIRFOIL_TEXTURE,
			rear_mark: AIRFOIL_REAR_MARK_TEXTURE,
			particle: AIRFOIL_TEXTURE
		},
		elements: vertical ? verticalPanelElements() : horizontalPanelElements()
	};
}

function horizontalPanelElements() {
	return [
		box([0.0, PANEL_Y0, 0.0], [16.0, PANEL_Y1, REAR_MARK_Z0], "#skin", ["south"]),
		box([0.0, PANEL_Y0, REAR_MARK_Z0], [16.0, PANEL_Y1, 16.0], "#rear_mark", ["north"])
	];
}

function verticalPanelElements() {
	return [
		box([PANEL_Y0, 0.0, 0.0], [PANEL_Y1, 16.0, REAR_MARK_Z0], "#skin", ["south"]),
		box([PANEL_Y0, 0.0, REAR_MARK_Z0], [PANEL_Y1, 16.0, 16.0], "#rear_mark", ["north"])
	];
}

function box(from, to, texture, omittedFaces = []) {
	const roundedFrom = from.map(round);
	const roundedTo = to.map(round);
	const omitted = new Set(omittedFaces);
	const allFaces = {
		down: face(texture, uvXZ(roundedFrom, roundedTo)),
		up: face(texture, uvXZ(roundedFrom, roundedTo)),
		north: face(texture, uvXY(roundedFrom, roundedTo)),
		south: face(texture, uvXY(roundedFrom, roundedTo)),
		west: face(texture, uvZY(roundedFrom, roundedTo)),
		east: face(texture, uvZY(roundedFrom, roundedTo))
	};
	const faces = Object.fromEntries(
		Object.entries(allFaces).filter(([name]) => !omitted.has(name))
	);
	return {
		from: roundedFrom,
		to: roundedTo,
		faces
	};
}

function face(texture, uv) {
	return {
		uv: uv.map(round),
		texture
	};
}

function uvXZ(from, to) {
	return [from[0], from[2], to[0], to[2]];
}

function uvXY(from, to) {
	return [from[0], TEXTURE_SIZE - to[1], to[0], TEXTURE_SIZE - from[1]];
}

function uvZY(from, to) {
	return [from[2], TEXTURE_SIZE - to[1], to[2], TEXTURE_SIZE - from[1]];
}

function createAirfoilSkinPng() {
	return createPng((x, y) => airfoilSkinPixel(x, y));
}

function createAirfoilRearMarkPng() {
	return createPng((x, y) => airfoilRearMarkPixel(x, y));
}

function createPng(pixelAt) {
	const width = TEXTURE_SIZE;
	const height = TEXTURE_SIZE;
	const raw = Buffer.alloc((width * 4 + 1) * height);
	let offset = 0;
	for (let y = 0; y < height; y++) {
		raw[offset++] = 0;
		for (let x = 0; x < width; x++) {
			const [r, g, b, a] = pixelAt(x, y);
			raw[offset++] = r;
			raw[offset++] = g;
			raw[offset++] = b;
			raw[offset++] = a;
		}
	}
	const header = Buffer.alloc(13);
	header.writeUInt32BE(width, 0);
	header.writeUInt32BE(height, 4);
	header[8] = 8;
	header[9] = 6;
	header[10] = 0;
	header[11] = 0;
	header[12] = 0;

	return Buffer.concat([
		Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
		pngChunk("IHDR", header),
		pngChunk("IDAT", zlib.deflateSync(raw, { level: 9 })),
		pngChunk("IEND", Buffer.alloc(0))
	]);
}

function airfoilSkinPixel(x, y) {
	const u = (x + 0.5) / TEXTURE_SIZE;
	const v = (y + 0.5) / TEXTURE_SIZE;
	let shade = 190 + tileableMetalNoise(u, v);

	if (x === 8 || y === 8) {
		shade -= 5;
	}
	if (x === 9 || y === 9) {
		shade += 3;
	}
	if ((x + y) % 8 === 3) {
		shade += 3;
	}
	if ((x * 3 + y * 5) % 16 === 6) {
		shade -= 3;
	}

	const rivet = rivetShade(x, y);
	if (rivet !== 0) {
		shade += rivet;
	}

	return [
		clampByte(shade + 9),
		clampByte(shade + 7),
		clampByte(shade),
		255
	];
}

function airfoilRearMarkPixel(x, y) {
	const noise = ((x * 23 + y * 31 + x * y * 7) % 7) - 3;
	const shade = 28 + noise;
	return [
		clampByte(shade + 2),
		clampByte(shade + 2),
		clampByte(shade),
		255
	];
}

function tileableMetalNoise(u, v) {
	const tau = Math.PI * 2.0;
	return Math.round(
		Math.sin(u * tau) * 2.2
			+ Math.cos(v * tau) * 1.8
			+ Math.sin((u + v) * tau * 2.0) * 1.4
			+ Math.cos((u * 3.0 - v * 2.0) * tau) * 1.2
	);
}

function rivetShade(x, y) {
	const rivets = [
		[3, 3],
		[12, 3],
		[3, 12],
		[12, 12]
	];
	for (const [cx, cy] of rivets) {
		const distance = Math.hypot(x - cx, y - cy);
		if (distance < 0.75) {
			return 14;
		}
		if (distance < 1.55) {
			return -18;
		}
	}
	return 0;
}

function pngChunk(type, data) {
	const typeBuffer = Buffer.from(type, "ascii");
	const length = Buffer.alloc(4);
	length.writeUInt32BE(data.length, 0);
	const crcBuffer = Buffer.alloc(4);
	crcBuffer.writeUInt32BE(crc32(Buffer.concat([typeBuffer, data])), 0);
	return Buffer.concat([length, typeBuffer, data, crcBuffer]);
}

function crc32(buffer) {
	let crc = 0xffffffff;
	for (const byte of buffer) {
		crc ^= byte;
		for (let bit = 0; bit < 8; bit++) {
			crc = (crc >>> 1) ^ (0xedb88320 & -(crc & 1));
		}
	}
	return (crc ^ 0xffffffff) >>> 0;
}

function clampByte(value) {
	return Math.max(0, Math.min(255, Math.round(value)));
}

function round(value) {
	return Number(value.toFixed(4));
}
