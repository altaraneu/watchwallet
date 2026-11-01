plugins {
    alias(libs.plugins.watchwallet.kmp.library)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(projects.chain.api)
            implementation(projects.chain.common)
            implementation(projects.core.network)
            implementation(projects.core.crypto)
            implementation(libs.kotlincrypto.sha2)
        }
        commonTest.dependencies {
            implementation(libs.ktor.client.mock)
        }
    }
}
