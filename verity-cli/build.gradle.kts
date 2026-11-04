plugins {
    `java-library`
    alias(libs.plugins.shadow)
}

description = "Verity command line interface."

dependencies {
    api(project(":verity-core"))
    api(libs.picocli)
}

tasks.shadowJar {
    archiveBaseName.set("verity-cli")
    archiveClassifier.set("all")
    manifest {
        attributes["Main-Class"] = "dev.verity.cli.VerityCli"
        attributes["Implementation-Version"] = project.version
        attributes["Enable-Native-Access"] = "ALL-UNNAMED"
    }
    mergeServiceFiles()
}
