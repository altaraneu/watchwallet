plugins {
    alias(libs.plugins.watchwallet.kmp.android.library)
    alias(libs.plugins.watchwallet.kmp.room)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(projects.core.common)
            implementation(projects.core.model)
            implementation(libs.kotlinx.coroutines.core)
            implementation(project.dependencies.platform(libs.koin.bom))
            implementation(libs.koin.core)
        }
        androidMain.dependencies {
            implementation(libs.koin.android)
        }
        getByName("androidHostTest").dependencies {
            implementation(libs.kotlinx.serialization.json)
            // Desktop natives of the bundled SQLite (the Android artifact only ships .so files).
            implementation(libs.androidx.sqlite.bundled.jvm)
        }
    }
}
