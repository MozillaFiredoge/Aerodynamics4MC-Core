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
			"client.LeavesParticleMixin",
			"client.ParticleAccessor",
			"client.ParticleMixin"
		)
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
