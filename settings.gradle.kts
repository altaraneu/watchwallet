pluginManagement {
    includeBuild("build-logic")
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
    }
}

rootProject.name = "WatchWallet"
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

include(":app")
include(":shared")

include(":core:common")
include(":core:model")
include(":core:crypto")
include(":core:security")
include(":core:classifier")
include(":core:network")
include(":core:pricing")
include(":core:timeline")
include(":core:importexport")
include(":chain:api")
include(":chain:bitcoin")
include(":chain:evm")
include(":chain:common")
include(":chain:litecoin")
include(":chain:dogecoin")
include(":chain:bitcoincash")
include(":chain:solana")
include(":chain:xrp")
include(":chain:tron")
include(":chain:ton")
include(":chain:cardano")
include(":chain:cosmos")
include(":chain:polkadot")
include(":chain:stellar")
include(":chain:sui")
include(":chain:aptos")
include(":chain:near")
include(":chain:hedera")
include(":chain:algorand")
include(":chain:tezos")
include(":chain:kaspa")
include(":core:datastore")
include(":core:database")
include(":core:data")
include(":core:designsystem")
include(":core:chart")
include(":core:ui")

include(":feature:addwallet")
include(":feature:portfolio")
include(":feature:wallets")
include(":feature:markets")
include(":feature:settings")
include(":feature:donate")
include(":feature:importexport")
