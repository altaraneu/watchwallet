package com.altaran.watchwallet.shared

import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.compose.LocalPlatformContext
import coil3.compose.setSingletonImageLoaderFactory
import coil3.disk.DiskCache
import coil3.network.ktor3.KtorNetworkFetcherFactory
import com.altaran.watchwallet.core.designsystem.component.LocalDiscreetMode
import com.altaran.watchwallet.core.designsystem.component.LocalRemoteIcons
import com.altaran.watchwallet.core.designsystem.icon.WwIcon
import com.altaran.watchwallet.core.designsystem.icon.painter
import com.altaran.watchwallet.core.designsystem.theme.WatchWalletTheme
import com.altaran.watchwallet.core.endpoints.EndpointApprovalRequest
import com.altaran.watchwallet.core.model.ThemeMode
import com.altaran.watchwallet.core.ui.LocalPriceHistoryLoading
import com.altaran.watchwallet.feature.addwallet.AddWalletRoute
import com.altaran.watchwallet.feature.addwallet.addWalletScreen
import com.altaran.watchwallet.feature.donate.DonateRoute
import com.altaran.watchwallet.feature.donate.donateScreen
import com.altaran.watchwallet.feature.importexport.ExportRoute
import com.altaran.watchwallet.feature.importexport.ImportRoute
import com.altaran.watchwallet.feature.importexport.PendingImport
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
import com.altaran.watchwallet.shared.generated.resources.endpoints_approval_added
import com.altaran.watchwallet.shared.generated.resources.endpoints_approval_allow
import com.altaran.watchwallet.shared.generated.resources.endpoints_approval_decline
import com.altaran.watchwallet.shared.generated.resources.endpoints_approval_message
import com.altaran.watchwallet.shared.generated.resources.endpoints_approval_title
import com.altaran.watchwallet.shared.generated.resources.nav_markets
import com.altaran.watchwallet.shared.generated.resources.nav_portfolio
import com.altaran.watchwallet.shared.generated.resources.nav_settings
import com.altaran.watchwallet.shared.generated.resources.nav_wallets
import com.altaran.watchwallet.shared.generated.resources.rate_action
import com.altaran.watchwallet.shared.generated.resources.rate_already
import com.altaran.watchwallet.shared.generated.resources.rate_later
import com.altaran.watchwallet.shared.generated.resources.rate_message
import com.altaran.watchwallet.shared.generated.resources.rate_title
import io.ktor.client.HttpClient
import okio.FileSystem
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import kotlin.reflect.KClass

/** Root composable shared by all platforms. Renders nothing until settings are loaded. */
@Composable
fun WatchWalletApp(viewModel: AppViewModel) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    // The periodic price refresh runs only while the app is started (visible).
    LifecycleStartEffect(viewModel) {
        viewModel.setForeground(true)
        onStopOrDispose { viewModel.setForeground(false) }
    }
    val settings = (uiState as? AppUiState.Ready)?.settings ?: return

    val darkTheme = when (settings.themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    WatchWalletTheme(darkTheme = darkTheme, dynamicColor = settings.useDynamicColor) {
        val historyLoading by viewModel.priceHistoryLoading.collectAsStateWithLifecycle()
        // Coin logos go through the app's HTTP client (proxy/Tor settings apply) with a disk cache.
        val http = koinInject<HttpClient>()
        setSingletonImageLoaderFactory { context ->
            ImageLoader.Builder(context)
                .components { add(KtorNetworkFetcherFactory(http)) }
                .diskCache {
                    DiskCache.Builder()
                        .directory(FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "coin_icons")
                        .maxSizeBytes(ICON_CACHE_BYTES)
                        .build()
                }
                .build()
        }
        // Cached logos show which coins were held. The next look at a held coin fetches its logo again.
        val platformContext = LocalPlatformContext.current
        LaunchedEffect(viewModel) {
            viewModel.walletRemoved.collect {
                val images = SingletonImageLoader.get(platformContext)
                images.memoryCache?.clear()
                images.diskCache?.clear()
            }
        }
        CompositionLocalProvider(
            LocalDiscreetMode provides settings.discreetMode,
            LocalPriceHistoryLoading provides historyLoading,
            LocalRemoteIcons provides settings.remoteIcons,
        ) {
            val navController = rememberNavController()
            AppScaffold(navController)
            val approval by viewModel.endpointApproval.collectAsStateWithLifecycle()
            approval?.let { request ->
                EndpointApprovalDialog(
                    request,
                    onAnswer = { approved -> viewModel.answerEndpointApproval(request, approved) },
                    onDismiss = viewModel::postponeEndpointApproval,
                )
            }
            val showReminder by viewModel.showDonateReminder.collectAsStateWithLifecycle()
            if (showReminder && approval == null) {
                DonateReminderDialog(
                    onSupport = {
                        viewModel.dismissDonateReminder(forever = false)
                        navController.navigate(DonateRoute)
                    },
                    onLater = { viewModel.dismissDonateReminder(forever = false) },
                    onNever = { viewModel.dismissDonateReminder(forever = true) },
                )
            }
            val showRatePrompt by viewModel.showRatePrompt.collectAsStateWithLifecycle()
            if (showRatePrompt && approval == null) {
                val uriHandler = LocalUriHandler.current
                RatePromptDialog(
                    onRate = {
                        viewModel.dismissRatePrompt(alreadyRated = false)
                        runCatching { uriHandler.openUri(PLAY_STORE_URL) }
                    },
                    onLater = { viewModel.dismissRatePrompt(alreadyRated = false) },
                    onAlreadyRated = { viewModel.dismissRatePrompt(alreadyRated = true) },
                )
            }
        }
    }
}

/** Who receives the user's addresses is theirs to decide, before any of them is sent. It goes first. */
@Composable
private fun EndpointApprovalDialog(request: EndpointApprovalRequest, onAnswer: (Boolean) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.endpoints_approval_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(Res.string.endpoints_approval_message))
                Text(stringResource(Res.string.endpoints_approval_added, request.added.joinToString(", ")))
            }
        },
        confirmButton = { TextButton(onClick = { onAnswer(true) }) { Text(stringResource(Res.string.endpoints_approval_allow)) } },
        dismissButton = { TextButton(onClick = { onAnswer(false) }) { Text(stringResource(Res.string.endpoints_approval_decline)) } },
    )
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

/** The rating itself is always chosen on Google Play; an app cannot submit one. */
@Composable
private fun RatePromptDialog(onRate: () -> Unit, onLater: () -> Unit, onAlreadyRated: () -> Unit) {
    AlertDialog(
        onDismissRequest = onLater,
        title = { Text(stringResource(Res.string.rate_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    Char(0x2605).toString().repeat(RATE_STARS),
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable(onClickLabel = stringResource(Res.string.rate_action), onClick = onRate),
                )
                Text(stringResource(Res.string.rate_message))
            }
        },
        confirmButton = { TextButton(onClick = onRate) { Text(stringResource(Res.string.rate_action)) } },
        dismissButton = {
            FlowRow {
                TextButton(onClick = onAlreadyRated) { Text(stringResource(Res.string.rate_already)) }
                TextButton(onClick = onLater) { Text(stringResource(Res.string.rate_later)) }
            }
        },
    )
}

private const val RATE_STARS = 5
private const val PLAY_STORE_URL = "https://play.google.com/store/apps/details?id=com.altaran.watchwallet"
private const val ICON_CACHE_BYTES = 30L * 1024 * 1024

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
    // Batch-import text is handed over in memory, never as a navigation argument (which would end
    // up in the saved back stack state).
    val pendingImport = koinInject<PendingImport>()
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
                onBatchImport = { text ->
                    pendingImport.set(text)
                    navController.navigate(ImportRoute)
                },
                onWalletAdded = { id ->
                    navController.navigate(WalletDetailRoute(id)) {
                        popUpTo<AddWalletRoute> { inclusive = true }
                    }
                },
            )
            marketsScreen(onOpenAsset = openHolding)
            assetDetailScreen(onNavigateUp = navController::navigateUp, onOpenWallet = openWallet)
            settingsScreen(
                onOpenSupport = { navController.navigate(DonateRoute) },
                onOpenImport = { navController.navigate(ImportRoute) },
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
