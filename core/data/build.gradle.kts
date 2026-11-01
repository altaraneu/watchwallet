plugins {
    alias(libs.plugins.watchwallet.kmp.android.library)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(projects.core.model)
            implementation(projects.core.common)
            implementation(projects.core.datastore)
            implementation(projects.core.database)
            implementation(libs.kotlinx.coroutines.core)
            implementation(project.dependencies.platform(libs.koin.bom))
            api(libs.koin.core)
        }
    }
}
