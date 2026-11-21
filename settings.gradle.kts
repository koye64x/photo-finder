rootProject.name = "photo-finder"

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

pluginManagement {
    includeBuild("build-logic")
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

// Android targets are enabled only when an Android SDK is available.
// Without it the JVM/desktop part still builds and tests (agent loop on any machine).
val androidSdkAvailable: Boolean = run {
    val localProps = file("local.properties")
    val sdkFromLocal = if (localProps.exists()) {
        localProps.readLines()
            .firstOrNull { it.startsWith("sdk.dir=") }
            ?.substringAfter("sdk.dir=")
            ?.replace("\\:", ":")
            ?.replace("\\\\", "\\")
    } else {
        null
    }
    listOfNotNull(sdkFromLocal, System.getenv("ANDROID_HOME"), System.getenv("ANDROID_SDK_ROOT"))
        .any { file(it).isDirectory }
}
gradle.extensions.extraProperties["photofinder.androidSdkAvailable"] = androidSdkAvailable
if (!androidSdkAvailable) {
    logger.warn("photo-finder: Android SDK not found, Android targets and :androidApp are disabled.")
}

include(":composeApp")
include(":desktopApp")
if (androidSdkAvailable) include(":androidApp")

include(":core:ml")
include(":core:db")
include(":core:storage")
include(":core:camera")

include(":feature:indexing")
include(":feature:session")
include(":feature:print")
include(":feature:share")
