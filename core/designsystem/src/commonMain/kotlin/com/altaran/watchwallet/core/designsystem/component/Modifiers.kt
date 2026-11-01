package com.altaran.watchwallet.core.designsystem.component

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** Makes long dialog content scrollable (for example at large font scales). */
@Composable
internal fun Modifier.verticalScrollIfNeeded(): Modifier = verticalScroll(rememberScrollState())
