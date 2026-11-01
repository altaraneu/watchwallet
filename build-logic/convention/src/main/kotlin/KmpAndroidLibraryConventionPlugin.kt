import com.altaran.watchwallet.buildlogic.configureQuality
import com.altaran.watchwallet.buildlogic.JVM_TARGET
import com.altaran.watchwallet.buildlogic.configureKotlinMultiplatform
import com.altaran.watchwallet.buildlogic.intVersion
import com.altaran.watchwallet.buildlogic.lib
import com.altaran.watchwallet.buildlogic.libs
import com.altaran.watchwallet.buildlogic.moduleNamespace
import com.android.build.api.dsl.KotlinMultiplatformAndroidLibraryTarget
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.ExtensionAware
import org.gradle.kotlin.dsl.configure
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

/**
 * Multiplatform module that also needs the Android framework (Room, DataStore, resources…):
 * `android` + iOS targets, built with AGP's Kotlin Multiplatform library plugin.
 */
class KmpAndroidLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("org.jetbrains.kotlin.multiplatform")
        pluginManager.apply("com.android.kotlin.multiplatform.library")

        extensions.configure<KotlinMultiplatformExtension> {
            val android = (this as ExtensionAware).extensions.getByName("android")
                as KotlinMultiplatformAndroidLibraryTarget
            android.apply {
                namespace = moduleNamespace()
                compileSdk = libs.intVersion("compileSdk")
                minSdk = libs.intVersion("minSdk")
                compilerOptions.jvmTarget.set(JVM_TARGET)
                withHostTest {
                    isIncludeAndroidResources = true
                }
            }
            sourceSets.getByName("androidHostTest").dependencies {
                implementation(libs.lib("junit4"))
                implementation(libs.lib("robolectric"))
            }
        }
        configureKotlinMultiplatform()
        configureQuality()
    }
}
