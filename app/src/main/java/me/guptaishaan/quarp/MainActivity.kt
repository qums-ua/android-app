package me.guptaishaan.quarp

import android.os.Bundle
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import me.guptaishaan.quarp.ui.theme.QuarpTheme

class MainActivity : ComponentActivity() {

    private var webView: WebView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            QuarpTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    QumsWebView(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding),
                        onWebViewCreated = { webView = it }
                    )
                }
            }
        }
    }

    @Deprecated("Use OnBackPressedCallback instead.")
    override fun onBackPressed() {
        if (webView?.canGoBack() == true) {
            webView?.goBack()
        } else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }
}

private const val QUMS_URL = "https://qums.quantumuniversity.edu.in"

@Composable
fun QumsWebView(
    modifier: Modifier = Modifier,
    onWebViewCreated: (WebView) -> Unit = {}
) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            WebView(context).apply {
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    loadWithOverviewMode = true
                    useWideViewPort = true
                    setSupportZoom(true)
                    builtInZoomControls = true
                    displayZoomControls = false
                }

                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        view: WebView?,
                        request: WebResourceRequest?
                    ): Boolean {
                        // Keep all navigation inside the WebView
                        return false
                    }
                }

                webChromeClient = WebChromeClient()

                loadUrl(QUMS_URL)
                onWebViewCreated(this)
            }
        }
    )
}