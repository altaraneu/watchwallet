import com.altaran.watchwallet.buildlogic.lib
import com.altaran.watchwallet.buildlogic.libs
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.project
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

/**
 * A feature module: multiplatform Android library + Compose + the shared core modules,
 * Koin, lifecycle-aware ViewModels and type-safe navigation.
 */
class KmpFeatureConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("watchwallet.kmp.android.library")
        pluginManager.apply("watchwallet.kmp.compose")
        pluginManager.apply("org.jetbrains.kotlin.plugin.serialization")

        extensions.configure<KotlinMultiplatformExtension> {
            sourceSets.getByName("commonMain").dependencies {
                implementation(project(":core:common"))
                implementation(project(":core:model"))
                implementation(project(":core:data"))
                implementation(project(":core:designsystem"))
                implementation(project(":core:ui"))

                implementation(project.dependencies.platform(libs.lib("koin-bom")))
                implementation(libs.lib("koin-core"))
                implementation(libs.lib("koin-compose"))
                implementation(libs.lib("koin-compose-viewmodel"))
                implementation(libs.lib("koin-compose-viewmodel-navigation"))

                implementation(libs.lib("jb-lifecycle-viewmodel-compose"))
                implementation(libs.lib("jb-lifecycle-runtime-compose"))
                implementation(libs.lib("jb-navigation-compose"))
                implementation(libs.lib("kotlinx-serialization-json"))
                implementation(libs.lib("kotlinx-collections-immutable"))
                implementation(libs.lib("kotlinx-coroutines-core"))
            }
        }
    }
}
