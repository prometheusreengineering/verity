plugins {
    `java-library`
}

description = "Verity engine: metadata, installers, launching, orchestration, reports."

dependencies {
    api(libs.jackson.databind)
    api(libs.jackson.jsr310)
    implementation(libs.jackson.toml)
    implementation(libs.commons.compress)
    implementation(libs.xz)
    implementation(libs.jna)
    implementation(libs.jna.platform)
    implementation(project(":verity-api"))
}

tasks.test {
    // A credential Verity has in its environment, which the fake games launched by RunOrchestratorTest must not see.
    environment("VERITY_MSA_REFRESH_TOKEN", "test-refresh-token-must-not-reach-the-game")
}
