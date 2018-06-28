import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

description = "Kotlin JPS plugin"

plugins {
    `java-base`
    id("pill-configurable")
}

val projectsToShadow = listOf(
        ":kotlin-build-common",
        ":compiler:cli-common",
        ":kotlin-compiler-runner",
        ":compiler:daemon-common",
        ":compiler:daemon-common-new",
        ":core:descriptors",
        ":core:descriptors.jvm",
        ":idea:idea-jps-common",
        ":jps-plugin",
        ":kotlin-preloader",
        ":compiler:util",
        ":core:util.runtime",
        ":plugins:android-extensions-jps")

dependencies {
    projectsToShadow.forEach {
        embeddedComponents(project(it)) { isTransitive = false }
    }
    embeddedComponents(projectRuntimeJar(":kotlin-daemon-client"))
}

runtimeJar<ShadowJar>(task<ShadowJar>("jar")) {
    manifest.attributes.put("Main-Class", "org.jetbrains.kotlin.runner.Main")
    manifest.attributes.put("Class-Path", "kotlin-stdlib.jar")
    from(files("$rootDir/resources/kotlinManifest.properties"))
    fromEmbeddedComponents()
}

ideaPlugin("lib/jps")
