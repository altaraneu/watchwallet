package com.altaran.watchwallet.buildlogic

import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

internal val JVM_TARGET = JvmTarget.JVM_21

/**
 * Shared configuration for every multiplatform module.
 *
 * iOS targets are always declared so that `commonMain` cannot accidentally use JVM-only
 * APIs. They are compiled on macOS (CI); other hosts skip them.
 */
internal fun Project.configureKotlinMultiplatform() {
    extensions.configure<KotlinMultiplatformExtension> {
        iosArm64()
        iosSimulatorArm64()

        applyDefaultHierarchyTemplate()

        compilerOptions {
            freeCompilerArgs.addAll(
                "-Xexpect-actual-classes",
            )
            optIn.addAll(
                "kotlin.time.ExperimentalTime",
            )
        }

        sourceSets.getByName("commonTest").dependencies {
            implementation(libs.lib("kotlin-test"))
            implementation(libs.lib("kotlinx-coroutines-test"))
            implementation(libs.lib("turbine"))
        }
    }
    configureJvmTarget()
}

internal fun Project.configureJvmTarget() {
    tasks.withType<KotlinJvmCompile>().configureEach {
        compilerOptions.jvmTarget.set(JVM_TARGET)
    }
    tasks.withType<JavaCompile>().configureEach {
        sourceCompatibility = JavaVersion.VERSION_21.toString()
        targetCompatibility = JavaVersion.VERSION_21.toString()
    }
    tasks.withType<KotlinCompilationTask<*>>().configureEach {
        compilerOptions.allWarningsAsErrors.set(false)
    }
}
