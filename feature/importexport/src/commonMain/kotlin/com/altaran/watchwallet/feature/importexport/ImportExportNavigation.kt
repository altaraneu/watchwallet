package com.altaran.watchwallet.feature.importexport

import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import kotlinx.serialization.Serializable
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

/** Batch import. Optional prefilled text is handed over through [PendingImport]. */
@Serializable
data object ImportRoute

@Serializable
data object ExportRoute

/**
 * In-memory hand-off of text for the batch import screen (for example from the add-wallet
 * screen). Never passed as a navigation argument, which would be persisted in the saved back
 * stack state. The import screen consumes it once.
 */
class PendingImport {
    private var text: String? = null

    fun set(value: String) {
        text = value
    }

    /** Returns the pending text, if any, and forgets it. */
    fun consume(): String? = text.also { text = null }
}

fun NavGraphBuilder.importExportScreens(onNavigateUp: () -> Unit, onImportDone: () -> Unit) {
    composable<ImportRoute> { ImportRoute(onNavigateUp = onNavigateUp, onDone = onImportDone) }
    composable<ExportRoute> { ExportRoute(onNavigateUp = onNavigateUp) }
}

val importExportModule = module {
    single { PendingImport() }
    viewModelOf(::ImportViewModel)
    viewModelOf(::ExportViewModel)
}
