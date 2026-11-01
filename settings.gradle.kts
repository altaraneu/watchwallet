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
include(":core:datastore")
include(":core:database")
include(":core:data")
include(":core:designsystem")
include(":core:ui")

include(":feature:portfolio")
include(":feature:wallets")
include(":feature:markets")
include(":feature:settings")
include(":feature:donate")
