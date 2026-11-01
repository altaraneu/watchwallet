plugins {
    alias(libs.plugins.watchwallet.kmp.feature)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(projects.core.importexport)
        }
        androidMain.dependencies {
            implementation(libs.androidx.activity.compose)
        }
    }
}
