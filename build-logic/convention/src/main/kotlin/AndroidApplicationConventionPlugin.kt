import com.altaran.watchwallet.buildlogic.configureQuality
import com.altaran.watchwallet.buildlogic.configureJvmTarget
import com.altaran.watchwallet.buildlogic.intVersion
import com.altaran.watchwallet.buildlogic.libs
import com.android.build.api.dsl.ApplicationExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

/** The Android application shell (`:app`). Uses AGP's built-in Kotlin support. */
class AndroidApplicationConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("com.android.application")
        pluginManager.apply("org.jetbrains.kotlin.plugin.compose")

        extensions.configure<ApplicationExtension> {
            compileSdk = libs.intVersion("compileSdk")
            defaultConfig {
                minSdk = libs.intVersion("minSdk")
                targetSdk = libs.intVersion("targetSdk")
                testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
            }
            compileOptions {
                sourceCompatibility = JavaVersion.VERSION_21
                targetCompatibility = JavaVersion.VERSION_21
            }
            buildFeatures {
                compose = true
                buildConfig = true
            }
            packaging {
                resources.excludes += setOf(
                    "/META-INF/{AL2.0,LGPL2.1}",
                    "/META-INF/versions/9/previous-compilation-data.bin",
                    // Desktop builds of secp256k1, which the JVM target of :core:crypto pulls in. The
                    // app loads its own from lib/ (secp256k1-kmp-jni-android), never these.
                    "/fr/acinq/secp256k1/jni/native/**",
                )
            }
        }
        configureJvmTarget()
        configureQuality()
    }
}
