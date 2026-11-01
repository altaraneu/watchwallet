package com.altaran.watchwallet.widget

import android.content.Context
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
import com.altaran.watchwallet.core.ui.format.MoneyFormatter
import kotlinx.coroutines.flow.first
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * Home-screen widget with the total portfolio value and its 24-hour change. Respects discreet
 * mode: amounts are replaced by dots. Data comes from the local cache; no network access here.
 */
class PortfolioWidget : GlanceAppWidget(), KoinComponent {
    private val settingsRepository: SettingsRepository by inject()
    private val portfolioRepository: PortfolioRepository by inject()

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val settings = settingsRepository.settings.first()
        val snapshot = portfolioRepository.snapshot(settings.quoteCurrency).first()
        val hidden = settings.discreetMode
        val value = if (hidden) HIDDEN else MoneyFormatter.format(snapshot.totalValue, settings.quoteCurrency)
        val change = snapshot.change24hPercent?.let { pct ->
            if (hidden) HIDDEN else MoneyFormatter.formatPercentChange(pct / 100)
        }
        val title = context.getString(R.string.widget_title)
        val changeLabel = context.getString(R.string.widget_change_24h)

        provideContent {
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
                        Text("$change $changeLabel", style = TextStyle(color = GlanceTheme.colors.primary, fontSize = 13.sp))
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
