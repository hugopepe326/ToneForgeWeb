package com.example

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.example.dsp.PackExporter
import com.example.web.ToneForgeWebBridge
import java.io.ByteArrayInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.BLACK),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.BLACK)
        )
        setContent {
            ToneForgeWebShell()
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun ToneForgeWebShell() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var filePathCallback by remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }
    var pendingWebPermissionReq by remember { mutableStateOf<PermissionRequest?>(null) }
    var pendingSaveBytes by remember { mutableStateOf<ByteArray?>(null) }
    var pendingSaveLabel by remember { mutableStateOf("") }

    val openFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        val cb = filePathCallback
        filePathCallback = null
        if (uri != null) {
            cb?.onReceiveValue(arrayOf(uri))
        } else {
            cb?.onReceiveValue(null)
        }
    }

    val createDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("*/*")
    ) { uri ->
        val bytes = pendingSaveBytes
        val label = pendingSaveLabel
        pendingSaveBytes = null
        if (uri != null && bytes != null) {
            scope.launch(Dispatchers.IO) {
                val res = runCatching { PackExporter.writeBytesToUri(context, uri, bytes) }
                withContext(Dispatchers.Main) {
                    val msg = if (res.isSuccess) "$label guardado correctamente." else "Error al guardar."
                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    var bridgeRef by remember { mutableStateOf<ToneForgeWebBridge?>(null) }

    val openValetonFolderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { treeUri ->
        if (treeUri != null) {
            bridgeRef?.onValetonTreePicked(treeUri)
        }
    }

    val recordPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        val webReq = pendingWebPermissionReq
        pendingWebPermissionReq = null
        if (webReq != null) {
            if (granted) {
                webReq.grant(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE))
            } else {
                webReq.deny()
            }
        } else if (granted) {
            bridgeRef?.startNativeRecordingInternal()
        }
    }

    val bridge = remember {
        ToneForgeWebBridge(
            context = context,
            scope = scope,
            webViewProvider = { webViewRef },
            onLaunchSaveDocument = { bytes, fileName, _, label ->
                pendingSaveBytes = bytes
                pendingSaveLabel = label
                createDocumentLauncher.launch(fileName)
            },
            onRequestRecordPermission = {
                recordPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            },
            onPickValetonFolder = {
                openValetonFolderLauncher.launch(null)
            }
        ).also { bridgeRef = it }
    }

    DisposableEffect(Unit) {
        onDispose {
            bridge.cleanup()
        }
    }

    val activity = context as? ComponentActivity
    BackHandler {
        val wv = webViewRef
        if (wv == null) {
            activity?.finish()
        } else {
            wv.evaluateJavascript("window.__handleBackPress ? window.__handleBackPress() : false;") { result ->
                if (result != "true") {
                    activity?.finish()
                }
            }
        }
    }

    Scaffold(
        containerColor = Color.Black,
        contentWindowInsets = WindowInsets.safeDrawing
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .padding(innerPadding)
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    WebView(ctx).apply {
                        setBackgroundColor(android.graphics.Color.BLACK)
                        settings.apply {
                            javaScriptEnabled = true
                            domStorageEnabled = true
                            databaseEnabled = true
                            mediaPlaybackRequiresUserGesture = false
                            allowFileAccess = false
                            allowContentAccess = true
                            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                        }

                        addJavascriptInterface(bridge, "ToneForgeNative")

                        webViewClient = object : WebViewClient() {
                            override fun shouldInterceptRequest(
                                view: WebView?,
                                request: WebResourceRequest?
                            ): WebResourceResponse? {
                                val url = request?.url ?: return null
                                if (url.host != "toneforge.local") return null
                                val relPath = url.path?.trimStart('/')?.ifBlank { "index.html" } ?: "index.html"
                                val cleanPath = relPath.substringBefore('?').substringBefore('#')
                                val mime = when {
                                    cleanPath.endsWith(".html") -> "text/html"
                                    cleanPath.endsWith(".css") -> "text/css"
                                    cleanPath.endsWith(".js") -> "application/javascript"
                                    cleanPath.endsWith(".svg") -> "image/svg+xml"
                                    cleanPath.endsWith(".json") -> "application/json"
                                    else -> "application/octet-stream"
                                }
                                return runCatching {
                                    val stream = ctx.assets.open("web/$cleanPath")
                                    WebResourceResponse(mime, "UTF-8", stream)
                                }.getOrElse {
                                    WebResourceResponse(
                                        "text/plain",
                                        "UTF-8",
                                        404,
                                        "Not Found",
                                        emptyMap(),
                                        ByteArrayInputStream(ByteArray(0))
                                    )
                                }
                            }

                            override fun shouldOverrideUrlLoading(
                                view: WebView?,
                                request: WebResourceRequest?
                            ): Boolean {
                                val uri = request?.url ?: return false
                                if (uri.host == "toneforge.local") return false
                                runCatching {
                                    ctx.startActivity(Intent(Intent.ACTION_VIEW, uri))
                                }
                                return true
                            }
                        }

                        webChromeClient = object : WebChromeClient() {
                            override fun onShowFileChooser(
                                webView: WebView?,
                                filePathCallbackArg: ValueCallback<Array<Uri>>?,
                                fileChooserParams: FileChooserParams?
                            ): Boolean {
                                filePathCallback?.onReceiveValue(null)
                                filePathCallback = filePathCallbackArg
                                openFileLauncher.launch(arrayOf("audio/*", "video/*", "application/octet-stream"))
                                return true
                            }

                            override fun onPermissionRequest(request: PermissionRequest?) {
                                val req = request ?: return
                                if (PermissionRequest.RESOURCE_AUDIO_CAPTURE in req.resources) {
                                    val granted = ContextCompat.checkSelfPermission(
                                        ctx,
                                        Manifest.permission.RECORD_AUDIO
                                    ) == PackageManager.PERMISSION_GRANTED
                                    if (granted) {
                                        req.grant(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE))
                                    } else {
                                        pendingWebPermissionReq = req
                                        recordPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                    }
                                } else {
                                    req.deny()
                                }
                            }
                        }

                        loadUrl("https://toneforge.local/index.html")
                        webViewRef = this
                    }
                }
            )
        }
    }
}
