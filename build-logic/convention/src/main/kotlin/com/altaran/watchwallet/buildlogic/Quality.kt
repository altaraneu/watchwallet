package com.altaran.watchwallet.buildlogic

import dev.detekt.gradle.extensions.DetektExtension
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.jlleitschuh.gradle.ktlint.KtlintExtension

/**
 * Static analysis for every module: ktlint (formatting, rules in `.editorconfig`) and
 * detekt (code smells, rules in `config/detekt/detekt.yml`). Both run in CI.
 */
internal fun Project.configureQuality() {
    pluginManager.apply("org.jlleitschuh.gradle.ktlint")
    pluginManager.apply("dev.detekt")

    extensions.configure<KtlintExtension> {
        version.set(libs.findVersion("ktlint").get().requiredVersion)
        filter {
            exclude { element -> element.file.path.contains("${java.io.File.separator}build${java.io.File.separator}") }
        }
    }

    extensions.configure<DetektExtension> {
        buildUponDefaultConfig.set(true)
        parallel.set(true)
        config.setFrom(rootProject.file("config/detekt/detekt.yml"))
        // Multiplatform source sets: src/commonMain/kotlin, src/androidMain/kotlin, ...
        source.setFrom(
            projectDir.resolve("src").listFiles().orEmpty()
                .map { it.resolve("kotlin") }
                .filter { it.isDirectory },
        )
    }
}
