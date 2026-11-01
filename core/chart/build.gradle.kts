plugins {
    alias(libs.plugins.watchwallet.kmp.android.library)
    alias(libs.plugins.watchwallet.kmp.compose)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(projects.core.designsystem)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.coroutines.core)
        }
    }
}
