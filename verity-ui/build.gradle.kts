plugins {
    `java-library`
    alias(libs.plugins.javafx)
}

description = "Verity desktop UI (JavaFX)."

javafx {
    version = libs.versions.javafx.get()
    modules = listOf("javafx.controls")
}

dependencies {
    api(project(":verity-core"))
    implementation(libs.atlantafx)
}
