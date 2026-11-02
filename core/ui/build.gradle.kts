plugins {
    alias(libs.plugins.watchwallet.kmp.android.library)
    alias(libs.plugins.watchwallet.kmp.compose)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(projects.core.model)
            implementation(projects.core.security)
            implementation(projects.core.designsystem)
            api(projects.core.chart)
            implementation(libs.kotlinx.datetime)
        }
    }
}
