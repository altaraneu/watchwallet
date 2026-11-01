import com.altaran.watchwallet.buildlogic.lib
import com.altaran.watchwallet.buildlogic.libs
import com.altaran.watchwallet.buildlogic.moduleNamespace
import com.android.build.api.dsl.KotlinMultiplatformAndroidLibraryTarget
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.ExtensionAware
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies
import org.jetbrains.compose.ComposeExtension
import org.jetbrains.compose.resources.ResourcesExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

/**
 * Adds Compose Multiplatform (UI + resources) to a `watchwallet.kmp.android.library` module.
 *
 * Every module gets its own generated, internal `Res` class in `<namespace>.generated.resources`,
 * so translations live next to the screens that use them.
 */
class KmpComposeConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("org.jetbrains.kotlin.plugin.compose")
        pluginManager.apply("org.jetbrains.compose")

        extensions.configure<KotlinMultiplatformExtension> {
            val android = (this as ExtensionAware).extensions.getByName("android")
                as KotlinMultiplatformAndroidLibraryTarget
            android.androidResources.enable = true

            sourceSets.getByName("commonMain").dependencies {
                implementation(libs.lib("compose-runtime"))
                implementation(libs.lib("compose-foundation"))
                implementation(libs.lib("compose-ui"))
                implementation(libs.lib("compose-material3"))
                implementation(libs.lib("compose-components-resources"))
                implementation(libs.lib("compose-ui-tooling-preview"))
            }
        }

        (extensions.getByType(ComposeExtension::class.java) as ExtensionAware)
            .extensions.configure<ResourcesExtension> {
                packageOfResClass = "${moduleNamespace()}.generated.resources"
                publicResClass = false
                generateResClass = ResourcesExtension.ResourceClassGeneration.Auto
            }

        dependencies {
            add("androidRuntimeClasspath", libs.lib("compose-ui-tooling"))
        }
    }
}
