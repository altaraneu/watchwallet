plugins {
    alias(libs.plugins.watchwallet.kmp.library)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(projects.chain.api)
            api(projects.core.network)
            api(projects.core.crypto)
        }
        commonTest.dependencies {
            implementation(libs.ktor.client.mock)
        }
    }
}
