package com.altaran.watchwallet.buildlogic

import org.gradle.api.Project
import org.gradle.api.artifacts.MinimalExternalModuleDependency
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.provider.Provider
import org.gradle.kotlin.dsl.getByType

internal val Project.libs: VersionCatalog
    get() = extensions.getByType<VersionCatalogsExtension>().named("libs")

internal fun VersionCatalog.lib(alias: String): Provider<MinimalExternalModuleDependency> =
    findLibrary(alias).orElseThrow { IllegalArgumentException("Unknown library alias: $alias") }

internal fun VersionCatalog.intVersion(alias: String): Int =
    findVersion(alias).orElseThrow { IllegalArgumentException("Unknown version alias: $alias") }
        .requiredVersion
        .toInt()

/** Base package for all modules; module namespaces are derived from the Gradle path. */
internal const val BASE_PACKAGE = "com.altaran.watchwallet"

/** `:core:designsystem` -> `com.altaran.watchwallet.core.designsystem`. */
internal fun Project.moduleNamespace(): String =
    BASE_PACKAGE + path.replace(':', '.').replace('-', '_')
