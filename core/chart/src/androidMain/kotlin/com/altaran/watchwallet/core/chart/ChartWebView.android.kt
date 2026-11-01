package com.altaran.watchwallet.core.chart

import android.annotation.SuppressLint
import android.content.pm.ApplicationInfo
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.MotionEvent
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Hardened WebView: JavaScript only for the bundled chart, no network, no file or content
 * access, no storage. The page talks back through a single `WWBridge` interface.
 */
@SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
@Composable
internal actual fun ChartWebView(
    html: String,
    controller: ChartController,
    onCrosshair: (CrosshairValue?) -> Unit,
    onVisibleRange: (Long, Long) -> Unit,
    modifier: Modifier,
) {
    val callback by rememberUpdatedState(onCrosshair)
    val rangeCallback by rememberUpdatedState(onVisibleRange)
    val main = Handler(Looper.getMainLooper())

    AndroidView(
        modifier = modifier,
        factory = { context ->
            WebView(context).apply {
                // AndroidView defaults to WRAP_CONTENT; a wrap-content WebView lays the page out with a
                // zero-height viewport, so `height: 100%` collapses and the chart draws nothing.
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                setBackgroundColor(Color.TRANSPARENT)
                isVerticalScrollBarEnabled = false
                isHorizontalScrollBarEnabled = false
                settings.apply {
                    javaScriptEnabled = true
                    blockNetworkLoads = true
                    allowFileAccess = false
                    allowContentAccess = false
                    domStorageEnabled = false
                    databaseEnabled = false
                    setSupportZoom(false)
                    builtInZoomControls = false
                }
                addJavascriptInterface(
                    object {
                        @JavascriptInterface
                        fun onCrosshair(payload: String?) {
                            main.post { callback(parseCrosshair(payload)) }
                        }

                        @JavascriptInterface
                        fun onVisibleRange(payload: String?) {
                            parseVisibleRange(payload)?.let { (from, to) -> main.post { rangeCallback(from, to) } }
                        }

                        @JavascriptInterface
                        fun onReady(
                            @Suppress("UNUSED_PARAMETER") payload: String?,
                        ) = Unit
                    },
                    "WWBridge",
                )
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String?) {
                        controller.attach { js -> view.evaluateJavascript(js, null) }
                    }

                    // Defense in depth: refuse every network request the page might try to make.
                    // The page itself is a local data: URL and must not be intercepted.
                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                        if (request.url.scheme?.lowercase() in NETWORK_SCHEMES) {
                            WebResourceResponse("text/plain", "utf-8", 403, "Blocked", emptyMap(), null)
                        } else {
                            null
                        }

                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true
                }
                // Debuggable builds only: forward page console output (chart script errors) to logcat.
                if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
                    webChromeClient = object : WebChromeClient() {
                        override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                            Log.w("WatchWalletChart", "${message.messageLevel()} ${message.lineNumber()}: ${message.message()}")
                            return true
                        }
                    }
                }
                // The chart owns horizontal pan and pinch gestures; the list can still be scrolled
                // by dragging outside the chart.
                setOnTouchListener { view, event ->
                    if (event.actionMasked == MotionEvent.ACTION_DOWN) view.parent?.requestDisallowInterceptTouchEvent(true)
                    false
                }
                loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
            }
        },
    )
    DisposableEffect(controller) {
        onDispose { controller.detach() }
    }
}

private val NETWORK_SCHEMES = setOf("http", "https", "ws", "wss", "ftp")
