import dev.detekt.gradle.extensions.DetektExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.jlleitschuh.gradle.ktlint.KtlintExtension

/** ktlint (formatting) + detekt (static analysis) with shared config from `config/detekt/detekt.yml`. */
class LintConventionPlugin : Plugin<Project> {
    override fun apply(target: Project): Unit = with(target) {
        pluginManager.apply("org.jlleitschuh.gradle.ktlint")
        pluginManager.apply("dev.detekt")

        extensions.configure<KtlintExtension> {
            version.set(libs.version("ktlint"))
            filter {
                exclude { it.file.path.contains("${java.io.File.separator}build${java.io.File.separator}") }
            }
        }

        extensions.configure<DetektExtension> {
            buildUponDefaultConfig.set(true)
            config.setFrom(rootProject.file("config/detekt/detekt.yml"))
            parallel.set(true)
        }

        // In KMP modules plain `detekt` only covers src/main; make it run every per-source-set task
        // (commonMain, jvmMain, iosMain, ...) so `./gradlew detekt` and `check` really analyze the code.
        tasks.named("detekt") {
            dependsOn(
                tasks.matching {
                    it.name.startsWith("detekt") && it.name.endsWith("SourceSet") && !it.name.startsWith("detektBaseline")
                },
            )
        }
        tasks.named("check") { dependsOn("detekt") }
    }
}
