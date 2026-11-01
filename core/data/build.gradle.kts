plugins {
    alias(libs.plugins.watchwallet.kmp.android.library)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(projects.core.model)
            api(projects.core.classifier)
            api(projects.chain.api)
            implementation(projects.core.common)
            implementation(projects.core.datastore)
            implementation(projects.core.database)
            implementation(projects.core.network)
            api(projects.core.pricing)
            api(projects.core.timeline)
            implementation(projects.chain.bitcoin)
            implementation(projects.chain.evm)
            implementation(libs.kotlinx.coroutines.core)
            implementation(project.dependencies.platform(libs.koin.bom))
            api(libs.koin.core)
        }
        androidMain.dependencies {
            implementation(libs.secp256k1.kmp.jni.android)
        }
    }
}
