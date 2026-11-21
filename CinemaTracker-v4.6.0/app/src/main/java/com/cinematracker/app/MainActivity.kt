package com.cinematracker.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
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
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { _ -> }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, true)
        window.statusBarColor = Color.parseColor("#09090B")
        window.navigationBarColor = Color.parseColor("#09090B")

        createNotificationChannel()
        requestNotificationPermissionIfNeeded()

        webView = WebView(this).apply {
            setBackgroundColor(Color.parseColor("#09090B"))
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
                    "(function(){ if(window.handleNativeBack && window.handleNativeBack()) return 'handled'; return 'exit'; })()"
                ) { result ->
                    if (result != ""handled"") {
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
                ReleaseCheckWorker.CHANNEL_ID,
                "Jadwal Tayang & Presale Bioskop",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifikasi jadwal tayang bioskop Indonesia dan pembukaan tiket presale"
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

        val workRequest = PeriodicWorkRequestBuilder<ReleaseCheckWorker>(6, TimeUnit.HOURS)
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "cinema_tracker_release_sync",
            ExistingPeriodicWorkPolicy.KEEP,
            workRequest
        )
    }

    inner class WebAppInterface(private val context: Context) {

        /**
         * Opens the exact Letterboxd film profile page using Letterboxd's canonical TMDB resolver:
         * https://letterboxd.com/tmdb/<tmdbId> -> auto-redirects directly to /film/<slug>/
         * (and opens natively in the Letterboxd Android app when installed).
         */
        @JavascriptInterface
        fun openLetterboxd(tmdbId: Int) {
            runOnUiThread {
                val url = "https://letterboxd.com/tmdb/$tmdbId"
                openExternalUri(Uri.parse(url))
            }
        }

        /**
         * Launches official YouTube trailer directly (prefers native YouTube app intent).
         */
        @JavascriptInterface
        fun openTrailer(youtubeKey: String, fallbackQuery: String) {
            runOnUiThread {
                if (youtubeKey.isNotBlank()) {
                    try {
                        val appIntent = Intent(Intent.ACTION_VIEW, Uri.parse("vnd.youtube:$youtubeKey")).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        startActivity(appIntent)
                        return@runOnUiThread
                    } catch (_: ActivityNotFoundException) {
                        openExternalUri(Uri.parse("https://www.youtube.com/watch?v=$youtubeKey"))
                        return@runOnUiThread
                    }
                }
                val encoded = Uri.encode(fallbackQuery)
                openExternalUri(Uri.parse("https://www.youtube.com/results?search_query=$encoded"))
            }
        }

        @JavascriptInterface
        fun syncState(watchlistJson: String, apiKey: String) {
            val prefs = context.getSharedPreferences(ReleaseCheckWorker.PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit()
                .putString(ReleaseCheckWorker.KEY_WATCHLIST, watchlistJson)
                .putString(ReleaseCheckWorker.KEY_API_KEY, apiKey)
                .apply()
        }

        @JavascriptInterface
        fun sendTestNotification(movieTitle: String) {
            runOnUiThread {
                val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                val notification = NotificationCompat.Builder(context, ReleaseCheckWorker.CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle("Radar Presale & Tayang")
                    .setContentText("$movieTitle siap dipantau untuk jadwal bioskop Indonesia.")
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setAutoCancel(true)
                    .build()
                manager.notify(9901, notification)
            }
        }

        @JavascriptInterface
        fun switchLauncherIcon(variant: String) {
            runOnUiThread {
                val aliases = listOf(
                    "default" to "com.cinematracker.app.LauncherDefault",
                    "obsidian" to "com.cinematracker.app.LauncherObsidian",
                    "crimson" to "com.cinematracker.app.LauncherCrimson"
                )
                val pm = context.packageManager
                aliases.forEach { (key, className) ->
                    val state = if (key == variant) {
                        PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                    } else {
                        PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                    }
                    pm.setComponentEnabledSetting(
                        ComponentName(context, className),
                        state,
                        PackageManager.DONT_KILL_APP
                    )
                }
                Toast.makeText(context, "Ikon aplikasi diperbarui", Toast.LENGTH_SHORT).show()
            }
        }

        @JavascriptInterface
        fun clearCache() {
            runOnUiThread {
                webView.clearCache(true)
                Toast.makeText(context, "Cache dibersihkan", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
