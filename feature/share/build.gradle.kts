plugins {
    id("photofinder.kmp.library")
}

kotlin {
    sourceSets.commonMain.dependencies {
        api(projects.core.storage)
    }
}
