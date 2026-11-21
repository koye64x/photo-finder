plugins {
    id("photofinder.kmp.compose")
}

kotlin {
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "ComposeApp"
            isStatic = true
        }
    }

    sourceSets.commonMain.dependencies {
        implementation(projects.feature.indexing)
        implementation(projects.feature.session)
        implementation(projects.feature.print)
        implementation(projects.feature.share)
    }
}
