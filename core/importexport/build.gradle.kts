plugins {
    alias(libs.plugins.watchwallet.kmp.library)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(projects.core.model)
            api(projects.core.classifier)
            implementation(libs.kotlinx.serialization.json)
        }
    }
}
