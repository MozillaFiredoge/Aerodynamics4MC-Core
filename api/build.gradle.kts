plugins {
    `java-library`
    `maven-publish`
}

group = "io.github.mozillafiredoge"
version = apiVersion()

base {
    archivesName.set("aerodynamics4mc-api")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
    withSourcesJar()
    withJavadocJar()
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
            artifactId = "aerodynamics4mc-api"

            pom {
                name.set("Aerodynamics4MC API")
                description.set("Stable Minecraft-free wind sampling API for Aerodynamics4MC integrations.")
                url.set("https://github.com/MozillaFiredoge/Aerodynamics4MC-Fabric")
                licenses {
                    license {
                        name.set("MIT")
                        url.set("https://opensource.org/licenses/MIT")
                    }
                }
                developers {
                    developer {
                        id.set("mozillafiredoge")
                        name.set("MozillaFiredoge")
                        url.set("https://github.com/MozillaFiredoge")
                    }
                }
                scm {
                    url.set("https://github.com/MozillaFiredoge/Aerodynamics4MC-Fabric")
                    connection.set("scm:git:git://github.com/MozillaFiredoge/Aerodynamics4MC-Fabric.git")
                    developerConnection.set("scm:git:ssh://git@github.com/MozillaFiredoge/Aerodynamics4MC-Fabric.git")
                }
            }
        }
    }

    repositories {
        maven {
            name = "GitHubPages"
            url = rootProject.layout.buildDirectory.dir("github-pages-maven/maven").get().asFile.toURI()
        }
    }
}

fun apiVersion(): String {
    val modVersion = stonecutterProperty("mod.version")
    val channelTag = stonecutterProperty("mod.channel_tag")
    return normalizeSemver(modVersion) + channelTag
}

fun stonecutterProperty(name: String): String {
    val pattern = Regex("""^${Regex.escape(name)}\s*=\s*"([^"]*)"""")
    return rootProject.file("stonecutter.properties.toml")
        .readLines()
        .firstNotNullOfOrNull { line -> pattern.find(line)?.groupValues?.get(1) }
        ?: error("Missing $name in stonecutter.properties.toml")
}

fun normalizeSemver(version: String): String {
    val numeric = Regex("""^(\d+)\.(\d+)$""")
    val match = numeric.matchEntire(version)
    return if (match == null) version else "${match.groupValues[1]}.${match.groupValues[2]}.0"
}
