import java.util.Properties

plugins {
    alias(libs.plugins.watchwallet.android.application)
}

/**
 * Languages bundled with the app, derived from the translation folders of `:shared`
 * (`values-cs` → `cs`, `values-pt-rBR` → `pt-BR`, `values-b+sr+Latn` → `sr-Latn`).
 * English is the source language. Adding a translation folder is all it takes to add a
 * language: the in-app picker and Android's per-app language list both read this.
 */
val supportedLanguages: List<String> = listOf("en") + rootProject
    .file("shared/src/commonMain/composeResources")
    .listFiles { file -> file.isDirectory && file.name.startsWith("values-") }
    .orEmpty()
    .map { it.name.removePrefix("values-").toLanguageTag() }
    .sorted()

fun String.toLanguageTag(): String = when {
    startsWith("b+") -> removePrefix("b+").replace('+', '-')
    contains("-r") -> replace("-r", "-")
    else -> this
}

android {
    namespace = "com.altaran.watchwallet"

    defaultConfig {
        applicationId = "com.altaran.watchwallet"
        versionCode = 100
        versionName = "1.0.0"

        val languages = supportedLanguages.joinToString(",")
        buildConfigField("String", "SUPPORTED_LANGUAGES", "\"$languages\"")
        buildConfigField("String", "SOURCE_CODE_URL", "\"https://github.com/altaraneu/watchwallet\"")
    }

    // The upload key stays outside the repository; without it release builds are simply unsigned
    // (F-Droid signs its own builds).
    val keystoreFile = rootProject.file("keystore.properties")
    val uploadSigning = if (keystoreFile.exists()) {
        val keystore = Properties().apply { keystoreFile.inputStream().use { load(it) } }
        signingConfigs.create("upload") {
            storeFile = file(keystore.getProperty("storeFile"))
            storePassword = keystore.getProperty("storePassword")
            keyAlias = keystore.getProperty("keyAlias")
            keyPassword = keystore.getProperty("keyPassword")
        }
    } else {
        null
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = uploadSigning
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // The file depends on the kind of checkout, so it would break reproducible builds.
            vcsInfo.include = false
        }
    }

    dependenciesInfo {
        // F-Droid / reproducible builds: no Google-encrypted dependency metadata blob.
        includeInApk = false
        includeInBundle = false
    }
}

abstract class GenerateLocaleConfigTask : DefaultTask() {
    @get:Input
    abstract val languages: ListProperty<String>

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun generate() {
        val xml = outputDirectory.get().dir("xml").asFile.apply { mkdirs() }.resolve("locales_config.xml")
        xml.writeText(
            buildString {
                appendLine("""<?xml version="1.0" encoding="utf-8"?>""")
                appendLine(
                    """<!-- Generated from the translation folders by :app:generateLocaleConfig. Do not edit. -->""",
                )
                appendLine("""<locale-config xmlns:android="http://schemas.android.com/apk/res/android">""")
                languages.get().forEach { appendLine("""    <locale android:name="$it" />""") }
                appendLine("</locale-config>")
            },
        )
    }
}

val generateLocaleConfig = tasks.register<GenerateLocaleConfigTask>("generateLocaleConfig") {
    languages.set(supportedLanguages)
}

androidComponents {
    onVariants { variant ->
        variant.sources.res?.addGeneratedSourceDirectory(
            generateLocaleConfig,
            GenerateLocaleConfigTask::outputDirectory,
        )
    }
}

dependencies {
    implementation(projects.shared)
    implementation(projects.core.designsystem)
    implementation(projects.core.data)
    implementation(projects.core.common)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.glance.appwidget)
    implementation(projects.core.ui)

    implementation(platform(libs.koin.bom))
    implementation(libs.koin.android)
    implementation(libs.koin.compose.viewmodel)

    implementation(libs.compose.runtime)
    implementation(libs.compose.ui)
    implementation(libs.jb.lifecycle.runtime.compose)
}
