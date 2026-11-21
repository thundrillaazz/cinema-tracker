package com.michael.cinematracker

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { _ -> }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, true)
        window.statusBarColor = Color.parseColor("#F8FAFC")
        window.navigationBarColor = Color.parseColor("#F8FAFC")

        createNotificationChannel()
        requestNotificationPermissionIfNeeded()

        webView = WebView(this).apply {
            setBackgroundColor(Color.parseColor("#F8FAFC"))
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                cacheMode = WebSettings.LOAD_DEFAULT
                mediaPlaybackRequiresUserGesture = false
                allowFileAccess = true
                useWideViewPort = true
                loadWithOverviewMode = true
            }
            addJavascriptInterface(WebAppInterface(this@MainActivity), "AndroidBridge")
            webChromeClient = WebChromeClient()
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest?
                ): Boolean {
                    val uri = request?.url ?: return false
                    val scheme = uri.scheme ?: return false
                    if (scheme == "file") return false
                    openExternalUri(uri)
                    return true
                }
            }
        }

        setContentView(webView)
        webView.loadUrl("file:///android_asset/index.html")

        scheduleReleaseWorker()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                webView.evaluateJavascript(
                    "(function(){ if(window.handleNativeBack && window.handleNativeBack()) return 'handled'; if(document.getElementById('movie-modal').classList.contains('active')) { closeModal(); return 'handled'; } return 'exit'; })()"
                ) { result ->
                    if (result != "\"handled\"") {
                        if (webView.canGoBack()) {
                            webView.goBack()
                        } else {
                            finish()
                        }
                    }
                }
            }
        })
    }

    private fun openExternalUri(uri: Uri) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, "Tidak ada aplikasi untuk membuka tautan ini", Toast.LENGTH_SHORT).show()
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CinemaCheckWorker.CHANNEL_ID,
                "Radar Bioskop, TBA & Presale",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifikasi film masuk jadwal ID, TBA terkonfirmasi, presale, H-1, dan hari-H"
                enableVibration(true)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun scheduleReleaseWorker() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val workRequest = PeriodicWorkRequestBuilder<CinemaCheckWorker>(6, TimeUnit.HOURS)
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "cinema_tracker_release_sync",
            ExistingPeriodicWorkPolicy.KEEP,
            workRequest
        )
    }

    inner class WebAppInterface(private val context: Context) {

        @JavascriptInterface
        fun openLetterboxdDirect(tmdbId: Int, slugFallback: String) {
            runOnUiThread {
                val url = if (tmdbId > 0) {
                    "https://letterboxd.com/tmdb/$tmdbId/"
                } else {
                    "https://letterboxd.com/search/$slugFallback/"
                }
                openExternalUri(Uri.parse(url))
            }
        }

        @JavascriptInterface
        fun openTrailerDirect(youtubeKey: String, movieTitle: String) {
            runOnUiThread {
                if (youtubeKey.isNotBlank()) {
                    try {
                        startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse("vnd.youtube:$youtubeKey"))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                        return@runOnUiThread
                    } catch (_: ActivityNotFoundException) {
                        openExternalUri(Uri.parse("https://www.youtube.com/watch?v=$youtubeKey"))
                        return@runOnUiThread
                    }
                }
                val q = Uri.encode("$movieTitle official trailer")
                openExternalUri(Uri.parse("https://m.youtube.com/results?search_query=$q"))
            }
        }

        @JavascriptInterface
        fun syncRadarState(watchlistJson: String, tbaJson: String, apiKey: String) {
            context.getSharedPreferences(CinemaCheckWorker.PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(CinemaCheckWorker.KEY_WATCHLIST, watchlistJson)
                .putString(CinemaCheckWorker.KEY_TBA, tbaJson)
                .putString(CinemaCheckWorker.KEY_API_KEY, apiKey)
                .apply()
        }

        @JavascriptInterface
        fun syncTheme(colorHex: String) {
            runOnUiThread {
                try {
                    window.statusBarColor = Color.parseColor(colorHex)
                    window.navigationBarColor = Color.parseColor(colorHex)
                } catch (_: Exception) {}
            }
        }

        @JavascriptInterface
        fun savePosterBase64(base64Data: String, fileName: String) {
            runOnUiThread {
                try {
                    val clean = base64Data.substringAfter(",")
                    val bytes = Base64.decode(clean, Base64.DEFAULT)
                    val safeName = if (fileName.endsWith(".jpg", true)) fileName else "$fileName.jpg"

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        val values = ContentValues().apply {
                            put(MediaStore.Images.Media.DISPLAY_NAME, safeName)
                            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/CinemaTracker")
                        }
                        val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                        if (uri != null) {
                            context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                            Toast.makeText(context, "Poster HD disimpan ke Galeri", Toast.LENGTH_SHORT).show()
                            return@runOnUiThread
                        }
                    }

                    val dir = File(
                        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                        "CinemaTracker"
                    )
                    if (!dir.exists()) dir.mkdirs()
                    val outFile = File(dir, safeName)
                    FileOutputStream(outFile).use { it.write(bytes) }
                    Toast.makeText(context, "Poster HD tersimpan", Toast.LENGTH_SHORT).show()
                } catch (_: Exception) {
                    Toast.makeText(context, "Gagal menyimpan poster", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}
