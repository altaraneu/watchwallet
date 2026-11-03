package com.altaran.watchwallet.widget

import android.content.Context
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.altaran.watchwallet.MainActivity
import com.altaran.watchwallet.R
import com.altaran.watchwallet.core.data.repository.PortfolioRepository
import com.altaran.watchwallet.core.data.repository.SettingsRepository
import com.altaran.watchwallet.core.data.repository.usesCoinGecko
import com.altaran.watchwallet.core.ui.format.MoneyFormatter
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * Home-screen widget with the total portfolio value and its 24-hour change. With discreet mode or
 * the app lock on, amounts are replaced by dots. Data comes from the local cache; no network access here.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PortfolioWidget : GlanceAppWidget(), KoinComponent {
    private val settingsRepository: SettingsRepository by inject()
    private val portfolioRepository: PortfolioRepository by inject()

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val title = context.getString(R.string.widget_title)
        val credit = context.getString(R.string.widget_coingecko_credit)
        val coinGeckoIds = portfolioRepository.coinGeckoPriceIds
        val settingsFlow = settingsRepository.settings
        val snapshots = settingsFlow.map { it.quoteCurrency }.distinctUntilChanged().flatMapLatest { portfolioRepository.snapshot(it) }

        provideContent {
            // Read inside the composition: turning on discreet mode or the lock takes effect in a running session.
            val settings by settingsFlow.collectAsState(initial = null)
            val snapshot by snapshots.collectAsState(initial = null)
            val priceIds by coinGeckoIds.collectAsState(initial = emptySet())
            val shown = settings?.takeUnless { it.widgetHidesAmounts }
            val current = snapshot
            val hidden = shown == null
            val value = if (shown == null || current == null) HIDDEN else MoneyFormatter.format(current.totalValue, shown.quoteCurrency)
            // Only with a total on display that uses its prices, as on the other screens.
            val showCredit = !hidden && current?.holdings?.usesCoinGecko(priceIds) == true
            val change = current?.change24hPercent?.let { pct ->
                context.getString(R.string.widget_change_24h, if (hidden) HIDDEN else MoneyFormatter.formatPercentChange(pct / 100))
            }
            GlanceTheme {
                Column(
                    modifier = GlanceModifier
                        .fillMaxSize()
                        .background(GlanceTheme.colors.widgetBackground)
                        .cornerRadius(20.dp)
                        .padding(16.dp)
                        .clickable(actionStartActivity<MainActivity>()),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(title, style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 13.sp))
                    Text(value, style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 24.sp, fontWeight = FontWeight.Bold))
                    if (change != null) {
                        Text(change, style = TextStyle(color = GlanceTheme.colors.primary, fontSize = 13.sp))
                    }
                    if (showCredit) {
                        // CoinGecko's terms ask for at least font size 10.
                        Text(credit, style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 10.sp))
                    }
                }
            }
        }
    }

    private companion object {
        const val HIDDEN = "•••••"
    }
}

class PortfolioWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = PortfolioWidget()
}
