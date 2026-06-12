plugins {
	id("mod-platform")
	id("net.fabricmc.fabric-loom-remap")
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
	replacements.string(current.parsed >= "26.1.2") {
		replace("FabricDataOutput", "FabricPackOutput")
	}
}

platform {
	loader = "fabric-o"
	dependencies {
		required("minecraft") {
			fabricLikeVersionRange = prop("deps.minecraft")
		}
		required("fabric-api") {
			slug("fabric-api")
			fabricLikeVersionRange = ">=${prop("deps.fabric-api")}"
		}
		required("fabricloader") {
			fabricLikeVersionRange = ">=${prop("deps.fabric-loader")}"
		}
		optional("modmenu") {}
	}
}

mixins {
	common {
		always(
			"ServerWorldBlockStateMixin"
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
		minVersion("1.21.11", "client.WeatherEffectRendererMixin")
	}
}

loom {
	accessWidenerPath = rootProject.file("src/main/resources/aw/${sc.current.version}.accesswidener")
	runs.named("client") {
		client()
		ideConfigGenerated(true)
		runDir = "run/"
		environment = "client"
		programArgs("--username=Dev")
		configName = "Fabric Client"
	}
	runs.named("server") {
		server()
		ideConfigGenerated(true)
		runDir = "run/"
		environment = "server"
		configName = "Fabric Server"
	}
}

fabricApi {
	configureDataGeneration {
		outputDirectory = file("${rootDir}/versions/datagen/${sc.current.version.split("-")[0]}/src/main/generated")
		client = true
	}
}

repositories {
	mavenCentral()
	strictMaven("https://maven.terraformersmc.com/", "com.terraformersmc") { name = "TerraformersMC" }
	strictMaven("https://api.modrinth.com/maven", "maven.modrinth") { name = "Modrinth" }
	strictMaven("https://jitpack.io") { name = "Jitpack" }
}

configurations.all {
	resolutionStrategy {
		force("net.fabricmc:fabric-loader:${prop("deps.fabric-loader")}")
	}
}

dependencies {
	implementation(project(":api"))
	include(project(":api"))

	minecraft("com.mojang:minecraft:${prop("deps.minecraft")}")
	mappings(
		loom.layered {
			officialMojangMappings()
			if (hasProperty("deps.parchment")) parchment("org.parchmentmc.data:parchment-${prop("deps.parchment")}@zip")
		})
	modImplementation("net.fabricmc:fabric-loader:${prop("deps.fabric-loader")}")
	// implementation(libs.moulberry.mixinconstraints)
	// include(libs.moulberry.mixinconstraints)
	modImplementation("net.fabricmc.fabric-api:fabric-api:${prop("deps.fabric-api")}")
	modLocalRuntime("com.terraformersmc:modmenu:${prop("deps.modmenu")}")

	compileOnly("org.projectlombok:lombok:1.18.46")
	annotationProcessor("org.projectlombok:lombok:1.18.46")

	testCompileOnly("org.projectlombok:lombok:1.18.46")
	testAnnotationProcessor("org.projectlombok:lombok:1.18.46")

	include(implementation("com.github.RazorPlay01:PacketHandler:1.3.0")!!)
}
