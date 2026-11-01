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
            implementation(projects.chain.common)
            implementation(projects.chain.litecoin)
            implementation(projects.chain.dogecoin)
            implementation(projects.chain.bitcoincash)
            implementation(projects.chain.solana)
            implementation(projects.chain.xrp)
            implementation(projects.chain.tron)
            implementation(projects.chain.ton)
            implementation(projects.chain.cardano)
            implementation(projects.chain.cosmos)
            implementation(projects.chain.polkadot)
            implementation(projects.chain.stellar)
            implementation(projects.chain.sui)
            implementation(projects.chain.aptos)
            implementation(projects.chain.near)
            implementation(projects.chain.hedera)
            implementation(projects.chain.algorand)
            implementation(projects.chain.tezos)
            implementation(projects.chain.kaspa)
            implementation(libs.kotlinx.coroutines.core)
            implementation(project.dependencies.platform(libs.koin.bom))
            api(libs.koin.core)
        }
        androidMain.dependencies {
            implementation(libs.secp256k1.kmp.jni.android)
        }
    }
}
