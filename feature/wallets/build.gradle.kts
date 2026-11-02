plugins {
    alias(libs.plugins.watchwallet.kmp.feature)
}

kotlin {
    sourceSets {
        androidMain.dependencies {
            implementation(libs.zxing.core)
        }
    }
}
