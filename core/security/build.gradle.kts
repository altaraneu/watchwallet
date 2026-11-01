plugins {
    alias(libs.plugins.watchwallet.kmp.library)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(libs.bitcoin.kmp)
        }
        jvmTest.dependencies {
            implementation(libs.secp256k1.kmp.jni.jvm)
        }
    }
}
