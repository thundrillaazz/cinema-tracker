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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private val activityScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

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
                    "(function(){ if(window.handleNativeBack && window.handleNativeBack()) return 'handled'; return 'exit'; })()"
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

    override fun onDestroy() {
        activityScope.cancel()
        webView.destroy()
        super.onDestroy()
    }

    private fun openExternalUri(uri: Uri) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, "Aplikasi tujuan tidak ditemukan", Toast.LENGTH_SHORT).show()
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
                description = "Notifikasi jadwal bioskop ID, TBA terkonfirmasi, pre-sale tiket, H-1, dan hari-H"
                enableVibration(true)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
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

        /**
         * 1-Tap direct open to Letterboxd movie page (resolves canonical redirect or opens Letterboxd app directly).
         */
        @JavascriptInterface
        fun openLetterboxdDirect(tmdbId: Int, movieTitle: String, releaseYear: String) {
            activityScope.launch {
                val resolvedUri = withContext(Dispatchers.IO) {
                    resolveLetterboxdUri(tmdbId, movieTitle, releaseYear)
                }
                val lbIntent = Intent(Intent.ACTION_VIEW, resolvedUri).apply {
                    setPackage("com.letterboxd.letterboxd")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                try {
                    startActivity(lbIntent)
                } catch (_: ActivityNotFoundException) {
                    openExternalUri(resolvedUri)
                }
            }
        }

        /**
         * 1-Tap direct open to YouTube trailer video (fetches TMDB /videos or scrapes first watch?v= ID so it NEVER stops on a search bar).
         */
        @JavascriptInterface
        fun openTrailerDirect(tmdbId: Int, cachedYtKey: String, movieTitle: String, releaseYear: String, apiKey: String) {
            activityScope.launch {
                val videoId = if (cachedYtKey.isNotBlank()) {
                    cachedYtKey
                } else {
                    withContext(Dispatchers.IO) {
                        resolveYouTubeTrailerId(tmdbId, movieTitle, releaseYear, apiKey)
                    }
                }

                if (!videoId.isNullOrBlank()) {
                    try {
                        startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse("vnd.youtube:$videoId"))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    } catch (_: ActivityNotFoundException) {
                        openExternalUri(Uri.parse("https://www.youtube.com/watch?v=$videoId"))
                    }
                } else {
                    val q = Uri.encode("$movieTitle $releaseYear official trailer")
                    openExternalUri(Uri.parse("https://m.youtube.com/results?search_query=$q"))
                }
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
                            Toast.makeText(context, "Poster disimpan ke Galeri", Toast.LENGTH_SHORT).show()
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
                    Toast.makeText(context, "Poster tersimpan: ${outFile.name}", Toast.LENGTH_SHORT).show()
                } catch (_: Exception) {
                    Toast.makeText(context, "Gagal menyimpan poster", Toast.LENGTH_SHORT).show()
                }
            }
        }

        @JavascriptInterface
        fun pushInstantAlert(title: String, message: String) {
            runOnUiThread {
                val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                val notification = NotificationCompat.Builder(context, CinemaCheckWorker.CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle(title)
                    .setContentText(message)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(message))
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setAutoCancel(true)
                    .build()
                manager.notify((title + message).hashCode(), notification)
            }
        }

        @JavascriptInterface
        fun switchLauncherIcon(styleKey: String) {
            runOnUiThread {
                val map = mapOf(
                    "cinema_blue" to "com.michael.cinematracker.LauncherCinemaBlue",
                    "kino_trio" to "com.michael.cinematracker.LauncherKinoTrio",
                    "arrakis_spice" to "com.michael.cinematracker.LauncherArrakisSpice",
                    "monolith_2001" to "com.michael.cinematracker.LauncherMonolith2001",
                    "grand_budapest" to "com.michael.cinematracker.LauncherGrandBudapest",
                    "neon_2049" to "com.michael.cinematracker.LauncherNeon2049",
                    "imax_horizon" to "com.michael.cinematracker.LauncherImaxHorizon",
                    "kodak_35mm" to "com.michael.cinematracker.LauncherKodak35mm",
                    "trinity_ember" to "com.michael.cinematracker.LauncherTrinityEmber",
                    "emerald_cut" to "com.michael.cinematracker.LauncherEmeraldCut"
                )
                val targetClass = map[styleKey] ?: return@runOnUiThread
                val pm = context.packageManager
                map.values.forEach { cls ->
                    val state = if (cls == targetClass) {
                        PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                    } else {
                        PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                    }
                    pm.setComponentEnabledSetting(
                        ComponentName(context, cls),
                        state,
                        PackageManager.DONT_KILL_APP
                    )
                }
                Toast.makeText(context, "Ikon aplikasi diperbarui", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun resolveLetterboxdUri(tmdbId: Int, movieTitle: String, releaseYear: String): Uri {
        val slug = movieTitle.lowercase()
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')

        if (tmdbId > 0) {
            try {
                val conn = (URL("https://letterboxd.com/tmdb/$tmdbId/").openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = true
                    connectTimeout = 4500
                    readTimeout = 4500
                    setRequestProperty("User-Agent", "Mozilla/5.0")
                }
                conn.responseCode
                val finalUrl = conn.url.toString()
                conn.disconnect()
                if (finalUrl.contains("letterboxd.com/film/")) {
                    return Uri.parse(finalUrl)
                }
            } catch (_: Exception) {
            }
        }

        if (releaseYear.length == 4) {
            try {
                val yearUrl = "https://letterboxd.com/film/$slug-$releaseYear/"
                val conn = (URL(yearUrl).openConnection() as HttpURLConnection).apply {
                    requestMethod = "HEAD"
                    connectTimeout = 3500
                    readTimeout = 3500
                }
                val code = conn.responseCode
                conn.disconnect()
                if (code == 200) return Uri.parse(yearUrl)
            } catch (_: Exception) {
            }
        }

        return Uri.parse("https://letterboxd.com/film/$slug/")
    }

    private fun resolveYouTubeTrailerId(tmdbId: Int, movieTitle: String, releaseYear: String, apiKey: String): String? {
        if (tmdbId > 0 && apiKey.isNotBlank()) {
            try {
                val apiUrl = "https://api.themoviedb.org/3/movie/$tmdbId/videos?api_key=${URLEncoder.encode(apiKey, "UTF-8")}"
                val conn = (URL(apiUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 4500
                    readTimeout = 4500
                }
                if (conn.responseCode == 200) {
                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    conn.disconnect()
                    val arr = JSONObject(body).optJSONArray("results")
                    if (arr != null) {
                        var fallbackKey: String? = null
                        for (i in 0 until arr.length()) {
                            val v = arr.getJSONObject(i)
                            if (v.optString("site").equals("YouTube", true)) {
                                val key = v.optString("key")
                                val type = v.optString("type")
                                if (type.equals("Trailer", true) && key.isNotBlank()) return key
                                if (fallbackKey == null && key.isNotBlank()) fallbackKey = key
                            }
                        }
                        if (fallbackKey != null) return fallbackKey
                    }
                } else {
                    conn.disconnect()
                }
            } catch (_: Exception) {
            }
        }

        // Direct fallback: fetch first YouTube video ID so user lands straight on the trailer video player
        return try {
            val q = URLEncoder.encode("$movieTitle $releaseYear official trailer", "UTF-8")
            val conn = (URL("https://www.youtube.com/results?search_query=$q").openConnection() as HttpURLConnection).apply {
                connectTimeout = 4500
                readTimeout = 4500
                setRequestProperty("User-Agent", "Mozilla/5.0")
            }
            val html = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()
            val match = Regex("\x22videoId\x22:\x22([a-zA-Z0-9_-]{11})\x22").find(html)
            match?.groupValues?.get(1)
        } catch (_: Exception) {
            null
        }
    }
}
