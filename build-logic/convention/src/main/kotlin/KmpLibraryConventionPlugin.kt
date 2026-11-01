import com.altaran.watchwallet.buildlogic.configureQuality
import com.altaran.watchwallet.buildlogic.configureKotlinMultiplatform
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

/**
 * Pure Kotlin multiplatform module (no Android framework): `jvm` + iOS targets.
 *
 * Android modules consume the `jvm` variant. Use this for domain logic such as models,
 * address classification, secret detection and crypto helpers; tests run on the JVM.
 */
class KmpLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("org.jetbrains.kotlin.multiplatform")
        extensions.configure<KotlinMultiplatformExtension> {
            jvm()
        }
        configureKotlinMultiplatform()
        configureQuality()
    }
}
