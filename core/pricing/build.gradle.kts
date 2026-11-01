plugins {
    alias(libs.plugins.watchwallet.kmp.library)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(projects.core.model)
            implementation(projects.core.network)
        }
        commonTest.dependencies {
            implementation(libs.ktor.client.mock)
        }
    }
}
