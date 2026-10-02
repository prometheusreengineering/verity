plugins {
    `java-library`
    `maven-publish`
}

description = "Tiny dependency-free API mods use to report test results to Verity. Safe to shade."

tasks.named<JavaCompile>("compileJava") {
    // Mods targeting 1.7.10/1.8.9 still run on Java 8.
    options.release.set(8)
    options.compilerArgs.removeAll(listOf("-Xlint:all,-serial,-processing,-options"))
    options.compilerArgs.add("-Xlint:-options")
}

java {
    withSourcesJar()
    withJavadocJar()
}

tasks.withType<Javadoc>().configureEach {
    (options as StandardJavadocDocletOptions).addStringOption("Xdoclint:none", "-quiet")
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            artifactId = "verity-api"
            from(components["java"])
        }
    }
}
