package com.altaran.watchwallet.core.data.repository

import com.altaran.watchwallet.core.datastore.SettingsDataSource
import com.altaran.watchwallet.core.pricing.PriceSourceStore
import kotlinx.coroutines.flow.Flow

/** Keeps the price source marks next to the settings; no database schema is involved. */
internal class SettingsPriceSourceStore(private val settings: SettingsDataSource) : PriceSourceStore {
    override val marks: Flow<Set<String>> = settings.priceSourceMarks

    override suspend fun update(transform: (Set<String>) -> Set<String>) = settings.updatePriceSourceMarks(transform)
}
