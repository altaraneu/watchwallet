package com.altaran.watchwallet

import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.altaran.watchwallet.core.data.platform.AppLanguageController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Per-app language via AppCompat: uses the system per-app language setting on Android 13+
 * and AppCompat's own storage on older versions.
 *
 * @param supportedLanguages comma-separated tags generated at build time from the translation folders.
 */
internal class AndroidAppLanguageController(supportedLanguages: String) : AppLanguageController {
    override val supportedLanguageTags: List<String> = supportedLanguages.split(',').filter(String::isNotBlank)

    private val selected = MutableStateFlow(currentSelection())
    override val selectedLanguageTag: StateFlow<String?> = selected.asStateFlow()

    override fun setLanguage(tag: String?) {
        selected.value = tag
        AppCompatDelegate.setApplicationLocales(
            if (tag == null) LocaleListCompat.getEmptyLocaleList() else LocaleListCompat.forLanguageTags(tag),
        )
    }

    /** Re-reads the selection, for example after the user changed it in system settings. */
    fun refresh() {
        selected.value = currentSelection()
    }

    private fun currentSelection(): String? =
        AppCompatDelegate.getApplicationLocales().takeUnless { it.isEmpty }?.get(0)?.toLanguageTag()
}
