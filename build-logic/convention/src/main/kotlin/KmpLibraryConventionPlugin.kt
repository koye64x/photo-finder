import com.android.build.api.dsl.KotlinMultiplatformAndroidLibraryTarget
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.ExtensionAware
import org.gradle.kotlin.dsl.configure
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

/**
 * Kotlin Multiplatform library with targets: jvm (tests + desktop), android (if SDK present),
 * iosArm64, iosSimulatorArm64. All logic goes to commonMain; platform source sets hold thin adapters.
 */
class KmpLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project): Unit = with(target) {
        pluginManager.apply("org.jetbrains.kotlin.multiplatform")
        if (androidSdkAvailable) pluginManager.apply("com.android.kotlin.multiplatform.library")
        pluginManager.apply(LintConventionPlugin::class.java)

        val jvmTarget = JvmTarget.fromTarget(libs.version("jvm-target"))

        extensions.configure<KotlinMultiplatformExtension> {
            jvm {
                compilerOptions { this.jvmTarget.set(jvmTarget) }
            }

            if (androidSdkAvailable) {
                (this as ExtensionAware).extensions.configure<KotlinMultiplatformAndroidLibraryTarget>("android") {
                    namespace = photofinderNamespace
                    compileSdk = libs.version("android-compileSdk").toInt()
                    minSdk = libs.version("android-minSdk").toInt()
                    compilerOptions { this.jvmTarget.set(jvmTarget) }
                    withHostTest {}
                    withDeviceTest {
                        instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
                    }
                }
            }

            iosArm64()
            iosSimulatorArm64()

            sourceSets.getByName("commonMain").dependencies {
                implementation(libs.library("kotlinx-coroutines-core"))
            }
            sourceSets.getByName("commonTest").dependencies {
                implementation(libs.library("kotlin-test"))
                implementation(libs.library("kotlinx-coroutines-test"))
            }
        }
    }
}
