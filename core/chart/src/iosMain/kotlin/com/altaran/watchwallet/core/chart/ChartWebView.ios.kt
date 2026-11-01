package com.altaran.watchwallet.core.chart

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitView
import kotlinx.cinterop.ExperimentalForeignApi
import platform.WebKit.WKNavigation
import platform.WebKit.WKNavigationDelegateProtocol
import platform.WebKit.WKWebView
import platform.WebKit.WKWebViewConfiguration
import platform.darwin.NSObject

// TODO(iOS): crosshair callbacks via WKScriptMessageHandler.
@OptIn(ExperimentalForeignApi::class)
@Composable
internal actual fun ChartWebView(
    html: String,
    controller: ChartController,
    onCrosshair: (CrosshairValue?) -> Unit,
    modifier: Modifier,
) {
    val delegate = remember {
        object : NSObject(), WKNavigationDelegateProtocol {
            override fun webView(webView: WKWebView, didFinishNavigation: WKNavigation?) {
                controller.attach { js -> webView.evaluateJavaScript(js, null) }
            }
        }
    }
    UIKitView(
        modifier = modifier,
        factory = {
            WKWebView(frame = platform.CoreGraphics.CGRectZero.readValue(), configuration = WKWebViewConfiguration()).apply {
                navigationDelegate = delegate
                opaque = false
                loadHTMLString(html, baseURL = null)
            }
        },
    )
    DisposableEffect(controller) { onDispose { controller.detach() } }
}
