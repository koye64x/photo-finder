import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.kotlin.dsl.getByType

internal val Project.libs: VersionCatalog
    get() = extensions.getByType<VersionCatalogsExtension>().named("libs")

internal fun VersionCatalog.version(alias: String): String = findVersion(alias).get().requiredVersion

internal fun VersionCatalog.library(alias: String) = findLibrary(alias).get()

/** Set in settings.gradle.kts: Android targets are configured only when an SDK is present. */
internal val Project.androidSdkAvailable: Boolean
    get() = gradle.extensions.extraProperties.let {
        it.has("photofinder.androidSdkAvailable") && it.get("photofinder.androidSdkAvailable") == true
    }

/** `:core:ml` -> `app.photofinder.core.ml` */
internal val Project.photofinderNamespace: String
    get() = "app.photofinder" + path.replace(':', '.').replace("-", "")
