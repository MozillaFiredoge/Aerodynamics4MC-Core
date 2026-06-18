@file:Suppress("unused", "DuplicatedCode")

import dev.kikugie.fletching_table.extension.FletchingTableExtension
import dev.kikugie.stonecutter.StonecutterExperimentalAPI
import dev.kikugie.stonecutter.build.StonecutterBuildExtension
import net.fabricmc.loom.task.RemapJarTask
import org.gradle.api.DefaultTask
import org.gradle.api.artifacts.ExternalModuleDependency
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.dsl.RepositoryHandler
import org.gradle.api.artifacts.repositories.MavenArtifactRepository
import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Copy
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.TaskAction
import org.gradle.internal.extensions.stdlib.toDefaultLowerCase
import org.gradle.jvm.tasks.Jar
import org.gradle.kotlin.dsl.*
import org.gradle.language.jvm.tasks.ProcessResources
import org.gradle.plugins.ide.idea.model.IdeaModel
import java.io.File
import javax.inject.Inject

val Project.sc: StonecutterBuildExtension
	get() = extensions.getByType<StonecutterBuildExtension>()

@OptIn(StonecutterExperimentalAPI::class)
fun Project.prop(name: String): String = (project.sc.properties.get<String>(name))

fun Project.env(variable: String): String? = providers.environmentVariable(variable).orNull

fun Project.envTrue(variable: String): Boolean = env(variable)?.toDefaultLowerCase() == "true"

fun RepositoryHandler.strictMaven(
	url: String, vararg groups: String, configure: MavenArtifactRepository.() -> Unit = {}
) = exclusiveContent {
	forRepository { maven(url) { configure() } }
	filter { groups.forEach(::includeGroup) }
}

abstract class GenerateModManifestTask : DefaultTask() {
	@get:Input
	abstract val content: Property<String>

	@get:OutputFile
	abstract val outputFile: RegularFileProperty

	@TaskAction
	fun generate() {
		val file = outputFile.get().asFile
		file.parentFile.mkdirs()
		file.writeText(content.get())
	}
}

abstract class ModPlatformPlugin @Inject constructor() : Plugin<Project> {
	override fun apply(project: Project) = with(project) {
		val inferredLoader = Loader.of(project.buildFile.name.substringAfter('.').replace(".gradle.kts", ""))

		val extension = extensions.create("platform", ModPlatformExtension::class.java).apply {
			loader.convention(inferredLoader.id)
			jarTask.convention(inferredLoader.jarTask)
			sourcesJarTask.convention(inferredLoader.sourcesJarTask)
		}

		extensions.create("mixins", MixinsExtension::class.java)

		listOf("org.jetbrains.kotlin.jvm", "com.google.devtools.ksp", "dev.kikugie.fletching-table").forEach {
			apply(
				plugin = it
			)
		}

		afterEvaluate {
			val ctx = Context(
				project = this,
				extension = extension,
				loader = Loader.of(extension.loader.get()),
				stonecutter = project.sc
			)
			configureProject(ctx)
		}
	}

	private fun Project.configureProject(ctx: Context) {
		listOf("java", "me.modmuss50.mod-publish-plugin", "idea").forEach { apply(plugin = it) }

		version = ctx.fullVersion
		ctx.extension.requiredJava.set(ctx.javaVersion)

		if (ctx.loader.isFabricLike) {
			ctx.extension.dependencies {
				required("java") { fabricLikeVersionRange = ">=${ctx.javaVersion.majorVersion}" }
			}
		}

		configureFletchingTable(ctx)
		registerGenerateManifestTask(ctx)
		configureJarTask(ctx)
		configureIdea()
		configureProcessResources(ctx)
		configureJava(ctx)
		configureNativeResources(ctx)
		configureBundledSourceSets(ctx)
		registerClientIsolationCheck()
		registerBuildAndCollectTask(ctx)

		configureModPublishing(ctx)

		if (envTrue("PUB_MAVEN_ENABLE")) {
			configureMavenPublishing(ctx)
		}
	}

	private fun Project.configureJava(ctx: Context) {
		extensions.configure<JavaPluginExtension>("java") {
			withSourcesJar()
			withJavadocJar()
			sourceCompatibility = ctx.javaVersion
			targetCompatibility = ctx.javaVersion
		}
	}

	private fun Project.configureBundledSourceSets(ctx: Context) {
		val java = the<JavaPluginExtension>()
		val sourceSets = java.sourceSets
		val mainSourceSet = sourceSets.named(SourceSet.MAIN_SOURCE_SET_NAME).get()
		val contentSourceSet = configureFeatureSourceSet("content", mainSourceSet)
		val clientSourceSet = configureFeatureSourceSet("client", mainSourceSet)
		bundleSourceSetIntoMainJar(clientSourceSet, mainSourceSet)
		val contentClientSourceSet = configureFeatureSourceSet("contentClient", mainSourceSet, contentSourceSet, clientSourceSet)
		configureContentAddonJar(ctx, contentSourceSet, contentClientSourceSet)
		if (ctx.loader == Loader.NeoForge) {
			val createAeronauticsCompatSourceSet = configureFeatureSourceSet("compatCreateAeronautics", mainSourceSet)
			configureCreateAeronauticsCompatJar(ctx, createAeronauticsCompatSourceSet)
		}
	}

	private fun Project.configureFeatureSourceSet(
		name: String,
		mainSourceSet: SourceSet,
		vararg upstreamSourceSets: SourceSet
	): SourceSet {
		val sourceSets = the<JavaPluginExtension>().sourceSets
		val featureSourceSet = sourceSets.findByName(name) ?: sourceSets.create(name)

		configurations.named(featureSourceSet.implementationConfigurationName) {
			extendsFrom(configurations.getByName(mainSourceSet.implementationConfigurationName))
		}
		configurations.named(featureSourceSet.compileOnlyConfigurationName) {
			extendsFrom(configurations.getByName(mainSourceSet.compileOnlyConfigurationName))
		}
		configurations.named(featureSourceSet.runtimeOnlyConfigurationName) {
			extendsFrom(configurations.getByName(mainSourceSet.runtimeOnlyConfigurationName))
		}
		configurations.named(featureSourceSet.annotationProcessorConfigurationName) {
			extendsFrom(configurations.getByName(mainSourceSet.annotationProcessorConfigurationName))
		}

		featureSourceSet.compileClasspath += mainSourceSet.output + mainSourceSet.compileClasspath
		featureSourceSet.runtimeClasspath += featureSourceSet.output + featureSourceSet.compileClasspath + mainSourceSet.runtimeClasspath
		for (upstreamSourceSet in upstreamSourceSets) {
			featureSourceSet.compileClasspath += upstreamSourceSet.output + upstreamSourceSet.compileClasspath
			featureSourceSet.runtimeClasspath += upstreamSourceSet.output + upstreamSourceSet.runtimeClasspath
		}

		tasks.named(featureSourceSet.compileJavaTaskName) {
			dependsOn(tasks.named(mainSourceSet.classesTaskName))
			for (upstreamSourceSet in upstreamSourceSets) {
				dependsOn(tasks.named(upstreamSourceSet.classesTaskName))
			}
		}
		return featureSourceSet
	}

	private fun Project.bundleSourceSetIntoMainJar(featureSourceSet: SourceSet, mainSourceSet: SourceSet) {
		mainSourceSet.runtimeClasspath += featureSourceSet.output
		tasks.named<Jar>("jar") {
			dependsOn(tasks.named(featureSourceSet.classesTaskName))
			from(featureSourceSet.output)
		}
		tasks.named<Jar>("sourcesJar") {
			from(featureSourceSet.allSource)
		}
	}

	private fun Project.configureContentAddonJar(
		ctx: Context,
		contentSourceSet: SourceSet,
		contentClientSourceSet: SourceSet
	) {
		val contentManifestDir = layout.buildDirectory.dir("generated/contentModManifest")
		val contentManifestTask = tasks.register<GenerateModManifestTask>("generateContentModManifest") {
			content.set(ctx.loader.generateContentManifest(ctx))
			outputFile.set(layout.buildDirectory.file("generated/contentModManifest/${ctx.loader.modManifestPath}"))
		}

		val contentJar = tasks.register<Jar>("contentJar") {
			group = "build"
			description = "Builds the official Aerodynamics4MC content addon jar."
			archiveBaseName.set("${ctx.modId}-content")
			if (ctx.loader.isFabricLike) {
				archiveClassifier.set("dev")
				description = "Builds the official Aerodynamics4MC content addon dev jar."
			}
			dependsOn(
				contentManifestTask,
				tasks.named(contentSourceSet.classesTaskName),
				tasks.named(contentClientSourceSet.classesTaskName)
			)
			from(contentSourceSet.output)
			from(contentClientSourceSet.output)
			from(contentManifestDir)
			from(rootProject.file("src/main/resources/assets/icon.png")) {
				into("assets")
			}
		}

		val finalContentJar = if (ctx.loader.isFabricLike) {
			tasks.register<RemapJarTask>("remapContentJar") {
				group = "build"
				description = "Remaps the official Aerodynamics4MC content addon jar."
				archiveBaseName.set("${ctx.modId}-content")
				archiveClassifier.set("")
				inputFile.set(contentJar.flatMap { it.archiveFile })
				dependsOn(contentJar)
				addNestedDependencies.set(false)
				classpath.from(contentSourceSet.compileClasspath, contentClientSourceSet.compileClasspath)
			}
		} else {
			contentJar
		}

		val contentSourcesJar = tasks.register<Jar>("contentSourcesJar") {
			group = "build"
			description = "Builds the official Aerodynamics4MC content addon sources jar."
			archiveBaseName.set("${ctx.modId}-content")
			archiveClassifier.set("sources")
			from(contentSourceSet.allSource)
			from(contentClientSourceSet.allSource)
		}

		tasks.named("assemble") {
			dependsOn(finalContentJar, contentSourcesJar)
		}
	}

	private fun Project.configureCreateAeronauticsCompatJar(
		ctx: Context,
		compatSourceSet: SourceSet
	) {
		if (ctx.loader != Loader.NeoForge) {
			return
		}
		(dependencies.add(
			compatSourceSet.compileOnlyConfigurationName,
			"dev.ryanhcode.sable:sable-neoforge-1.21.1:2.0.0"
		) as ExternalModuleDependency).isTransitive = false
		(dependencies.add(
			compatSourceSet.compileOnlyConfigurationName,
			"dev.ryanhcode.sable-companion:sable-companion-common-1.21.1:1.6.0"
		) as ExternalModuleDependency).isTransitive = false
		val compatManifestDir = layout.buildDirectory.dir("generated/createAeronauticsCompatModManifest")
		val compatManifestTask = tasks.register<GenerateModManifestTask>("generateCreateAeronauticsCompatModManifest") {
			content.set(ctx.loader.generateCreateAeronauticsCompatManifest(ctx))
			outputFile.set(layout.buildDirectory.file("generated/createAeronauticsCompatModManifest/${ctx.loader.modManifestPath}"))
		}

		val compatJar = tasks.register<Jar>("createAeronauticsCompatJar") {
			group = "build"
			description = "Builds the Create Aeronautics compatibility addon jar."
			archiveBaseName.set("${ctx.modId}-compat-create-aeronautics")
			dependsOn(compatManifestTask, tasks.named(compatSourceSet.classesTaskName))
			from(compatSourceSet.output)
			from(compatManifestDir)
			from(rootProject.file("src/main/resources/assets/icon.png")) {
				into("assets")
			}
		}

		val compatSourcesJar = tasks.register<Jar>("createAeronauticsCompatSourcesJar") {
			group = "build"
			description = "Builds the Create Aeronautics compatibility addon sources jar."
			archiveBaseName.set("${ctx.modId}-compat-create-aeronautics")
			archiveClassifier.set("sources")
			from(compatSourceSet.allSource)
		}

		tasks.named("assemble") {
			dependsOn(compatJar, compatSourcesJar)
		}
	}

	private fun Project.registerClientIsolationCheck() {
		val mainSourceSet = the<JavaPluginExtension>().sourceSets.named(SourceSet.MAIN_SOURCE_SET_NAME)
		val forbiddenReferences = listOf(
				"com.aerodynamics4mc.client.",
				"com.aerodynamics4mc.mixin.client.",
				"com.aerodynamics4mc.block.",
				"com.aerodynamics4mc.particle.",
				"com.aerodynamics4mc.vehicle.",
				"net.minecraft.client.",
			"net.fabricmc.fabric.api.client.",
			"net.neoforged.neoforge.client.",
			"ClientModInitializer",
			"RegisterClientPayloadHandlersEvent",
			"Dist.CLIENT"
		)

		val verifyTask = tasks.register("verifyMainSourceSetHasNoClientOnlyReferences") {
			group = "verification"
			description = "Fails when main source set imports or references client-only code."
			dependsOn("stonecutterGenerate")
			inputs.files(mainSourceSet.map { it.allJava })

			doLast {
				val offenders = mainSourceSet.get().allJava.files
					.asSequence()
					.filter { it.isFile }
					.flatMap { file ->
						val text = file.readText()
						forbiddenReferences.asSequence()
							.filter(text::contains)
							.map { reference -> "${file.relativeTo(rootProject.rootDir)} contains $reference" }
					}
					.toList()

				if (offenders.isNotEmpty()) {
					throw GradleException(
						"Client-only references must live in src/client, not the main source set:\n" +
								offenders.joinToString("\n")
					)
				}
			}
		}
		tasks.named("check") {
			dependsOn(verifyTask)
		}
	}

	private fun Project.registerGenerateManifestTask(ctx: Context) {
		val manifestOutputDir = layout.buildDirectory.dir("generated/modManifest")
		val generateTask = tasks.register<GenerateModManifestTask>("generateModManifest") {
			content.set(ctx.loader.generateManifest(ctx))
			outputFile.set(layout.buildDirectory.file("generated/modManifest/${ctx.loader.modManifestPath}"))
		}

		the<JavaPluginExtension>().sourceSets.named("main") { resources.srcDir(manifestOutputDir) }
		tasks.named<ProcessResources>("processResources") { dependsOn(generateTask) }
		tasks.withType<Jar>().configureEach {
			if (name == ctx.loader.sourcesJarTask) {
				dependsOn(generateTask)
			}
		}
	}

	@Suppress("UnstableApiUsage")
	private fun Project.configureProcessResources(ctx: Context) {
		tasks.named<ProcessResources>("processResources") {
			dependsOn(tasks.named("stonecutterGenerate"), "kspKotlin")

			val mcVersion = ctx.stonecutter.current.version.split("-")[0]
			val mixinsExt = project.extensions.findByType<MixinsExtension>()

			if (mixinsExt != null && mixinsExt.hasAnyMixins()) {
				val commonMixins = resolveMixinsForVersion(mixinsExt.common, mcVersion, ctx.stonecutter)
				val clientMixins = resolveMixinsForVersion(mixinsExt.client, mcVersion, ctx.stonecutter)
				val serverMixins = resolveMixinsForVersion(mixinsExt.server, mcVersion, ctx.stonecutter)

				val commonArray = commonMixins.toMixinJsonArray()
				val clientArray = clientMixins.toMixinJsonArray()
				val serverArray = serverMixins.toMixinJsonArray()

				logMixinConfiguration(
					logger = project.logger,
					mcVersion = mcVersion,
					commonCount = commonMixins.size,
					clientCount = clientMixins.size,
					serverCount = serverMixins.size
				)

				processMixinFiles(ctx, mapOf(
					"java" to "JAVA_${ctx.javaVersion.majorVersion}",
					"common_array" to commonArray,
					"client_array" to clientArray,
					"server_array" to serverArray
				))

				inputs.property("mcVersion", mcVersion)
				inputs.property("commonMixins", commonArray)
				inputs.property("clientMixins", clientArray)
				inputs.property("serverMixins", serverArray)
			} else {
				processMixinFiles(ctx, mapOf(
					"java" to "JAVA_${ctx.javaVersion.majorVersion}"
				))
			}

			exclude(ctx.loader.excludedResources)
		}
	}

	private fun ProcessResources.processMixinFiles(ctx: Context, expansionMap: Map<String, String>) {
		filesMatching("*.mixins.json") {
			expand(expansionMap)

			if (ctx.loader is Loader.Forge) {
				filter { line ->
					if (line.contains("\"package\"") && line.trim().endsWith(",")) {
						line + "\n    \"refmap\": \"${ctx.modId}.mixins.refmap.json\","
					} else {
						line
					}
				}
			}
		}
	}

	private fun Project.configureNativeResources(ctx: Context) {
		val generatedNativeResourcesDir = layout.buildDirectory.dir("generated/native-resources")

		val prepareNativeResources = tasks.register("prepareNativeResources") {
			outputs.dir(generatedNativeResourcesDir)
			outputs.upToDateWhen { false }

			doLast {
				val outDir = generatedNativeResourcesDir.get().asFile
				outDir.deleteRecursively()
				outDir.mkdirs()

				var packedCount = 0
				val root = rootProject.rootDir

				logger.lifecycle("🔍 prepareNativeResources: Searching from the roots: ${root.absolutePath}")

				val copyNative = { relativePath: String, target: String ->
					val src = root.resolve(relativePath)
					if (src.exists() && src.length() > 100_000) {
						val dst = outDir.resolve(target)
						dst.parentFile.mkdirs()
						dst.writeBytes(src.readBytes())
						packedCount++
						logger.lifecycle("   ✅ Copied: $relativePath → $target")
					} else if (src.exists()) {
						logger.lifecycle("   ⚠️ Small file: $relativePath")
					} else {
						logger.lifecycle("   ❌ Not found: $relativePath")
					}
				}

				copyNative("native/build/libaero_lbm.so", "natives/linux-x86_64/libaero_lbm.so")
				copyNative("native/build/aero_lbm.dll", "natives/windows-x86_64/aero_lbm.dll")
				copyNative("native/build/Release/aero_lbm.dll", "natives/windows-x86_64/aero_lbm.dll")
				copyNative("native/build-linux-arm64/libaero_lbm.so", "natives/linux-arm64/libaero_lbm.so")
				copyNative("native/build-macos-arm64/libaero_lbm.dylib", "natives/macos-arm64/libaero_lbm.dylib")
				copyNative("native/build-windows-x86_64/aero_lbm.dll", "natives/windows-x86_64/aero_lbm.dll")
				copyNative("native/dist/natives/linux-x86_64/libaero_lbm.so", "natives/linux-x86_64/libaero_lbm.so")
				copyNative("native/dist/natives/linux-arm64/libaero_lbm.so", "natives/linux-arm64/libaero_lbm.so")
				copyNative("native/dist/natives/windows-x86_64/aero_lbm.dll", "natives/windows-x86_64/aero_lbm.dll")
				copyNative("native/dist/natives/macos-arm64/libaero_lbm.dylib", "natives/macos-arm64/libaero_lbm.dylib")

				if (packedCount == 0) {
					logger.lifecycle("⚠️ No native binaries found")
				} else {
					logger.lifecycle("✅ Packaged $packedCount native file(s)")
				}
			}
		}

		tasks.named<ProcessResources>("processResources") {
			dependsOn(prepareNativeResources)
			from(generatedNativeResourcesDir) { into("") }
		}

		tasks.withType<Jar>().configureEach {
			if (name == "jar" || name == ctx.loader.jarTask) {
				dependsOn(prepareNativeResources)

				duplicatesStrategy = DuplicatesStrategy.INCLUDE

				from(generatedNativeResourcesDir) {
					into("")
				}
			}
		}
	}

	private fun Project.configureJarTask(ctx: Context) {
		val generateTask = tasks.named("generateModManifest")
		val coreJarTaskNames = setOf("jar", "sourcesJar", "javadocJar", ctx.loader.jarTask, ctx.loader.sourcesJarTask)
		tasks.withType<Jar>().configureEach {
			if (name in coreJarTaskNames) {
				archiveBaseName.set(ctx.modId)
				dependsOn(generateTask)
				if (ctx.loader is Loader.Forge) {
					manifest.attributes(ctx.loader.mixinConfigAttribute to "${ctx.modId}.mixins.json")
				}
			}
		}
	}

	private fun Project.configureIdea() {
		extensions.configure<IdeaModel>("idea") {
			module {
				isDownloadJavadoc = true
				isDownloadSources = true
			}
		}
	}

	private fun Project.configureFletchingTable(ctx: Context) {
		extensions.configure<FletchingTableExtension> {
			mixins.create("main") { mixin("default", "${ctx.modId}.mixins.json") }
			j52j.register("main") { extension("json", "**/*.json5") }
		}
	}

	private fun Project.registerBuildAndCollectTask(ctx: Context) {
		val contentJarTask = if (ctx.loader.isFabricLike) "remapContentJar" else "contentJar"
		val collectedArtifacts = mutableListOf<Any>(
			tasks.named(ctx.extension.jarTask.get()),
			tasks.named(ctx.extension.sourcesJarTask.get()),
			tasks.named("javadocJar"),
			tasks.named(contentJarTask),
			tasks.named("contentSourcesJar")
		)
		if (ctx.loader == Loader.NeoForge) {
			collectedArtifacts += tasks.named("createAeronauticsCompatJar")
			collectedArtifacts += tasks.named("createAeronauticsCompatSourcesJar")
		}
		tasks.register<Copy>("buildAndCollect") {
			from(collectedArtifacts)
			into(rootProject.layout.buildDirectory.file("libs/${ctx.basicVersion}"))
			dependsOn("build")
		}
	}
}
