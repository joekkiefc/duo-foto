package nl.houseofsneijder.duoshot

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Base64
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.OutputStream

/**
 * Duoshot — WebView-only app die de bundled HTML/JS toont. De HTML doet alle
 * UI + canvas-combine; save-naar-galerij gaat via de JavaScriptInterface
 * `DuoshotBridge.saveJpeg(base64, name)` naar MediaStore.
 */
class MainActivity : ComponentActivity() {

    private lateinit var web: WebView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        ensureCameraPermission()

        web = WebView(this).also { setContentView(it) }
        with(web.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        }
        web.addJavascriptInterface(Bridge(), "DuoshotBridge")

        // Camera-permissie voor input[type=file capture]
        web.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest?) {
                request?.let {
                    val cam = PermissionRequest.RESOURCE_VIDEO_CAPTURE
                    if (it.resources.contains(cam)) it.grant(arrayOf(cam)) else it.deny()
                }
            }
        }
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?, request: WebResourceRequest?
            ): Boolean = false // laat alles binnen WebView
        }

        web.loadUrl("file:///android_asset/www/index.html")
    }

    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else super.onBackPressed()
    }

    private fun ensureCameraPermission() {
        val perm = Manifest.permission.CAMERA
        if (ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(perm), 1001)
        }
    }

    /**
     * WebView ↔ Android bridge. De HTML noemt `window.DuoshotBridge.saveJpeg`
     * met een base64-encoded JPG en filenaam. We schrijven het naar
     * Pictures/Duoshot/ zodat het in de galerij verschijnt.
     */
    inner class Bridge {
        @JavascriptInterface
        fun saveJpeg(base64: String, name: String): Boolean {
            return try {
                val bytes = Base64.decode(base64, Base64.DEFAULT)
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, name)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Duoshot")
                        put(MediaStore.Images.Media.IS_PENDING, 1)
                    }
                }
                val resolver = contentResolver
                val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                    ?: return false
                resolver.openOutputStream(uri).use { out: OutputStream? ->
                    if (out == null) return false
                    out.write(bytes)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    values.clear()
                    values.put(MediaStore.Images.Media.IS_PENDING, 0)
                    resolver.update(uri, values, null, null)
                }
                runOnUiThread { Toast.makeText(this@MainActivity, "Opgeslagen in Pictures/Duoshot", Toast.LENGTH_SHORT).show() }
                true
            } catch (t: Throwable) {
                Log.e("Duoshot", "saveJpeg failed", t)
                runOnUiThread { Toast.makeText(this@MainActivity, "Opslaan mislukt: ${t.message}", Toast.LENGTH_LONG).show() }
                false
            }
        }
    }
}
