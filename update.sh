#!/bin/bash
echo "Memulai update Cinema Tracker v5.0 (Mix TIX ID + Clean UI)..."

# ==========================================
# 1. UPDATE ANDROID MANIFEST (Untuk Custom Icon)
# ==========================================
cat << 'XMLEOF' > app/src/main/AndroidManifest.xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
    <uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
    <uses-permission android:name="android.permission.VIBRATE" />

    <queries>
        <intent>
            <action android:name="android.intent.action.VIEW" />
            <data android:scheme="https" />
        </intent>
        <intent>
            <action android:name="android.intent.action.VIEW" />
            <data android:scheme="vnd.youtube" />
        </intent>
        <package android:name="com.letterboxd.letterboxd" />
        <package android:name="com.google.android.youtube" />
    </queries>

    <application
        android:allowBackup="true"
        android:icon="@mipmap/ic_launcher"
        android:label="@string/app_name"
        android:networkSecurityConfig="@xml/network_security_config"
        android:roundIcon="@mipmap/ic_launcher"
        android:supportsRtl="true"
        android:theme="@style/Theme.CinemaTracker"
        android:usesCleartextTraffic="false">

        <activity
            android:name=".MainActivity"
            android:configChanges="orientation|screenSize|keyboardHidden"
            android:exported="true"
            android:launchMode="singleTop"
            android:windowSoftInputMode="adjustResize" />

        <!-- ALIASES UNTUK CUSTOM ICON -->
        <activity-alias android:name=".LauncherCinemaBlue" android:enabled="true" android:exported="true" android:icon="@mipmap/ic_launcher_cinema_blue" android:label="@string/app_name" android:roundIcon="@mipmap/ic_launcher_cinema_blue" android:targetActivity=".MainActivity"><intent-filter><action android:name="android.intent.action.MAIN" /><category android:name="android.intent.category.LAUNCHER" /></intent-filter></activity-alias>
        
        <activity-alias android:name=".LauncherKinoTrio" android:enabled="false" android:exported="true" android:icon="@mipmap/ic_launcher_kino_trio" android:label="@string/app_name" android:roundIcon="@mipmap/ic_launcher_kino_trio" android:targetActivity=".MainActivity"><intent-filter><action android:name="android.intent.action.MAIN" /><category android:name="android.intent.category.LAUNCHER" /></intent-filter></activity-alias>
        
        <activity-alias android:name=".LauncherArrakisSpice" android:enabled="false" android:exported="true" android:icon="@mipmap/ic_launcher_arrakis_spice" android:label="@string/app_name" android:roundIcon="@mipmap/ic_launcher_arrakis_spice" android:targetActivity=".MainActivity"><intent-filter><action android:name="android.intent.action.MAIN" /><category android:name="android.intent.category.LAUNCHER" /></intent-filter></activity-alias>
        
        <activity-alias android:name=".LauncherMonolith2001" android:enabled="false" android:exported="true" android:icon="@mipmap/ic_launcher_monolith_2001" android:label="@string/app_name" android:roundIcon="@mipmap/ic_launcher_monolith_2001" android:targetActivity=".MainActivity"><intent-filter><action android:name="android.intent.action.MAIN" /><category android:name="android.intent.category.LAUNCHER" /></intent-filter></activity-alias>
        
        <activity-alias android:name=".LauncherGrandBudapest" android:enabled="false" android:exported="true" android:icon="@mipmap/ic_launcher_grand_budapest" android:label="@string/app_name" android:roundIcon="@mipmap/ic_launcher_grand_budapest" android:targetActivity=".MainActivity"><intent-filter><action android:name="android.intent.action.MAIN" /><category android:name="android.intent.category.LAUNCHER" /></intent-filter></activity-alias>
        
        <activity-alias android:name=".LauncherNeon2049" android:enabled="false" android:exported="true" android:icon="@mipmap/ic_launcher_neon_2049" android:label="@string/app_name" android:roundIcon="@mipmap/ic_launcher_neon_2049" android:targetActivity=".MainActivity"><intent-filter><action android:name="android.intent.action.MAIN" /><category android:name="android.intent.category.LAUNCHER" /></intent-filter></activity-alias>
        
        <activity-alias android:name=".LauncherImaxHorizon" android:enabled="false" android:exported="true" android:icon="@mipmap/ic_launcher_imax_horizon" android:label="@string/app_name" android:roundIcon="@mipmap/ic_launcher_imax_horizon" android:targetActivity=".MainActivity"><intent-filter><action android:name="android.intent.action.MAIN" /><category android:name="android.intent.category.LAUNCHER" /></intent-filter></activity-alias>
        
        <activity-alias android:name=".LauncherKodak35mm" android:enabled="false" android:exported="true" android:icon="@mipmap/ic_launcher_kodak_35mm" android:label="@string/app_name" android:roundIcon="@mipmap/ic_launcher_kodak_35mm" android:targetActivity=".MainActivity"><intent-filter><action android:name="android.intent.action.MAIN" /><category android:name="android.intent.category.LAUNCHER" /></intent-filter></activity-alias>
        
        <activity-alias android:name=".LauncherTrinityEmber" android:enabled="false" android:exported="true" android:icon="@mipmap/ic_launcher_trinity_ember" android:label="@string/app_name" android:roundIcon="@mipmap/ic_launcher_trinity_ember" android:targetActivity=".MainActivity"><intent-filter><action android:name="android.intent.action.MAIN" /><category android:name="android.intent.category.LAUNCHER" /></intent-filter></activity-alias>
        
        <activity-alias android:name=".LauncherEmeraldCut" android:enabled="false" android:exported="true" android:icon="@mipmap/ic_launcher_emerald_cut" android:label="@string/app_name" android:roundIcon="@mipmap/ic_launcher_emerald_cut" android:targetActivity=".MainActivity"><intent-filter><action android:name="android.intent.action.MAIN" /><category android:name="android.intent.category.LAUNCHER" /></intent-filter></activity-alias>

    </application>
</manifest>
XMLEOF

# ==========================================
# 2. MAIN ACTIVITY KOTLIN (Bridge & Custom Icon)
# ==========================================
cat << 'KTEOF' > app/src/main/java/com/michael/cinematracker/MainActivity.kt
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
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
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
                        if (webView.canGoBack()) webView.goBack() else finish()
                    }
                }
            }
        })
    }

    private fun openExternalUri(uri: Uri) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, uri).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, "Aplikasi tidak ditemukan", Toast.LENGTH_SHORT).show()
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CinemaCheckWorker.CHANNEL_ID, "Radar Bioskop & Presale", NotificationManager.IMPORTANCE_HIGH
            ).apply { description = "Notifikasi jadwal rilis, presale, dan H-1" }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun scheduleReleaseWorker() {
        val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        val workRequest = PeriodicWorkRequestBuilder<CinemaCheckWorker>(6, TimeUnit.HOURS).setConstraints(constraints).build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork("cinema_tracker_sync", ExistingPeriodicWorkPolicy.KEEP, workRequest)
    }

    inner class WebAppInterface(private val context: Context) {

        @JavascriptInterface
        fun openLetterboxdDirect(tmdbId: Int, slugFallback: String) {
            runOnUiThread {
                val url = if (tmdbId > 0) "https://letterboxd.com/tmdb/$tmdbId/" else "https://letterboxd.com/film/$slugFallback/"
                openExternalUri(Uri.parse(url))
            }
        }

        @JavascriptInterface
        fun openTrailerDirect(youtubeKey: String, movieTitle: String) {
            runOnUiThread {
                if (youtubeKey.isNotBlank()) {
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("vnd.youtube:$youtubeKey")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    } catch (_: ActivityNotFoundException) {
                        openExternalUri(Uri.parse("https://www.youtube.com/watch?v=$youtubeKey"))
                    }
                } else {
                    val q = Uri.encode("$movieTitle official trailer")
                    openExternalUri(Uri.parse("https://m.youtube.com/results?search_query=$q"))
                }
            }
        }

        @JavascriptInterface
        fun syncRadarState(watchlistJson: String, tbaJson: String, apiKey: String) {
            context.getSharedPreferences(CinemaCheckWorker.PREFS_NAME, Context.MODE_PRIVATE).edit()
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
                            Toast.makeText(context, "Poster tersimpan di Galeri", Toast.LENGTH_SHORT).show()
                            return@runOnUiThread
                        }
                    }
                    val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "CinemaTracker")
                    if (!dir.exists()) dir.mkdirs()
                    FileOutputStream(File(dir, safeName)).use { it.write(bytes) }
                    Toast.makeText(context, "Poster tersimpan", Toast.LENGTH_SHORT).show()
                } catch (_: Exception) {
                    Toast.makeText(context, "Gagal menyimpan poster", Toast.LENGTH_SHORT).show()
                }
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
                    val state = if (cls == targetClass) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                    pm.setComponentEnabledSetting(ComponentName(context, cls), state, PackageManager.DONT_KILL_APP)
                }
                Toast.makeText(context, "Ikon aplikasi diperbarui", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
KTEOF

# ==========================================
# 3. CINEMA CHECK WORKER KOTLIN (Notifikasi)
# ==========================================
cat << 'KTEOF' > app/src/main/java/com/michael/cinematracker/CinemaCheckWorker.kt
package com.michael.cinematracker

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.time.temporal.ChronoUnit

class CinemaCheckWorker(appContext: Context, workerParams: WorkerParameters) : CoroutineWorker(appContext, workerParams) {
    companion object {
        const val PREFS_NAME = "cinema_tracker_prefs"
        const val KEY_WATCHLIST = "watchlist_json"
        const val KEY_TBA = "tba_json"
        const val KEY_API_KEY = "tmdb_api_key"
        const val CHANNEL_ID = "cinema_tracker_alerts"
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val prefs = applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val apiKey = prefs.getString(KEY_API_KEY, "")?.trim().orEmpty()
        val watchlistRaw = prefs.getString(KEY_WATCHLIST, "[]").orEmpty()
        val tbaRaw = prefs.getString(KEY_TBA, "[]").orEmpty()

        if (apiKey.isEmpty()) return@withContext Result.success()

        try {
            val today = LocalDate.now()
            val tbaList = JSONArray(tbaRaw)
            for (i in 0 until tbaList.length()) {
                val item = tbaList.getJSONObject(i)
                val movieId = item.optInt("id", 0)
                val title = item.optString("title", "Film TBA")
                if (movieId == 0) continue
                val idDate = fetchIndonesiaReleaseDate(movieId, apiKey)
                if (!idDate.isNullOrBlank()) notifyOnce(prefs, movieId, "tba_indo_$idDate", "TBA Masuk Jadwal Indo: $title", "Jadwal tayang bioskop Indonesia terkonfirmasi: $idDate.")
            }

            val watchlist = JSONArray(watchlistRaw)
            for (i in 0 until watchlist.length()) {
                val item = watchlist.getJSONObject(i)
                val movieId = item.optInt("id", 0)
                val title = item.optString("title", "Film")
                val notifyPresale = item.optBoolean("notify_presale", true)
                val notifyH1 = item.optBoolean("notify_h1", true)
                val notifyH0 = item.optBoolean("notify_h0", true)

                var releaseDate = item.optString("release_date", "")
                val refreshed = fetchIndonesiaReleaseDate(movieId, apiKey)
                if (!refreshed.isNullOrBlank()) releaseDate = refreshed

                if (releaseDate.length >= 10) {
                    val target = LocalDate.parse(releaseDate.substring(0, 10))
                    val days = ChronoUnit.DAYS.between(today, target)

                    if (days == 7L && notifyPresale) notifyOnce(prefs, movieId, "presale_$releaseDate", "Open Pre-Sale: $title", "Tayang $releaseDate. Cek presale tiket bioskop sekarang!")
                    if (days == 1L && notifyH1) notifyOnce(prefs, movieId, "h1_$releaseDate", "Besok Tayang (H-1): $title", "$title mulai tayang besok di bioskop Indonesia.")
                    if (days == 0L && notifyH0) notifyOnce(prefs, movieId, "h0_$releaseDate", "Hari Perdana: $title", "$title resmi tayang hari ini di bioskop.")
                }
            }
            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }

    private fun fetchIndonesiaReleaseDate(movieId: Int, apiKey: String): String? {
        val urlStr = "https://api.themoviedb.org/3/movie/$movieId/release_dates?api_key=$apiKey"
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply { connectTimeout = 8000; readTimeout = 8000 }
        return try {
            if (conn.responseCode != 200) return null
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val results = JSONObject(body).optJSONArray("results") ?: return null
            for (i in 0 until results.length()) {
                val country = results.getJSONObject(i)
                if (country.optString("iso_3166_1") == "ID") {
                    val dates = country.optJSONArray("release_dates") ?: continue
                    for (j in 0 until dates.length()) {
                        val d = dates.getJSONObject(j)
                        if (d.optInt("type", 0) == 3 || d.optInt("type", 0) == 2) {
                            val date = d.optString("release_date").take(10)
                            if (date.isNotBlank()) return date
                        }
                    }
                }
            }
            null
        } finally {
            conn.disconnect()
        }
    }

    private fun notifyOnce(prefs: android.content.SharedPreferences, movieId: Int, tag: String, title: String, message: String) {
        val key = "alert_${movieId}_$tag"
        if (prefs.getBoolean(key, false)) return

        val intent = Intent(applicationContext, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP }
        val pi = PendingIntent.getActivity(applicationContext, movieId, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notif = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()

        manager.notify((movieId.toString() + tag).hashCode(), notif)
        prefs.edit().putBoolean(key, true).apply()
    }
}
KTEOF

# ==========================================
# 4. INDEX HTML (Mix TIX ID + Clean UI)
# ==========================================
cat << 'HTMLEOF' > app/src/main/assets/index.html
<!DOCTYPE html>
<html lang="id">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
    <title>Cinema Tracker</title>
    <style>
        :root {
            --bg: #F8FAFC; --card: #FFFFFF; --text: #0F172A; --text-sub: #64748B;
            --accent: #E11D48; --border: #E2E8F0; --nav-bg: #FFFFFF;
            --shadow: 0 4px 12px rgba(0,0,0,0.05);
        }
        [data-theme="dark"] {
            --bg: #0F172A; --card: #1E293B; --text: #F8FAFC; --text-sub: #94A3B8;
            --border: #334155; --nav-bg: #1E293B;
            --shadow: 0 4px 12px rgba(0,0,0,0.3);
        }
        * { box-sizing: border-box; margin: 0; padding: 0; -webkit-tap-highlight-color: transparent; font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif; }
        body { background: var(--bg); color: var(--text); padding-bottom: 70px; overflow-x: hidden; transition: background 0.3s; }

        /* HEADER & TABS */
        header { padding: 20px 16px 0; background: var(--bg); position: sticky; top: 0; z-index: 10; }
        .header-top { display: flex; justify-content: space-between; align-items: flex-start; margin-bottom: 16px; }
        .header-title { font-size: 24px; font-weight: 800; display: flex; align-items: center; gap: 8px; }
        .header-sub { font-size: 13px; color: var(--text-sub); font-weight: 500; margin-top: 4px; }
        
        .main-tabs { display: flex; border-bottom: 1px solid var(--border); margin-bottom: 16px; }
        .main-tab { flex: 1; text-align: center; padding: 12px 0; font-weight: 700; font-size: 14px; color: var(--text-sub); cursor: pointer; border-bottom: 3px solid transparent; transition: 0.2s; }
        .main-tab.active { color: var(--text); border-bottom-color: var(--text); }

        /* LAYOUTS */
        .view-container { display: none; padding: 0 16px; animation: fadeIn 0.3s ease; }
        .view-container.active { display: block; }
        @keyframes fadeIn { from { opacity: 0; transform: translateY(5px); } to { opacity: 1; transform: translateY(0); } }

        /* SEARCH */
        .search-bar { background: var(--card); border: 1px solid var(--border); border-radius: 12px; padding: 12px 16px; display: flex; align-items: center; gap: 10px; margin-bottom: 16px; }
        .search-bar input { border: none; background: transparent; color: var(--text); font-size: 15px; width: 100%; outline: none; }

        /* GRID */
        .movie-grid { display: grid; grid-template-columns: repeat(2, 1fr); gap: 16px; }
        .card { background: var(--card); border-radius: 16px; overflow: hidden; box-shadow: var(--shadow); position: relative; cursor: pointer; border: 1px solid var(--border); transition: transform 0.2s; }
        .card:active { transform: scale(0.97); }
        .card-img-wrapper { position: relative; aspect-ratio: 2/3; background: var(--border); }
        .card-img { width: 100%; height: 100%; object-fit: cover; display: block; }
        
        .badge-imax { position: absolute; top: 8px; left: 8px; background: #FBBF24; color: #000; font-size: 10px; font-weight: 800; padding: 2px 6px; border-radius: 4px; }
        .btn-bookmark { position: absolute; top: 8px; right: 8px; background: rgba(0,0,0,0.5); backdrop-filter: blur(4px); border: none; border-radius: 50%; width: 34px; height: 34px; display: flex; align-items: center; justify-content: center; cursor: pointer; z-index: 2; }
        .btn-bookmark.active { background: var(--accent); }
        .btn-bookmark svg { width: 16px; height: 16px; fill: none; stroke: #FFF; stroke-width: 2; }
        .btn-bookmark.active svg { fill: #FFF; }

        .card-info { padding: 12px; }
        .card-genre { font-size: 11px; color: var(--text-sub); margin-bottom: 4px; }
        .card-title { font-size: 14px; font-weight: 700; margin-bottom: 6px; display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden; line-height: 1.3; }
        .card-bottom { display: flex; justify-content: space-between; align-items: center; font-size: 12px; font-weight: 600; }
        .card-date { color: var(--text-sub); }
        .badge-hype { color: #2563EB; background: #EFF6FF; padding: 2px 6px; border-radius: 4px; }
        [data-theme="dark"] .badge-hype { background: rgba(37,99,235,0.2); }

        /* WATCHLIST */
        .filter-chips { display: flex; gap: 8px; overflow-x: auto; padding-bottom: 12px; scrollbar-width: none; }
        .filter-chips::-webkit-scrollbar { display: none; }
        .chip { padding: 8px 16px; border-radius: 20px; background: var(--card); border: 1px solid var(--border); font-size: 13px; font-weight: 600; color: var(--text-sub); white-space: nowrap; cursor: pointer; }
        .chip.active { background: var(--text); color: var(--bg); border-color: var(--text); }

        .watchlist-item { display: flex; gap: 16px; background: var(--card); padding: 12px; border-radius: 16px; margin-bottom: 12px; border: 1px solid var(--border); align-items: center; cursor: pointer; }
        .wl-img { width: 70px; height: 105px; border-radius: 8px; object-fit: cover; }
        .wl-info { flex: 1; }
        .wl-title { font-size: 15px; font-weight: 700; margin-bottom: 4px; }
        .wl-meta { font-size: 12px; color: var(--text-sub); margin-bottom: 8px; }
        .wl-countdown { font-size: 13px; font-weight: 800; color: var(--accent); display: flex; align-items: center; gap: 6px; }
        .wl-countdown.passed { color: #10B981; }

        /* MODAL DETAIL (RICH TAPI CLEAN) */
        .modal { position: fixed; inset: 0; background: var(--bg); z-index: 100; display: none; flex-direction: column; overflow-y: auto; }
        .modal.active { display: flex; animation: slideUp 0.3s ease; }
        @keyframes slideUp { from { transform: translateY(100%); } to { transform: translateY(0); } }
        .modal-hero { position: relative; width: 100%; height: 45vh; }
        .modal-hero img { width: 100%; height: 100%; object-fit: cover; }
        .modal-hero::after { content: ''; position: absolute; inset: 0; background: linear-gradient(to top, var(--bg) 0%, transparent 80%); }
        .btn-close { position: absolute; top: 20px; right: 20px; background: rgba(0,0,0,0.6); border: none; border-radius: 50%; width: 40px; height: 40px; color: white; font-size: 20px; cursor: pointer; z-index: 10; display: flex; align-items: center; justify-content: center; backdrop-filter: blur(4px); }
        
        .modal-content { padding: 0 20px 40px; margin-top: -40px; position: relative; z-index: 2; }
        .modal-title { font-size: 24px; font-weight: 800; line-height: 1.2; margin-bottom: 8px; }
        .modal-meta { display: flex; gap: 10px; font-size: 13px; color: var(--text-sub); margin-bottom: 16px; font-weight: 500; flex-wrap: wrap; }
        .modal-meta span { display: flex; align-items: center; gap: 4px; }

        .info-box { background: var(--card); border: 1px solid var(--border); border-radius: 12px; padding: 16px; margin-bottom: 16px; }
        .info-box-title { font-size: 11px; font-weight: 700; color: var(--text-sub); letter-spacing: 0.5px; margin-bottom: 8px; text-transform: uppercase; }
        .info-box-main { font-size: 15px; font-weight: 700; margin-bottom: 4px; }
        .info-box-sub { font-size: 13px; color: var(--text-sub); }

        .action-grid { display: grid; grid-template-columns: 1fr 1fr 1fr; gap: 8px; margin-bottom: 16px; }
        .btn-action { background: var(--card); border: 1px solid var(--border); border-radius: 12px; padding: 12px; display: flex; flex-direction: column; align-items: center; gap: 8px; font-size: 12px; font-weight: 600; color: var(--text); cursor: pointer; transition: 0.2s; }
        .btn-action:active { background: var(--border); }
        .btn-action svg { width: 20px; height: 20px; fill: none; stroke: currentColor; stroke-width: 2; }

        .btn-watchlist-large { width: 100%; padding: 16px; border-radius: 12px; border: none; font-size: 15px; font-weight: 700; display: flex; align-items: center; justify-content: center; gap: 8px; cursor: pointer; background: var(--accent); color: white; margin-bottom: 24px; transition: 0.2s; }
        .btn-watchlist-large.active { background: transparent; border: 2px solid var(--accent); color: var(--accent); }

        .notif-toggles { display: flex; flex-direction: column; gap: 12px; margin-bottom: 16px; }
        .toggle-row { display: flex; justify-content: space-between; align-items: center; }
        .toggle-label { font-size: 14px; font-weight: 600; }
        .toggle-sub { font-size: 12px; color: var(--text-sub); margin-top: 2px; }
        .switch { position: relative; display: inline-block; width: 44px; height: 24px; }
        .switch input { opacity: 0; width: 0; height: 0; }
        .slider { position: absolute; cursor: pointer; top: 0; left: 0; right: 0; bottom: 0; background-color: var(--border); transition: .4s; border-radius: 24px; }
        .slider:before { position: absolute; content: ""; height: 18px; width: 18px; left: 3px; bottom: 3px; background-color: white; transition: .4s; border-radius: 50%; }
        input:checked + .slider { background-color: #2563EB; }
        input:checked + .slider:before { transform: translateX(20px); }

        .sinopsis { font-size: 14px; line-height: 1.6; color: var(--text-sub); margin-bottom: 24px; }
        .credits-row { display: flex; margin-bottom: 12px; font-size: 14px; }
        .credits-label { width: 90px; color: var(--text-sub); font-weight: 600; }
        .credits-val { flex: 1; font-weight: 500; }

        /* BOTTOM NAV */
        .bottom-nav { position: fixed; bottom: 0; left: 0; right: 0; background: var(--nav-bg); display: flex; justify-content: space-around; padding: 12px 0 20px; border-top: 1px solid var(--border); z-index: 50; }
        .nav-item { display: flex; flex-direction: column; align-items: center; gap: 4px; color: var(--text-sub); font-size: 11px; font-weight: 600; cursor: pointer; border: none; background: transparent; width: 25%; }
        .nav-item svg { width: 22px; height: 22px; fill: none; stroke: currentColor; stroke-width: 2; }
        .nav-item.active { color: var(--text); }

        /* SETTINGS */
        .settings-card { background: var(--card); border: 1px solid var(--border); border-radius: 16px; padding: 16px; margin-bottom: 16px; }
        .settings-title { font-size: 16px; font-weight: 700; margin-bottom: 12px; }
        .icon-grid { display: grid; grid-template-columns: repeat(4, 1fr); gap: 12px; }
        .icon-opt { aspect-ratio: 1; border-radius: 16px; border: 2px solid transparent; background: var(--border); cursor: pointer; overflow: hidden; }
        .icon-opt img { width: 100%; height: 100%; object-fit: cover; }
        .icon-opt.active { border-color: #2563EB; }
        
        .api-hidden { display: none; margin-top: 12px; }
        .api-hidden.show { display: block; }
        .version-tap { text-align: center; font-size: 12px; color: var(--text-sub); padding: 20px; cursor: pointer; }
    </style>
</head>
<body>

<header>
    <div class="header-top">
        <div>
            <div class="header-title">
                <svg width="28" height="28" viewBox="0 0 24 24" fill="var(--text)"><path d="M18 4l2 4h-3l-2-4h-2l2 4h-3l-2-4H8l2 4H7L5 4H4c-1.1 0-1.99.9-1.99 2L2 18c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V4h-4z"/></svg>
                Cinema Tracker
            </div>
            <div class="header-sub">Jadwal Bioskop Indonesia</div>
        </div>
    </div>
    <div class="main-tabs" id="main-tabs">
        <div class="main-tab active" onclick="switchMainTab('now', this)">Sedang Tayang</div>
        <div class="main-tab" onclick="switchMainTab('upcoming', this)">Akan Datang</div>
    </div>
</header>

<!-- VIEW: BIOSKOP -->
<div id="view-bioskop" class="view-container active">
    <div class="movie-grid" id="grid-bioskop"></div>
</div>

<!-- VIEW: CARI -->
<div id="view-cari" class="view-container">
    <div class="search-bar">
        <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="var(--text-sub)" stroke-width="2"><circle cx="11" cy="11" r="8"></circle><line x1="21" y1="21" x2="16.65" y2="16.65"></line></svg>
        <input type="text" id="search-input" placeholder="Cari judul film..." oninput="handleSearch()">
    </div>
    <div class="movie-grid" id="grid-cari"></div>
</div>

<!-- VIEW: WATCHLIST -->
<div id="view-watchlist" class="view-container">
    <div class="filter-chips" id="filter-watchlist">
        <div class="chip active" onclick="setFilter('semua', this)">Semua</div>
        <div class="chip" onclick="setFilter('segera', this)">Segera (≤14 Hari)</div>
        <div class="chip" onclick="setFilter('bulan', this)">Bulan Ini</div>
    </div>
    <div id="list-watchlist"></div>
</div>

<!-- VIEW: SETTINGS -->
<div id="view-settings" class="view-container">
    <div class="settings-card">
        <div class="settings-title">Ikon Aplikasi</div>
        <div class="icon-grid" id="icon-selector">
            <!-- Diisi JS -->
        </div>
    </div>
    <div class="settings-card">
        <div class="settings-title">Tema Aplikasi</div>
        <div style="display: flex; gap: 8px;">
            <button class="btn-action" style="flex:1;" onclick="setTheme('light')">Light</button>
            <button class="btn-action" style="flex:1;" onclick="setTheme('dark')">Dark</button>
            <button class="btn-action" style="flex:1;" onclick="setTheme('system')">System</button>
        </div>
    </div>
    
    <!-- HIDDEN API KEY -->
    <div class="version-tap" onclick="revealApi()">Cinema Tracker v5.0</div>
    <div class="settings-card api-hidden" id="api-box">
        <div class="settings-title">Pengaturan Developer</div>
        <div class="search-bar" style="margin-bottom: 0;">
            <input type="text" id="api-key-input" placeholder="TMDB API Key (v3)" onchange="saveApiKey(this.value)">
        </div>
    </div>
</div>

<!-- MODAL: DETAIL FILM -->
<div id="movie-modal" class="modal">
    <button class="btn-close" onclick="closeModal()">✕</button>
    <div class="modal-hero">
        <img id="modal-img" src="" alt="Poster">
    </div>
    <div class="modal-content">
        <h1 id="modal-title" class="modal-title"></h1>
        <div class="modal-meta" id="modal-meta"></div>

        <button id="btn-save-modal" class="btn-watchlist-large" onclick="toggleWatchlistFromModal()">
            <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M19 21l-7-5-7 5V5a2 2 0 0 1 2-2h10a2 2 0 0 1 2 2z"></path></svg>
            <span>Simpan ke Watchlist</span>
        </button>

        <div class="info-box">
            <div class="info-box-title">Jadwal Bioskop ID</div>
            <div class="info-box-main" id="modal-release-text"></div>
            <div class="info-box-sub">Tayang di XXI, CGV, Cinépolis & IMAX</div>
        </div>

        <div class="action-grid">
            <button class="btn-action" onclick="openTrailer()">
                <svg viewBox="0 0 24 24"><polygon points="5 3 19 12 5 21 5 3"></polygon></svg>
                Trailer
            </button>
            <button class="btn-action" onclick="openLetterboxd()">
                <svg viewBox="0 0 24 24"><circle cx="7" cy="12" r="3"></circle><circle cx="12" cy="12" r="3"></circle><circle cx="17" cy="12" r="3"></circle></svg>
                Letterboxd
            </button>
            <button class="btn-action" onclick="downloadPoster()">
                <svg viewBox="0 0 24 24"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"></path><polyline points="7 10 12 15 17 10"></polyline><line x1="12" y1="15" x2="12" y2="3"></line></svg>
                Poster
            </button>
        </div>

        <div class="info-box">
            <div class="info-box-title">Pengingat Rilis</div>
            <div class="notif-toggles">
                <div class="toggle-row">
                    <div><div class="toggle-label">Pre-Sale Tiket</div><div class="toggle-sub">Info saat tiket dibuka</div></div>
                    <label class="switch"><input type="checkbox" id="notif-presale" onchange="updateNotif('presale', this.checked)"><span class="slider"></span></label>
                </div>
                <div class="toggle-row">
                    <div><div class="toggle-label">Sehari Sebelumnya (H-1)</div><div class="toggle-sub">Pengingat besok mulai tayang</div></div>
                    <label class="switch"><input type="checkbox" id="notif-h1" onchange="updateNotif('h1', this.checked)"><span class="slider"></span></label>
                </div>
                <div class="toggle-row">
                    <div><div class="toggle-label">Hari Perdana (Hari-H)</div><div class="toggle-sub">Notifikasi tepat di hari rilis</div></div>
                    <label class="switch"><input type="checkbox" id="notif-h0" onchange="updateNotif('h0', this.checked)"><span class="slider"></span></label>
                </div>
            </div>
        </div>

        <p id="modal-synopsis" class="sinopsis"></p>
        
        <div class="credits-row">
            <div class="credits-label">Sutradara</div>
            <div class="credits-val" id="modal-director">-</div>
        </div>
        <div class="credits-row">
            <div class="credits-label">Pemain</div>
            <div class="credits-val" id="modal-cast">-</div>
        </div>
    </div>
</div>

<!-- BOTTOM NAV -->
<nav class="bottom-nav">
    <button class="nav-item active" onclick="switchTab('bioskop', this)">
        <svg viewBox="0 0 24 24"><rect x="3" y="3" width="7" height="7"></rect><rect x="14" y="3" width="7" height="7"></rect><rect x="14" y="14" width="7" height="7"></rect><rect x="3" y="14" width="7" height="7"></rect></svg>
        Bioskop
    </button>
    <button class="nav-item" onclick="switchTab('cari', this)">
        <svg viewBox="0 0 24 24"><circle cx="11" cy="11" r="8"></circle><line x1="21" y1="21" x2="16.65" y2="16.65"></line></svg>
        Cari
    </button>
    <button class="nav-item" onclick="switchTab('watchlist', this)">
        <svg viewBox="0 0 24 24"><path d="M19 21l-7-5-7 5V5a2 2 0 0 1 2-2h10a2 2 0 0 1 2 2z"></path></svg>
        Watchlist
    </button>
    <button class="nav-item" onclick="switchTab('settings', this)">
        <svg viewBox="0 0 24 24"><circle cx="12" cy="12" r="3"></circle><path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 0 1 0 2.83 2 2 0 0 1-2.83 0l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-2 2 2 2 0 0 1-2-2v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 0 1-2.83 0 2 2 0 0 1 0-2.83l.06-.06a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1H3a2 2 0 0 1-2-2 2 2 0 0 1 2-2h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 0 1 0-2.83 2 2 0 0 1 2.83 0l.06.06a1.65 1.65 0 0 0 1.82.33H9a1.65 1.65 0 0 0 1-1.51V3a2 2 0 0 1 2-2 2 2 0 0 1 2 2v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 0 1 2.83 0 2 2 0 0 1 0 2.83l-.06.06a1.65 1.65 0 0 0-.33 1.82V9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 0 1 2 2 2 2 0 0 1-2 2h-.09a1.65 1.65 0 0 0-1.51 1z"></path></svg>
        Settings
    </button>
</nav>

<script>
    const DEFAULT_API_KEY = ""; // Isi pake default API key lu biar user ga perlu setting
    let state = {
        theme: localStorage.getItem('ct_theme') || 'system',
        apiKey: localStorage.getItem('ct_api_key') || DEFAULT_API_KEY,
        watchlist: JSON.parse(localStorage.getItem('ct_watchlist') || '[]'),
        nowPlaying: [],
        upcoming: [],
        currentMovie: null,
        currentFilter: 'semua',
        mainTab: 'now',
        apiRevealed: false
    };

    const fallbackNow = [
        { id: 101, title: "Agak Laen", release_date: "2024-02-01", poster_path: "/k6EfH8p4.jpg", overview: "Komedi horor seru.", vote_average: 8.1, genre_ids: [35, 27] },
        { id: 102, title: "Siksa Kubur", release_date: "2024-04-11", poster_path: "/k6EfH8p4.jpg", overview: "Horor religi.", vote_average: 7.5, genre_ids: [27] }
    ];
    const fallbackUpcoming = [
        { id: 103, title: "Avengers: Doomsday", release_date: "2026-12-16", poster_path: "/avengers.jpg", overview: "Petualangan baru Earth's Mightiest Heroes.", vote_average: 8.5, genre_ids: [28, 12] },
        { id: 104, title: "Dune: Part Three", release_date: "2026-12-16", poster_path: "/dune.jpg", overview: "Lanjutan perjalanan Paul Atreides.", vote_average: 8.9, genre_ids: [878, 12] }
    ];

    document.addEventListener('DOMContentLoaded', () => {
        applyTheme();
        document.getElementById('api-key-input').value = state.apiKey;
        renderIcons();
        fetchData();
        renderWatchlist();
        syncToAndroid();
    });

    // --- CORE LOGIC ---
    function fetchData() {
        if (!state.apiKey) {
            state.nowPlaying = fallbackNow;
            state.upcoming = fallbackUpcoming;
            renderBioskop();
            return;
        }
        Promise.all([
            fetch(`https://api.themoviedb.org/3/movie/now_playing?api_key=${state.apiKey}&region=ID&language=en-US&page=1`).then(r => r.json()),
            fetch(`https://api.themoviedb.org/3/movie/upcoming?api_key=${state.apiKey}&region=ID&language=en-US&page=1`).then(r => r.json())
        ]).then(([now, up]) => {
            state.nowPlaying = (now.results || []).filter(m => m.release_date);
            state.upcoming = (up.results || []).filter(m => m.release_date);
            renderBioskop();
            syncToAndroid();
        }).catch(() => {
            state.nowPlaying = fallbackNow;
            state.upcoming = fallbackUpcoming;
            renderBioskop();
        });
    }

    function renderBioskop() {
        const grid = document.getElementById('grid-bioskop');
        const data = state.mainTab === 'now' ? state.nowPlaying : state.upcoming;
        grid.innerHTML = data.map(m => createCardHTML(m)).join('');
    }

    function switchMainTab(tab, el) {
        state.mainTab = tab;
        document.querySelectorAll('.main-tab').forEach(t => t.classList.remove('active'));
        el.classList.add('active');
        renderBioskop();
    }

    function handleSearch() {
        const q = document.getElementById('search-input').value.toLowerCase();
        const grid = document.getElementById('grid-cari');
        if (!q) { grid.innerHTML = ''; return; }
        
        const allMovies = [...state.nowPlaying, ...state.upcoming];
        const filtered = allMovies.filter(m => m.title.toLowerCase().includes(q));
        grid.innerHTML = filtered.map(m => createCardHTML(m)).join('');
    }

    function createCardHTML(m) {
        const isSaved = state.watchlist.some(w => w.id === m.id);
        const poster = m.poster_path ? `https://image.tmdb.org/t/p/w342${m.poster_path}` : 'https://via.placeholder.com/342x513?text=No+Poster';
        const dateStr = formatDate(m.release_date);
        const days = getDaysLeft(m.release_date);
        const isImax = m.genre_ids && m.genre_ids.includes(878); // Mockup IMAX badge based on Sci-Fi
        
        return `
        <div class="card" onclick="openDetail(${m.id})">
            <div class="card-img-wrapper">
                ${isImax ? '<span class="badge-imax">IMAX</span>' : ''}
                <button class="btn-bookmark ${isSaved ? 'active' : ''}" onclick="event.stopPropagation(); toggleWatchlist(${m.id}, this)">
                    <svg viewBox="0 0 24 24"><path d="M19 21l-7-5-7 5V5a2 2 0 0 1 2-2h10a2 2 0 0 1 2 2z"></path></svg>
                </button>
                <img src="${poster}" class="card-img" loading="lazy" alt="${m.title}">
            </div>
            <div class="card-info">
                <div class="card-genre">${getGenre(m.genre_ids)}</div>
                <div class="card-title">${m.title}</div>
                <div class="card-bottom">
                    <span class="card-date">${dateStr}</span>
                    <span class="badge-hype">${days > 0 ? 'H-' + days : (days === 0 ? 'Hari Ini' : 'Tayang')}</span>
                </div>
            </div>
        </div>
        `;
    }

    // --- DETAIL MODAL ---
    function openDetail(id) {
        const allMovies = [...state.nowPlaying, ...state.upcoming, ...state.watchlist];
        let m = allMovies.find(x => x.id === id);
        if (!m) return;
        
        state.currentMovie = m;
        document.getElementById('modal-img').src = m.poster_path ? `https://image.tmdb.org/t/p/w780${m.poster_path}` : 'https://via.placeholder.com/780x1170?text=No+Poster';
        document.getElementById('modal-title').textContent = m.title;
        document.getElementById('modal-meta').innerHTML = `
            <span>⭐ ${m.vote_average ? m.vote_average.toFixed(1) : '-'}</span> • 
            <span>${getGenre(m.genre_ids)}</span>
        `;
        
        const days = getDaysLeft(m.release_date);
        document.getElementById('modal-release-text').innerHTML = `
            Mulai tayang ${formatDate(m.release_date)} 
            <span style="color:var(--accent); margin-left:6px;">(${days > 0 ? 'H-' + days : 'Tayang'})</span>
        `;
        
        document.getElementById('modal-synopsis').textContent = m.overview || 'Sinopsis belum tersedia dalam bahasa Indonesia.';
        
        // Load Credits & Trailer (Fetch Detail)
        document.getElementById('modal-director').textContent = 'Memuat...';
        document.getElementById('modal-cast').textContent = 'Memuat...';
        fetch(`https://api.themoviedb.org/3/movie/${m.id}?api_key=${state.apiKey}&append_to_response=credits,videos&language=en-US`)
            .then(r => r.json())
            .then(data => {
                const dir = data.credits?.crew?.find(c => c.job === 'Director');
                document.getElementById('modal-director').textContent = dir ? dir.name : '-';
                const cast = data.credits?.cast?.slice(0, 3).map(c => c.name).join(', ');
                document.getElementById('modal-cast').textContent = cast || '-';
                
                const trailer = data.videos?.results?.find(v => v.type === 'Trailer' && v.site === 'YouTube');
                state.currentMovie.youtubeKey = trailer ? trailer.key : '';
            }).catch(() => {});

        // Set Toggles
        document.getElementById('notif-presale').checked = m.notify_presale !== false;
        document.getElementById('notif-h1').checked = m.notify_h1 !== false;
        document.getElementById('notif-h0').checked = m.notify_h0 !== false;

        updateModalSaveBtn();
        document.getElementById('movie-modal').classList.add('active');
    }

    function closeModal() {
        document.getElementById('movie-modal').classList.remove('active');
        state.currentMovie = null;
    }

    function updateModalSaveBtn() {
        const btn = document.getElementById('btn-save-modal');
        const isSaved = state.watchlist.some(w => w.id === state.currentMovie.id);
        if (isSaved) {
            btn.classList.add('active');
            btn.innerHTML = `<svg width="20" height="20" viewBox="0 0 24 24" fill="currentColor" stroke="currentColor" stroke-width="2"><path d="M19 21l-7-5-7 5V5a2 2 0 0 1 2-2h10a2 2 0 0 1 2 2z"></path></svg> Tersimpan di Watchlist`;
        } else {
            btn.classList.remove('active');
            btn.innerHTML = `<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M19 21l-7-5-7 5V5a2 2 0 0 1 2-2h10a2 2 0 0 1 2 2z"></path></svg> Simpan ke Watchlist`;
        }
    }

    function updateNotif(type, val) {
        if(!state.currentMovie) return;
        state.currentMovie[`notify_${type}`] = val;
        const idx = state.watchlist.findIndex(w => w.id === state.currentMovie.id);
        if(idx > -1) {
            state.watchlist[idx][`notify_${type}`] = val;
            saveWatchlistState();
        }
    }

    // --- WATCHLIST LOGIC ---
    function toggleWatchlist(id, btnEl = null) {
        const allMovies = [...state.nowPlaying, ...state.upcoming];
        const movie = allMovies.find(m => m.id === id) || state.watchlist.find(w => w.id === id);
        if (!movie) return;

        const index = state.watchlist.findIndex(w => w.id === id);
        if (index > -1) {
            state.watchlist.splice(index, 1);
        } else {
            state.watchlist.push(movie);
        }
        saveWatchlistState();

        if (btnEl) btnEl.classList.toggle('active');
        renderBioskop();
        if (document.getElementById('search-input').value) handleSearch();
        renderWatchlist();
        if (state.currentMovie && state.currentMovie.id === id) updateModalSaveBtn();
    }

    function saveWatchlistState() {
        localStorage.setItem('ct_watchlist', JSON.stringify(state.watchlist));
        syncToAndroid();
    }

    function toggleWatchlistFromModal() {
        if (state.currentMovie) toggleWatchlist(state.currentMovie.id);
    }

    function renderWatchlist() {
        const container = document.getElementById('list-watchlist');
        if (state.watchlist.length === 0) {
            container.innerHTML = `<div style="text-align:center; padding: 40px 20px; color: var(--text-sub);">
                <svg width="48" height="48" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" style="margin-bottom:12px; opacity:0.5;"><path d="M19 21l-7-5-7 5V5a2 2 0 0 1 2-2h10a2 2 0 0 1 2 2z"></path></svg>
                <p>Belum ada film di watchlist.<br>Simpan film favoritmu di sini.</p>
            </div>`;
            return;
        }

        let filtered = [...state.watchlist];
        if (state.currentFilter === 'segera') {
            filtered = filtered.filter(m => { const d = getDaysLeft(m.release_date); return d >= 0 && d <= 14; });
        } else if (state.currentFilter === 'bulan') {
            const now = new Date();
            filtered = filtered.filter(m => { const rDate = new Date(m.release_date); return rDate.getMonth() === now.getMonth() && rDate.getFullYear() === now.getFullYear(); });
        }

        filtered.sort((a, b) => new Date(a.release_date) - new Date(b.release_date));

        container.innerHTML = filtered.map(m => {
            const poster = m.poster_path ? `https://image.tmdb.org/t/p/w185${m.poster_path}` : 'https://via.placeholder.com/185x278?text=No+Poster';
            const dateStr = formatDate(m.release_date);
            const days = getDaysLeft(m.release_date);
            const countdownText = days > 0 ? `H-${days}` : (days === 0 ? 'Hari Ini' : 'Sudah Tayang');
            const countdownClass = days <= 0 ? 'passed' : '';

            return `
            <div class="watchlist-item" onclick="openDetail(${m.id})">
                <img src="${poster}" class="wl-img" loading="lazy" alt="${m.title}">
                <div class="wl-info">
                    <div class="wl-title">${m.title}</div>
                    <div class="wl-meta">Rilis: ${dateStr}</div>
                    <div class="wl-countdown ${countdownClass}">
                        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="10"></circle><polyline points="12 6 12 12 16 14"></polyline></svg>
                        ${countdownText}
                    </div>
                </div>
            </div>
            `;
        }).join('');
    }

    function setFilter(filter, el) {
        state.currentFilter = filter;
        document.querySelectorAll('#filter-watchlist .chip').forEach(c => c.classList.remove('active'));
        el.classList.add('active');
        renderWatchlist();
    }

    // --- ACTIONS ---
    function openTrailer() {
        if (window.AndroidBridge && state.currentMovie) {
            window.AndroidBridge.openTrailerDirect(state.currentMovie.youtubeKey || "", state.currentMovie.title);
        }
    }

    function openLetterboxd() {
        if (window.AndroidBridge && state.currentMovie) {
            window.AndroidBridge.openLetterboxdDirect(state.currentMovie.id, "");
        }
    }

    function downloadPoster() {
        if (!state.currentMovie) return;
        const url = state.currentMovie.poster_path ? `https://image.tmdb.org/t/p/original${state.currentMovie.poster_path}` : '';
        if (!url) return alert("Poster tidak tersedia");
        
        fetch(url).then(res => res.blob()).then(blob => {
            const reader = new FileReader();
            reader.onloadend = () => {
                if (window.AndroidBridge) {
                    window.AndroidBridge.savePosterBase64(reader.result, state.currentMovie.title.replace(/[^a-z0-9]/gi, '_'));
                }
            };
            reader.readAsDataURL(blob);
        });
    }

    // --- SETTINGS & THEME ---
    function setTheme(theme) {
        state.theme = theme;
        localStorage.setItem('ct_theme', theme);
        applyTheme();
        if (window.AndroidBridge) window.AndroidBridge.syncTheme(theme === 'dark' ? '#0F172A' : '#F8FAFC');
    }
    function applyTheme() {
        const isDark = state.theme === 'dark' || (state.theme === 'system' && window.matchMedia('(prefers-color-scheme: dark)').matches);
        document.documentElement.setAttribute('data-theme', isDark ? 'dark' : 'light');
    }
    function revealApi() {
        state.apiRevealed = !state.apiRevealed;
        document.getElementById('api-box').classList.toggle('show');
    }
    function saveApiKey(key) {
        state.apiKey = key;
        localStorage.setItem('ct_api_key', key);
        fetchData();
        syncToAndroid();
    }
    function renderIcons() {
        const icons = [
            { id: 'cinema_blue', name: 'Cinema' }, { id: 'kino_trio', name: 'Kino' },
            { id: 'arrakis_spice', name: 'Arrakis' }, { id: 'monolith_2001', name: 'Monolith' },
            { id: 'grand_budapest', name: 'Budapest' }, { id: 'neon_2049', name: 'Neon' },
            { id: 'imax_horizon', name: 'IMAX' }, { id: 'kodak_35mm', name: 'Kodak' },
            { id: 'trinity_ember', name: 'Trinity' }, { id: 'emerald_cut', name: 'Emerald' }
        ];
        const container = document.getElementById('icon-selector');
        container.innerHTML = icons.map((ic, i) => `
            <div class="icon-opt ${i===0?'active':''}" onclick="changeIcon('${ic.id}', this)">
                <img src="https://ui-avatars.com/api/?name=${ic.name}&background=random&color=fff&rounded=true" alt="${ic.name}">
            </div>
        `).join('');
    }
    function changeIcon(id, el) {
        document.querySelectorAll('.icon-opt').forEach(c => c.classList.remove('active'));
        el.classList.add('active');
        if(window.AndroidBridge) window.AndroidBridge.switchLauncherIcon(id);
    }

    // --- HELPERS ---
    function syncToAndroid() {
        if (window.AndroidBridge) {
            const apiKey = state.apiKey || "";
            const watchlistStr = JSON.stringify(state.watchlist);
            const tbaStr = JSON.stringify([...state.upcoming.filter(m => !m.release_date)]);
            window.AndroidBridge.syncRadarState(watchlistStr, tbaStr, apiKey);
        }
    }
    function formatDate(dateStr) {
        if (!dateStr) return "TBA";
        return new Date(dateStr).toLocaleDateString('id-ID', { day: 'numeric', month: 'short', year: 'numeric' });
    }
    function getDaysLeft(dateStr) {
        if (!dateStr) return 999;
        const today = new Date(); today.setHours(0,0,0,0);
        return Math.ceil((new Date(dateStr) - today) / (1000 * 60 * 60 * 24));
    }
    function getGenre(ids) {
        if (!ids) return 'Film';
        const map = { 28: 'Action', 12: 'Adventure', 16: 'Animation', 35: 'Comedy', 80: 'Crime', 99: 'Documentary', 18: 'Drama', 10751: 'Family', 14: 'Fantasy', 36: 'History', 27: 'Horror', 10402: 'Music', 9648: 'Mystery', 10749: 'Romance', 878: 'Sci-Fi', 10770: 'TV Movie', 53: 'Thriller', 10752: 'War', 37: 'Western' };
        return ids.slice(0, 2).map(id => map[id]).filter(Boolean).join(', ') || 'Film';
    }
    function switchTab(tabId, el) {
        document.querySelectorAll('.view-container').forEach(c => c.classList.remove('active'));
        document.querySelectorAll('.nav-item').forEach(n => n.classList.remove('active'));
        document.getElementById(`view-${tabId}`).classList.add('active');
        el.classList.add('active');
    }
</script>
</body>
</html>
HTMLEOF

echo "Update Selesai! Silakan jalankan git commit dan push."
