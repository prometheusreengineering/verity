plugins {
    java
}

allprojects {
    group = "dev.verity"
    version = "0.1.0-SNAPSHOT"
    repositories { mavenCentral() }
}

subprojects {
    apply(plugin = "java")

    java {
        toolchain { languageVersion.set(JavaLanguageVersion.of(21)) }
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.compilerArgs.addAll(listOf("-Xlint:all,-serial,-processing,-options", "-parameters"))
    }

    dependencies {
        val libs = rootProject.extensions.getByType<VersionCatalogsExtension>().named("libs")
        "testImplementation"(platform(libs.findLibrary("junit-bom").get()))
        "testImplementation"(libs.findLibrary("junit-jupiter").get())
        "testImplementation"(libs.findLibrary("assertj").get())
        "testRuntimeOnly"(libs.findLibrary("junit-launcher").get())
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform {
            // Network/game-launching tests only run when explicitly requested (CI e2e job).
            if (!project.hasProperty("e2e")) excludeTags("e2e")
        }
        testLogging { events("failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
    }
}
