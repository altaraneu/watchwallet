plugins {
    alias(libs.plugins.watchwallet.kmp.android.library)
    alias(libs.plugins.watchwallet.kmp.compose)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(libs.compose.material3)
            api(libs.compose.components.resources)
            api(projects.core.model)
            implementation(libs.kotlinx.collections.immutable)
            api(libs.coil.compose)
        }
        androidMain.dependencies {
            implementation(libs.androidx.core.ktx)
        }
    }
}
