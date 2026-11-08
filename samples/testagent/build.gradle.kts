plugins {
    java
    alias(libs.plugins.shadow)
}

description = "A Java agent that reports to Verity once the game reaches its main menu, on vanilla and every loader."

dependencies {
    implementation(project(":verity-api"))
}

tasks.withType<JavaCompile>().configureEach {
    if (name == "compileJava") {
        // Old Minecraft runs on Java 8.
        options.release.set(8)
        options.compilerArgs.removeAll(listOf("-Xlint:all,-serial,-processing,-options"))
        options.compilerArgs.add("-Xlint:-options")
    }
}

tasks.jar { enabled = false }

tasks.shadowJar {
    archiveBaseName.set("verity-testagent")
    archiveClassifier.set("")
    // Its own package, so it never clashes with a mod's copy of verity-api (the sample mod relocates to another one).
    relocate("ing.prometheusreengineer.verity.api", "ing.prometheusreengineer.verity.sampleagent.shadow.api")
    manifest {
        attributes(
            "Premain-Class" to "ing.prometheusreengineer.verity.sampleagent.AgentEntry",
            "Implementation-Version" to project.version,
        )
    }
}

tasks.assemble { dependsOn(tasks.shadowJar) }
