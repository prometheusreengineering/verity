plugins {
    `java-library`
}

description = "Verity engine: metadata, installers, launching, orchestration, reports."

dependencies {
    api(libs.jackson.databind)
    implementation(libs.jackson.toml)
    implementation(libs.commons.compress)
    implementation(libs.xz)
    implementation(libs.jna)
    implementation(libs.jna.platform)
    testImplementation(project(":verity-api"))
}
