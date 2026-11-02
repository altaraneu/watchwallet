plugins {
    alias(libs.plugins.watchwallet.kmp.library)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(projects.core.model)
            api(projects.core.network)
            implementation(projects.core.crypto)
            implementation(libs.secp256k1.kmp)
        }
        jvmMain.dependencies {
            // Native secp256k1 for tests on the JVM (the Android app uses the -android variant).
            implementation(libs.secp256k1.kmp.jni.jvm)
        }
        commonTest.dependencies {
            implementation(libs.ktor.client.mock)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
