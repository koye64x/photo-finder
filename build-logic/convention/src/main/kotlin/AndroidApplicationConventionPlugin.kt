import com.android.build.api.dsl.ApplicationExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

/** Android application shell (AGP built-in Kotlin) with Compose. Only `:androidApp` uses it. */
class AndroidApplicationConventionPlugin : Plugin<Project> {
    override fun apply(target: Project): Unit = with(target) {
        pluginManager.apply("com.android.application")
        pluginManager.apply("org.jetbrains.kotlin.plugin.compose")
        pluginManager.apply(LintConventionPlugin::class.java)

        val javaVersion = JavaVersion.toVersion(libs.version("jvm-target"))

        extensions.configure<ApplicationExtension> {
            namespace = "app.photofinder"
            compileSdk = libs.version("android-compileSdk").toInt()
            defaultConfig {
                applicationId = "app.photofinder"
                minSdk = libs.version("android-minSdk").toInt()
                targetSdk = libs.version("android-targetSdk").toInt()
                versionCode = 1
                versionName = "0.1.0"
            }
            compileOptions {
                sourceCompatibility = javaVersion
                targetCompatibility = javaVersion
            }
            buildFeatures { compose = true }
        }
    }
}
