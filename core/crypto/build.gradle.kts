plugins {
    alias(libs.plugins.watchwallet.kmp.library)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(projects.core.model)
            api(libs.bitcoin.kmp)
            implementation(libs.kotlincrypto.sha3)
        }
        jvmMain.dependencies {
            // Native secp256k1 for public key derivation (the Android app uses the -android variant).
            implementation(libs.secp256k1.kmp.jni.jvm)
        }
    }
}
