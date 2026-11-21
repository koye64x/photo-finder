plugins {
    id("photofinder.kmp.library")
}

kotlin {
    sourceSets.commonMain.dependencies {
        api(projects.core.ml)
        api(projects.core.db)
        api(projects.core.storage)
    }
}
