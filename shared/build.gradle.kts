import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

plugins {
    alias(libs.plugins.watchwallet.kmp.feature)
}

kotlin {
    // The future iosApp (Xcode) embeds this framework; Android uses the module directly.
    targets.withType<KotlinNativeTarget>().configureEach {
        binaries.framework {
            baseName = "Shared"
            isStatic = true
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(projects.core.data)
            implementation(projects.feature.portfolio)
            implementation(projects.feature.wallets)
            implementation(projects.feature.markets)
            implementation(projects.feature.settings)
            implementation(projects.feature.donate)
        }
    }
}
