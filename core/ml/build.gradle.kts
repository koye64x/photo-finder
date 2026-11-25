plugins {
    id("photofinder.kmp.library")
}

kotlin {
    sourceSets.jvmMain.dependencies {
        // Same runtime as Android (onnxruntime-android) and iOS; no ML Kit / Apple Vision (AGENTS.md, rule 5).
        implementation(libs.onnxruntime)
    }
}
