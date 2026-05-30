plugins {
	id("mod-platform")
	id("net.neoforged.moddev")
}

stonecutter {
	val (version, loader) = current.project.split('-', limit = 2)
	properties.tags(version, loader)

	replacements.string(current.parsed >= "1.21.11") {
		replace("ResourceLocation", "Identifier")
		replace("location()", "identifier()")
	}
	replacements.string(current.parsed < "1.21.11") {
		replace("Identifier", "ResourceLocation")
		replace("identifier()", "location()")
		replace("net.minecraft.client.renderer.rendertype.RenderTypes", "net.minecraft.client.renderer.RenderType")
		replace("RenderTypes.lines()", "RenderType.lines()")
		replace("user.getCooldowns().addCooldown(user.getItemInHand(hand), 10)", "user.getCooldowns().addCooldown(user.getItemInHand(hand).getItem(), 10)")
		replace("world.updateNeighborsAt(worldPosition, state.getBlock(), null)", "world.updateNeighborsAt(worldPosition, state.getBlock())")
		replace(".getMainCamera().position()", ".getMainCamera().getPosition()")
		replace("client.getDeltaTracker()", "client.getTimer()")
		replace("new DynamicTexture(() -> \"aerodynamics4mc_iris_wind_bridge\", image)", "new DynamicTexture(image)")
		replace("image.setPixel(", "image.setPixelRGBA(")
		replace("world.getMinY()", "world.getMinBuildHeight()")
		replace("world.getMaxY()", "world.getMaxBuildHeight()")
		replace("world.getMinSectionY()", "world.getMinSection()")
		replace("world.getMaxSectionY()", "world.getMaxSection()")
		replace("Commands.hasPermission(Commands.LEVEL_ADMINS)", "source -> source.hasPermission(Commands.LEVEL_ADMINS)")
	}
}

platform {
	loader = "neoforge"
	dependencies {
		required("minecraft") {
			forgeLikeVersionRange = prop("deps.minecraft")
		}
		required("neoforge") {
			forgeLikeVersionRange.set("[1,)")
		}
	}
}

mixins {
	common {
		always(
			"ServerWorldBlockStateMixin",
			"event.LevelChunkMixin"
		)
	}
	client {
		always(
			"client.AscendingParticleMixin",
			"client.CampfireSmokeParticleMixin",
			"client.ClientWorldBlockStateMixin",
			"client.ParticleAccessor",
			"client.ParticleMixin"
		)
		minVersion("1.21.11", "client.LeavesParticleMixin")
	}
}

neoForge {
	version = prop("deps.neoforge")
	accessTransformers.from(rootProject.file("src/main/resources/aw/${stonecutter.current.version}.cfg"))
	validateAccessTransformers = true

	if (hasProperty("deps.parchment")) parchment {
		val (mc, ver) = prop("deps.parchment").split(':')
		mappingsVersion = ver
		minecraftVersion = mc
	}

	runs {
		register("client") {
			client()
			gameDirectory = file("run/")
			ideName = "NeoForge Client (${stonecutter.current.version})"
			programArgument("--username=Dev")
		}
		register("server") {
			server()
			gameDirectory = file("run/")
			ideName = "NeoForge Server (${stonecutter.current.version})"
		}
	}

	mods {
		register(prop("mod.id")) {
			sourceSet(sourceSets["main"])
		}
	}
	sourceSets["main"].resources.srcDir("${rootDir}/versions/datagen/${sc.current.version.split("-")[0]}/src/main/generated")
}

repositories {
	mavenCentral()
	strictMaven("https://api.modrinth.com/maven", "maven.modrinth") { name = "Modrinth" }
	strictMaven("https://jitpack.io") { name = "Jitpack" }
}

dependencies {
	// implementation(libs.moulberry.mixinconstraints)
	// jarJar(libs.moulberry.mixinconstraints)

	compileOnly("org.projectlombok:lombok:1.18.46")
	annotationProcessor("org.projectlombok:lombok:1.18.46")

	testCompileOnly("org.projectlombok:lombok:1.18.46")
	testAnnotationProcessor("org.projectlombok:lombok:1.18.46")

	jarJar(implementation("com.github.RazorPlay01:PacketHandler:1.3.0")!!)
}

tasks.named("createMinecraftArtifacts") {
	dependsOn(tasks.named("stonecutterGenerate"))
}
