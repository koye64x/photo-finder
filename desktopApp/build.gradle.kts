import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose.multiplatform)
    id("photofinder.lint")
}

// Desktop (JVM) shell: lets agents run and inspect the shared UI on Windows without an emulator.
kotlin {
    compilerOptions {
        jvmTarget.set(
            JvmTarget.fromTarget(
                libs.versions.jvm.target
                    .get(),
            ),
        )
    }
}

java {
    sourceCompatibility =
        JavaVersion.toVersion(
            libs.versions.jvm.target
                .get(),
        )
    targetCompatibility =
        JavaVersion.toVersion(
            libs.versions.jvm.target
                .get(),
        )
}

dependencies {
    implementation(projects.composeApp)
    implementation(compose.desktop.currentOs)
    implementation(libs.kotlinx.coroutines.swing)
}

compose.desktop {
    application {
        mainClass = "app.photofinder.desktop.MainKt"
    }
}
