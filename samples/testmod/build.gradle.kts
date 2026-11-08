plugins {
    java
    alias(libs.plugins.shadow)
}

description = "A universal (any version, any loader) test mod showing how to report to Verity."

// Loader annotations/interfaces are compile-time stubs only; the real ones come from the loader at runtime.
val stubs: SourceSet = sourceSets.create("stubs")

dependencies {
    compileOnly(stubs.output)
    implementation(project(":verity-api"))
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(8)
    options.compilerArgs.removeAll(listOf("-Xlint:all,-serial,-processing,-options"))
    options.compilerArgs.add("-Xlint:-options")
}

tasks.processResources {
    inputs.property("version", project.version)
    filesMatching(listOf("fabric.mod.json", "mcmod.info", "META-INF/mods.toml", "META-INF/neoforge.mods.toml")) {
        expand("version" to project.version)
    }
}

tasks.jar { enabled = false }

tasks.shadowJar {
    archiveBaseName.set("verity-testmod")
    archiveClassifier.set("")
    // Relocate so several mods can each shade verity-api without clashing on Forge/NeoForge's module layer.
    relocate("ing.prometheusreengineer.verity.api", "ing.prometheusreengineer.verity.sample.shadow.api")
    manifest {
        attributes(
            "Implementation-Version" to project.version,
            "Automatic-Module-Name" to "ing.prometheusreengineer.verity.sample",
        )
    }
}

tasks.assemble { dependsOn(tasks.shadowJar) }
