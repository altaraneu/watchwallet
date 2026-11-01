package com.altaran.watchwallet.shared

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.altaran.watchwallet.core.designsystem.component.LocalDiscreetMode
import com.altaran.watchwallet.core.designsystem.icon.WwIcon
import com.altaran.watchwallet.core.designsystem.icon.painter
import com.altaran.watchwallet.core.designsystem.theme.WatchWalletTheme
import com.altaran.watchwallet.core.model.ThemeMode
import com.altaran.watchwallet.feature.donate.DonateRoute
import com.altaran.watchwallet.feature.donate.donateScreen
import com.altaran.watchwallet.feature.markets.MarketsRoute
import com.altaran.watchwallet.feature.markets.marketsScreen
import com.altaran.watchwallet.feature.portfolio.PortfolioRoute
import com.altaran.watchwallet.feature.portfolio.portfolioScreen
import com.altaran.watchwallet.feature.settings.SettingsRoute
import com.altaran.watchwallet.feature.settings.settingsScreen
import com.altaran.watchwallet.feature.wallets.WalletsRoute
import com.altaran.watchwallet.feature.wallets.walletsScreen
import com.altaran.watchwallet.shared.generated.resources.Res
import com.altaran.watchwallet.shared.generated.resources.nav_markets
import com.altaran.watchwallet.shared.generated.resources.nav_portfolio
import com.altaran.watchwallet.shared.generated.resources.nav_settings
import com.altaran.watchwallet.shared.generated.resources.nav_wallets
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import kotlin.reflect.KClass

/** Root composable shared by all platforms. Renders nothing until settings are loaded. */
@Composable
fun WatchWalletApp(viewModel: AppViewModel) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val settings = (uiState as? AppUiState.Ready)?.settings ?: return

    val darkTheme = when (settings.themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    WatchWalletTheme(darkTheme = darkTheme, dynamicColor = settings.useDynamicColor) {
        CompositionLocalProvider(LocalDiscreetMode provides settings.discreetMode) {
            AppScaffold()
        }
    }
}

private enum class TopLevelDestination(
    val route: Any,
    val routeClass: KClass<*>,
    val icon: WwIcon,
    val label: StringResource,
) {
    PORTFOLIO(PortfolioRoute, PortfolioRoute::class, WwIcon.Portfolio, Res.string.nav_portfolio),
    WALLETS(WalletsRoute, WalletsRoute::class, WwIcon.Wallets, Res.string.nav_wallets),
    MARKETS(MarketsRoute, MarketsRoute::class, WwIcon.Markets, Res.string.nav_markets),
    SETTINGS(SettingsRoute, SettingsRoute::class, WwIcon.Settings, Res.string.nav_settings),
}

@Composable
private fun AppScaffold(navController: NavHostController = rememberNavController()) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination
    val currentTopLevel = TopLevelDestination.entries.firstOrNull { destination ->
        currentDestination?.hierarchy?.any { it.hasRoute(destination.routeClass) } == true
    }

    Scaffold(
        // Each screen draws its own top bar and handles the status bar inset itself.
        contentWindowInsets = WindowInsets(0),
        bottomBar = {
            if (currentTopLevel != null) {
                NavigationBar {
                    TopLevelDestination.entries.forEach { destination ->
                        NavigationBarItem(
                            selected = destination == currentTopLevel,
                            onClick = { navController.navigateToTopLevel(destination) },
                            icon = { Icon(painter = destination.icon.painter(), contentDescription = null) },
                            label = { Text(stringResource(destination.label)) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = PortfolioRoute,
            modifier = Modifier
                .padding(padding)
                .consumeWindowInsets(padding),
        ) {
            portfolioScreen(onAddWallet = { navController.navigateToTopLevel(TopLevelDestination.WALLETS) })
            walletsScreen()
            marketsScreen()
            settingsScreen(onOpenSupport = { navController.navigate(DonateRoute) })
            donateScreen(onNavigateUp = navController::navigateUp)
        }
    }
}

private fun NavHostController.navigateToTopLevel(destination: TopLevelDestination) {
    navigate(destination.route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
