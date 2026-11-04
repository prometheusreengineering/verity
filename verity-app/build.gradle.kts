plugins {
    application
    alias(libs.plugins.javafx)
    alias(libs.plugins.shadow)
}

description = "Verity application: desktop UI when started without arguments, CLI otherwise."

javafx {
    version = libs.versions.javafx.get()
    modules = listOf("javafx.controls")
}

dependencies {
    implementation(project(":verity-cli"))
    implementation(project(":verity-ui"))
}

application {
    applicationName = "verity"
    mainClass.set("dev.verity.app.Main")
    applicationDefaultJvmArgs = listOf("-Xmx512m", "--enable-native-access=ALL-UNNAMED")
}

val osName: String = System.getProperty("os.name").lowercase().let {
    when {
        it.contains("win") -> "windows"
        it.contains("mac") -> "macos"
        else -> "linux"
    }
}

tasks.shadowJar {
    archiveBaseName.set("verity")
    archiveClassifier.set(osName)
    manifest {
        attributes["Main-Class"] = "dev.verity.app.Main"
        attributes["Implementation-Version"] = project.version
        attributes["Enable-Native-Access"] = "ALL-UNNAMED"
    }
    mergeServiceFiles()
}

// Self-contained app image (bundled Java runtime) built with the JDK's jpackage.
val jpackageImage by tasks.registering(Exec::class) {
    group = "distribution"
    description = "Builds a native app image with jpackage (current OS)."
    dependsOn(tasks.shadowJar)
    val out = layout.buildDirectory.dir("jpackage")
    val jar = tasks.shadowJar.flatMap { it.archiveFile }
    val launcher = javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) }
    doFirst {
        delete(out)
        val jdk = launcher.get().metadata.installationPath.asFile
        val exe = if (osName == "windows") "jpackage.exe" else "jpackage"
        commandLine(
            jdk.resolve("bin").resolve(exe).absolutePath,
            "--type", "app-image",
            "--name", "Verity",
            "--app-version", project.version.toString().substringBefore("-"),
            "--input", jar.get().asFile.parent,
            "--main-jar", jar.get().asFile.name,
            "--main-class", "dev.verity.app.Main",
            "--java-options", "-Xmx512m",
            "--dest", out.get().asFile.absolutePath,
        )
    }
}
