plugins {
    id("photofinder.android.application")
}

dependencies {
    implementation(projects.composeApp)
    implementation(libs.androidx.activity.compose)
}
