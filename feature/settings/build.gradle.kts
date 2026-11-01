plugins {
    alias(libs.plugins.watchwallet.kmp.feature)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            // Only for `proxySupported`: the proxy option is hidden where the engine ignores it.
            implementation(projects.core.network)
        }
    }
}
