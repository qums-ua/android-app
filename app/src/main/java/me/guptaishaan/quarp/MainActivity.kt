package me.guptaishaan.quarp

import android.Manifest
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.view.Menu
import android.view.MenuItem
import android.webkit.CookieManager
import android.webkit.MimeTypeMap
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import android.webkit.ValueCallback
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import me.guptaishaan.quarp.ui.theme.QuarpTheme
import java.io.File

class MainActivity : ComponentActivity() {

    private var webView: WebView? = null
    private lateinit var downloadManager: DownloadManager
    private var lastDownloadId: Long = -1L

    /** Holds a pending download while we wait for WRITE_EXTERNAL_STORAGE on API 26-28. */
    private data class PendingDownload(val url: String, val contentDisposition: String, val mimeType: String)
    private var pendingDownload: PendingDownload? = null

    // File upload support (input type="file" in the WebView)
    private var fileUploadCallback: ValueCallback<Array<Uri>>? = null
    private val fileUploadLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val callback = fileUploadCallback
        fileUploadCallback = null
        val resultUri = result.data?.data
        callback?.onReceiveValue(if (resultUri != null) arrayOf(resultUri) else null)
    }

    // Permission launcher (only needed for API 26-28)
    private val requestStoragePermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val pd = pendingDownload
        pendingDownload = null
        if (granted && pd != null) {
            enqueueDownload(pd.url, pd.contentDisposition, pd.mimeType)
        } else if (!granted) {
            Toast.makeText(this, "Storage permission denied \u2014 download cancelled", Toast.LENGTH_SHORT).show()
        }
    }

    // BroadcastReceiver: fires when DownloadManager finishes a download
    private val downloadCompleteReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val id = intent?.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L) ?: return
            if (id == lastDownloadId && id != -1L) {
                openDownloadedFile(id)
            }
        }
    }

    companion object {
        private const val MENU_TOGGLE_AUTO_SOLVE = 1
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        downloadManager = getSystemService(DOWNLOAD_SERVICE) as DownloadManager

        val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(downloadCompleteReceiver, filter, RECEIVER_EXPORTED)
        } else {
            registerReceiver(downloadCompleteReceiver, filter)
        }

        setContent {
            QuarpTheme {
                // Track auto-solve state for the FAB visibility
                var hasCaptcha by remember { mutableStateOf(false) }

                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    floatingActionButton = {
                        if (hasCaptcha) {
                            FloatingActionButton(
                                onClick = {
                                    webView?.let { wv ->
                                        CaptchaHelper.extractAndSolveCaptcha(this@MainActivity, wv)
                                    }
                                }
                            ) {
                                Icon(
                                    imageVector = Icons.Default.DocumentScanner,
                                    contentDescription = "Solve Captcha"
                                )
                            }
                        }
                    }
                ) { innerPadding ->
                    QumsWebView(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding),
                        onWebViewCreated = { webView = it },
                        onDownloadRequested = ::showDownloadPermissionDialog,
                        onShowFileChooser = ::handleShowFileChooser,
                        onCaptchaDetected = { wv ->
                            if (CaptchaHelper.isEnabled(this@MainActivity)) {
                                CaptchaHelper.extractAndSolveCaptcha(this@MainActivity, wv)
                            }
                        },
                        onCaptchaPageChanged = { hasCaptcha = it }
                    )
                }
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, MENU_TOGGLE_AUTO_SOLVE, 0, toggleMenuTitle())
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.findItem(MENU_TOGGLE_AUTO_SOLVE)?.title = toggleMenuTitle()
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            MENU_TOGGLE_AUTO_SOLVE -> {
                val currentlyEnabled = CaptchaHelper.isEnabled(this)
                CaptchaHelper.setEnabled(this, !currentlyEnabled)
                val newState = !currentlyEnabled
                Toast.makeText(
                    this,
                    if (newState) "Auto-solve captcha: ON" else "Auto-solve captcha: OFF",
                    Toast.LENGTH_SHORT
                ).show()
                invalidateOptionsMenu()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun toggleMenuTitle(): String {
        val enabled = CaptchaHelper.isEnabled(this)
        return if (enabled) "\u2705 Auto-Solve Captcha: ON" else "\u274C Auto-Solve Captcha: OFF"
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(downloadCompleteReceiver)
        } catch (_: IllegalArgumentException) {
            // Receiver was not registered — safe to ignore
        }
    }

    /**
     * Called by the WebChromeClient when the page wants to open a file chooser.
     * Delegates to the system file picker via ActivityResultLauncher.
     */
    private fun handleShowFileChooser(
        filePathCallback: ValueCallback<Array<Uri>>?,
        intent: Intent?
    ): Boolean {
        if (intent == null) return false
        fileUploadCallback?.onReceiveValue(null)
        fileUploadCallback = filePathCallback
        fileUploadLauncher.launch(intent)
        return true
    }

    // Step 1: Ask the user for permission to download
    private fun showDownloadPermissionDialog(url: String, contentDisposition: String, mimeType: String) {
        val fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)
        MaterialAlertDialogBuilder(this)
            .setTitle("Download File")
            .setMessage("Do you want to download \"$fileName\"?")
            .setPositiveButton("Download") { _, _ ->
                requestStorageOrDownload(url, contentDisposition, mimeType)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // Step 2: Ensure we have storage permission (API 26-28), then download
    private fun requestStorageOrDownload(url: String, contentDisposition: String, mimeType: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED
            ) {
                enqueueDownload(url, contentDisposition, mimeType)
            } else {
                pendingDownload = PendingDownload(url, contentDisposition, mimeType)
                requestStoragePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        } else {
            enqueueDownload(url, contentDisposition, mimeType)
        }
    }

    // Step 3: Enqueue the download with DownloadManager
    private fun enqueueDownload(url: String, contentDisposition: String, mimeType: String) {
        val fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)
        val request = DownloadManager.Request(Uri.parse(url))
            .setTitle(fileName)
            .setDescription("Downloading\u2026")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(true)

        // Forward cookies from the WebView session so authenticated downloads work
        val cookies = CookieManager.getInstance().getCookie(url)
        if (!cookies.isNullOrEmpty()) {
            request.addRequestHeader("Cookie", cookies)
        }

        if (mimeType.isNotBlank()) {
            request.setMimeType(mimeType)
        }

        lastDownloadId = downloadManager.enqueue(request)
        Toast.makeText(this, "Download started: $fileName", Toast.LENGTH_SHORT).show()
    }

    // Step 4: Download complete — open the file directly
    private fun openDownloadedFile(downloadId: Long) {
        val info = getDownloadedFileInfo(downloadId)
        if (info == null) {
            Toast.makeText(this, "Unable to locate the downloaded file", Toast.LENGTH_SHORT).show()
            return
        }
        val (uri, title, dmMimeType) = info
        // Best chance: use the filename extension (title like "report.pdf")
        val ext = MimeTypeMap.getFileExtensionFromUrl(title)
        val extMime = if (ext.isNotEmpty()) MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext.lowercase()) else null
        val mimeType = dmMimeType ?: extMime ?: contentResolver.getType(uri)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(intent)
        } catch (_: Exception) {
            Toast.makeText(this, "No app found to open this file", Toast.LENGTH_SHORT).show()
        }
    }

    // Helpers

    /** Query DownloadManager for the file URI, title, and MIME type. */
    private fun getDownloadedFileInfo(downloadId: Long): Triple<Uri, String, String?>? {
        val query = DownloadManager.Query().setFilterById(downloadId)
        val cursor: Cursor = downloadManager.query(query) ?: return null
        cursor.use {
            if (!it.moveToFirst()) return null

            val uriIdx = it.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI)
            val titleIdx = it.getColumnIndex(DownloadManager.COLUMN_TITLE)
            val mimeTypeIdx = it.getColumnIndex(DownloadManager.COLUMN_MEDIA_TYPE)
            val uriString = it.getString(uriIdx) ?: return null
            val title = it.getString(titleIdx) ?: "file"
            val mimeType = it.getString(mimeTypeIdx)

            val rawUri = Uri.parse(uriString)

            // On pre-Q the URI is file:// which can't be shared via Intent — convert via FileProvider
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q && rawUri.scheme == "file") {
                return try {
                    val path = rawUri.path ?: return null
                    val contentUri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", File(path))
                    Triple(contentUri, title, mimeType)
                } catch (_: Exception) {
                    Triple(rawUri, title, mimeType)
                }
            }

            return Triple(rawUri, title, mimeType)
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

private const val QUMS_URL = "https://qums.quantumuniversity.edu.in/Account/Cyborg_StudentMenu"

/** Pages where a captcha is present and should be auto-solved. */
private val CAPTCHA_URLS = setOf(
    "https://qums.quantumuniversity.edu.in",
    "https://qums.quantumuniversity.edu.in/Account/Login",
)

/** Strips trailing slash so URLs match regardless of trailing slash. */
private fun normalizeUrl(url: String?): String = url?.trimEnd('/') ?: ""

@Composable
fun QumsWebView(
    modifier: Modifier = Modifier,
    onWebViewCreated: (WebView) -> Unit = {},
    onDownloadRequested: (url: String, contentDisposition: String, mimeType: String) -> Unit = { _, _, _ -> },
    onShowFileChooser: (filePathCallback: ValueCallback<Array<Uri>>?, intent: Intent?) -> Boolean = { _, _ -> false },
    onCaptchaDetected: (WebView) -> Unit = {},
    onCaptchaPageChanged: (Boolean) -> Unit = {}
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

                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        view?.evaluateJavascript("""
                            (function() {
                                var el = document.getElementById("captcha");
                                if (el) {
                                    el.setAttribute("autocapitalize", "characters");
                                }
                            })();
                        """.trimIndent(), null)
                        // Only detect captcha on known login pages (trailing-slash tolerant)
                        if (normalizeUrl(url) in CAPTCHA_URLS) {
                            onCaptchaPageChanged(true)
                            if (view != null) {
                                onCaptchaDetected(view)
                            }
                        } else {
                            onCaptchaPageChanged(false)
                        }
                    }
                }

                webChromeClient = object : WebChromeClient() {
                    // File upload (input type="file") — delegates to the system file picker
                    override fun onShowFileChooser(
                        webView: WebView?,
                        filePathCallback: ValueCallback<Array<Uri>>?,
                        fileChooserParams: FileChooserParams?
                    ): Boolean {
                        val intent = fileChooserParams?.createIntent()
                        return onShowFileChooser(filePathCallback, intent)
                    }

                    // Geolocation and media permissions — auto-grant for the QUMS portal
                    override fun onPermissionRequest(request: android.webkit.PermissionRequest?) {
                        request?.grant(request.resources)
                    }
                }

                // Intercept download requests from the WebView
                setDownloadListener { url, _, contentDisposition, mimeType, _ ->
                    onDownloadRequested(url, contentDisposition, mimeType)
                }

                loadUrl(QUMS_URL)
                onWebViewCreated(this)
            }
        }
    )
}
