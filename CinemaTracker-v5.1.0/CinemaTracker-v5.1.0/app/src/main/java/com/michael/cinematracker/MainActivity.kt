package com.michael.cinematracker

import android.Manifest
import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
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
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    companion object {
        const val DEFAULT_TMDB_KEY = "2dca580c2a14b55200e784d157207b4d"
    }

    private lateinit var webView: WebView

    private val notifPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Ensure content never overlaps status bar or navigation bar
        WindowCompat.setDecorFitsSystemWindows(window, true)
        window.statusBarColor = Color.parseColor("#0B0D12")
        window.navigationBarColor = Color.parseColor("#0B0D12")

        webView = WebView(this)
        webView.setBackgroundColor(Color.parseColor("#0B0D12"))
        setContentView(webView)

        // Extra safe inset padding in case OEM devices force edge-to-edge
        ViewCompat.setOnApplyWindowInsetsListener(webView) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.allowFileAccess = true
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        settings.cacheMode = WebSettings.LOAD_DEFAULT
        settings.userAgentString = settings.userAgentString + " CinemaTrackerApp/5.1.0"

        webView.addJavascriptInterface(AndroidBridge(), "AndroidBridge")

        webView.webChromeClient = WebChromeClient()
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: return false
                if (url.startsWith("file:///android_asset/")) return false
                openExternalUrl(url)
                return true
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                webView.evaluateJavascript("window.handleAndroidBack && window.handleAndroidBack()") { res ->
                    if (res != "true") {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        })

        requestNotifPermission()
        scheduleBackgroundWorker()
        webView.loadUrl("file:///android_asset/index.html")
    }

    private fun requestNotifPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun scheduleBackgroundWorker() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val request = PeriodicWorkRequestBuilder<CinemaCheckWorker>(6, TimeUnit.HOURS)
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "CinemaTrackerPeriodicCheck",
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }

    private fun openExternalUrl(url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        } catch (_: Exception) {
            Toast.makeText(this, "Tidak ada aplikasi untuk membuka tautan", Toast.LENGTH_SHORT).show()
        }
    }

    inner class AndroidBridge {

        @JavascriptInterface
        fun getStorageItem(key: String): String {
            val prefs = getSharedPreferences("CinemaTrackerPrefs", Context.MODE_PRIVATE)
            return prefs.getString(key, "") ?: ""
        }

        @JavascriptInterface
        fun setStorageItem(key: String, value: String) {
            val prefs = getSharedPreferences("CinemaTrackerPrefs", Context.MODE_PRIVATE)
            prefs.edit().putString(key, value).apply()
        }

        @JavascriptInterface
        fun openLetterboxd(tmdbId: Int, slug: String) {
            val cleanSlug = slug.trim()
            val primaryUrl = if (tmdbId > 0) {
                "https://letterboxd.com/tmdb/$tmdbId/"
            } else if (cleanSlug.isNotEmpty()) {
                "https://letterboxd.com/film/$cleanSlug/"
            } else {
                "https://letterboxd.com/"
            }
            openExternalUrl(primaryUrl)
        }

        @JavascriptInterface
        fun openTrailerDirect(youtubeKey: String, title: String, year: String) {
            val cleanKey = youtubeKey.trim()
            if (cleanKey.isNotEmpty()) {
                val appIntent = Intent(Intent.ACTION_VIEW, Uri.parse("vnd.youtube:$cleanKey"))
                appIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                try {
                    startActivity(appIntent)
                    return
                } catch (_: Exception) {
                    openExternalUrl("https://www.youtube.com/watch?v=$cleanKey")
                    return
                }
            }

            // Fallback: resolve first official trailer directly without leaving user on search page
            Thread {
                val resolvedId = resolveFirstYoutubeVideoId("$title $year official trailer")
                runOnUiThread {
                    if (!resolvedId.isNullOrEmpty()) {
                        try {
                            val ytIntent = Intent(Intent.ACTION_VIEW, Uri.parse("vnd.youtube:$resolvedId"))
                            ytIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            startActivity(ytIntent)
                        } catch (_: Exception) {
                            openExternalUrl("https://www.youtube.com/watch?v=$resolvedId")
                        }
                    } else {
                        val q = URLEncoder.encode("$title $year official trailer", "UTF-8")
                        openExternalUrl("https://www.youtube.com/results?search_query=$q")
                    }
                }
            }.start()
        }

        private fun resolveFirstYoutubeVideoId(query: String): String? {
            return try {
                val encoded = URLEncoder.encode(query, "UTF-8")
                val conn = URL("https://www.youtube.com/results?search_query=$encoded").openConnection() as HttpURLConnection
                conn.connectTimeout = 6000
                conn.readTimeout = 6000
                conn.setRequestProperty("User-Agent", "Mozilla/5.0")
                val html = conn.inputStream.bufferedReader().use { it.readText() }
                conn.disconnect()
                val match = Regex("\u0022videoId\u0022:\u0022([a-zA-Z0-9_-]{11})\u0022").find(html)
                match?.groupValues?.get(1)
            } catch (_: Exception) {
                null
            }
        }

        @JavascriptInterface
        fun downloadPoster(imageUrl: String, fileName: String) {
            try {
                val request = DownloadManager.Request(Uri.parse(imageUrl))
                    .setTitle(fileName)
                    .setDescription("Mengunduh poster HD Cinema Tracker")
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
                val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                dm.enqueue(request)
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Mengunduh poster ke Downloads...", Toast.LENGTH_SHORT).show()
                }
            } catch (_: Exception) {
                openExternalUrl(imageUrl)
            }
        }

        @JavascriptInterface
        fun switchAppIcon(variant: String) {
            val pm = packageManager
            val aliases = mapOf(
                "crimson" to ComponentName(this@MainActivity, "com.michael.cinematracker.MainActivity"),
                "gold" to ComponentName(this@MainActivity, "com.michael.cinematracker.LauncherTicketGold"),
                "blue" to ComponentName(this@MainActivity, "com.michael.cinematracker.LauncherProjectorBlue")
            )
            aliases.forEach { (key, comp) ->
                val state = if (key == variant) {
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                } else {
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                }
                pm.setComponentEnabledSetting(comp, state, PackageManager.DONT_KILL_APP)
            }
            runOnUiThread {
                Toast.makeText(this@MainActivity, "Ikon aplikasi diperbarui", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
