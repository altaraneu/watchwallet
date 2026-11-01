package com.altaran.watchwallet.feature.importexport

import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import kotlinx.serialization.Serializable
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

/** Batch import; [prefill] is optional pasted text (for example from the add-wallet screen). */
@Serializable
data class ImportRoute(val prefill: String? = null)

@Serializable
data object ExportRoute

fun NavGraphBuilder.importExportScreens(onNavigateUp: () -> Unit, onImportDone: () -> Unit) {
    composable<ImportRoute> { entry ->
        ImportRoute(initialText = entry.toRoute<ImportRoute>().prefill, onNavigateUp = onNavigateUp, onDone = onImportDone)
    }
    composable<ExportRoute> { ExportRoute(onNavigateUp = onNavigateUp) }
}

val importExportModule = module {
    viewModelOf(::ImportViewModel)
    viewModelOf(::ExportViewModel)
}
