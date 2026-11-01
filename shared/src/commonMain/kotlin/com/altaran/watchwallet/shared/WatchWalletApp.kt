package com.altaran.watchwallet.shared

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.altaran.watchwallet.feature.addwallet.AddWalletRoute
import com.altaran.watchwallet.feature.addwallet.addWalletScreen
import com.altaran.watchwallet.feature.donate.DonateRoute
import com.altaran.watchwallet.feature.donate.donateScreen
import com.altaran.watchwallet.feature.importexport.ExportRoute
import com.altaran.watchwallet.feature.importexport.ImportRoute
import com.altaran.watchwallet.feature.importexport.importExportScreens
import com.altaran.watchwallet.feature.markets.AssetDetailRoute
import com.altaran.watchwallet.feature.markets.MarketsRoute
import com.altaran.watchwallet.feature.markets.assetDetailScreen
import com.altaran.watchwallet.feature.markets.marketsScreen
import com.altaran.watchwallet.feature.portfolio.PortfolioRoute
import com.altaran.watchwallet.feature.portfolio.portfolioScreen
import com.altaran.watchwallet.feature.settings.SettingsRoute
import com.altaran.watchwallet.feature.settings.settingsScreen
import com.altaran.watchwallet.feature.wallets.WalletDetailRoute
import com.altaran.watchwallet.feature.wallets.WalletsRoute
import com.altaran.watchwallet.feature.wallets.walletDetailScreen
import com.altaran.watchwallet.feature.wallets.walletsScreen
import com.altaran.watchwallet.shared.generated.resources.Res
import com.altaran.watchwallet.shared.generated.resources.donate_reminder_later
import com.altaran.watchwallet.shared.generated.resources.donate_reminder_message
import com.altaran.watchwallet.shared.generated.resources.donate_reminder_never
import com.altaran.watchwallet.shared.generated.resources.donate_reminder_support
import com.altaran.watchwallet.shared.generated.resources.donate_reminder_title
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
            val navController = rememberNavController()
            AppScaffold(navController)
            val showReminder by viewModel.showDonateReminder.collectAsStateWithLifecycle()
            if (showReminder) {
                DonateReminderDialog(
                    onSupport = {
                        viewModel.dismissDonateReminder(forever = false)
                        navController.navigate(DonateRoute)
                    },
                    onLater = { viewModel.dismissDonateReminder(forever = false) },
                    onNever = { viewModel.dismissDonateReminder(forever = true) },
                )
            }
        }
    }
}

/** Shown on the 5th, 10th and 30th launch; "Don't ask again" turns it off for good. */
@Composable
private fun DonateReminderDialog(onSupport: () -> Unit, onLater: () -> Unit, onNever: () -> Unit) {
    AlertDialog(
        onDismissRequest = onLater,
        title = { Text(stringResource(Res.string.donate_reminder_title)) },
        text = { Text(stringResource(Res.string.donate_reminder_message)) },
        confirmButton = { TextButton(onClick = onSupport) { Text(stringResource(Res.string.donate_reminder_support)) } },
        dismissButton = {
            Row {
                TextButton(onClick = onNever) { Text(stringResource(Res.string.donate_reminder_never)) }
                TextButton(onClick = onLater) { Text(stringResource(Res.string.donate_reminder_later)) }
            }
        },
    )
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
            val openHolding: (String) -> Unit = { key -> navController.navigate(AssetDetailRoute(key)) }
            val openWallet: (Long) -> Unit = { id -> navController.navigate(WalletDetailRoute(id)) }
            val addWallet: () -> Unit = { navController.navigate(AddWalletRoute) }
            portfolioScreen(onAddWallet = addWallet, onOpenHolding = openHolding)
            walletsScreen(onAddWallet = addWallet, onOpenWallet = openWallet)
            walletDetailScreen(onNavigateUp = navController::navigateUp, onOpenHolding = openHolding)
            addWalletScreen(
                onNavigateUp = navController::navigateUp,
                onBatchImport = { text -> navController.navigate(ImportRoute(text)) },
                onWalletAdded = { id ->
                    navController.navigate(WalletDetailRoute(id)) {
                        popUpTo<AddWalletRoute> { inclusive = true }
                    }
                },
            )
            marketsScreen(onOpenAsset = openHolding)
            assetDetailScreen(onNavigateUp = navController::navigateUp)
            settingsScreen(
                onOpenSupport = { navController.navigate(DonateRoute) },
                onOpenImport = { navController.navigate(ImportRoute()) },
                onOpenExport = { navController.navigate(ExportRoute) },
            )
            importExportScreens(
                onNavigateUp = navController::navigateUp,
                onImportDone = { navController.navigateToTopLevel(TopLevelDestination.WALLETS) },
            )
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
