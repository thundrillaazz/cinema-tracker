package com.michael.cinematracker

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private val executor = Executors.newSingleThreadExecutor()

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        createNotificationChannel()
        requestNotificationPermissionIfNeeded()
        scheduleBackgroundWorker()

        webView = WebView(this).apply {
            setBackgroundColor(Color.parseColor("#F8FAFC"))
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.loadsImagesAutomatically = true
            settings.cacheMode = WebSettings.LOAD_DEFAULT
            settings.allowFileAccess = true
            webViewClient = WebViewClient()
            webChromeClient = WebChromeClient()
            addJavascriptInterface(AndroidBridge(this@MainActivity), "AndroidBridge")
            loadUrl("file:///android_asset/index.html")
        }

        setContentView(webView)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                webView.evaluateJavascript("window.handleAndroidBack && window.handleAndroidBack()") { result ->
                    if (result != "true") {
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

    override fun onResume() {
        super.onResume()
        if (::webView.isInitialized) {
            webView.evaluateJavascript("window.onAppResume && window.onAppResume()", null)
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Jadwal Bioskop Indonesia",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifikasi rilis film teater, watchlist, dan presale XXI, CGV, Cinépolis"
                enableLights(true)
                lightColor = Color.BLUE
                enableVibration(true)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun scheduleBackgroundWorker() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val intervalHours = prefs.getLong("sync_interval_hours", 6L)

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val periodicWork = PeriodicWorkRequestBuilder<CinemaCheckWorker>(intervalHours, TimeUnit.HOURS)
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "CinemaIndonesiaHypeScanner",
            ExistingPeriodicWorkPolicy.UPDATE,
            periodicWork
        )
    }

    private fun showJsToast(message: String) {
        val escaped = message.replace("'", "\'")
        runOnUiThread {
            if (::webView.isInitialized) {
                webView.evaluateJavascript("window.showToast && window.showToast('$escaped')", null)
            }
        }
    }

    inner class AndroidBridge(private val context: Context) {

        @JavascriptInterface
        fun setSystemBarTheme(isDark: Boolean) {
            runOnUiThread {
                val bgColor = if (isDark) Color.parseColor("#0B0F19") else Color.parseColor("#FFFFFF")
                val pageBg = if (isDark) Color.parseColor("#0B0F19") else Color.parseColor("#F8FAFC")
                window.statusBarColor = bgColor
                window.navigationBarColor = bgColor
                if (::webView.isInitialized) {
                    webView.setBackgroundColor(pageBg)
                }
                val controller = WindowInsetsControllerCompat(window, window.decorView)
                controller.isAppearanceLightStatusBars = !isDark
                controller.isAppearanceLightNavigationBars = !isDark
            }
        }

        @JavascriptInterface
        fun saveWatchlistJson(jsonStr: String) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putString("watchlist_full_json", jsonStr).apply()
        }

        @JavascriptInterface
        fun getWatchlistJson(): String {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getString("watchlist_full_json", "") ?: ""
        }

        @JavascriptInterface
        fun saveCustomPostersJson(jsonStr: String) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putString("custom_posters_json", jsonStr).apply()
        }

        @JavascriptInterface
        fun getCustomPostersJson(): String {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getString("custom_posters_json", "") ?: ""
        }

        @JavascriptInterface
        fun saveSettings(apiKey: String, minHype: Int, intervalHours: Int, tbaWatchIdsJson: String) {
            val cleanKey = apiKey.trim()
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit()
                .putString("tmdb_api_key", cleanKey)
                .putInt("min_hype_threshold", minHype)
                .putLong("sync_interval_hours", intervalHours.toLong())
                .putString("tba_watch_list_json", tbaWatchIdsJson)
                .apply()

            scheduleBackgroundWorker()

            val immediateWork = OneTimeWorkRequestBuilder<CinemaCheckWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                "CinemaImmediateCheck",
                ExistingWorkPolicy.REPLACE,
                immediateWork
            )
        }

        @JavascriptInterface
        fun runWatchlistReminderCheckNow() {
            val immediateWork = OneTimeWorkRequestBuilder<CinemaCheckWorker>().build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                "CinemaManualReminderCheck",
                ExistingWorkPolicy.REPLACE,
                immediateWork
            )
            showJsToast("Memeriksa pengingat Watchlist...")
        }

        @JavascriptInterface
        fun getTmdbApiKey(): String {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getString("tmdb_api_key", "") ?: ""
        }

        @JavascriptInterface
        fun changeLauncherIcon(aliasName: String) {
            val aliases = listOf(
                "com.michael.cinematracker.LauncherDefault",
                "com.michael.cinematracker.LauncherSciFi",
                "com.michael.cinematracker.LauncherAction",
                "com.michael.cinematracker.LauncherImax"
            )
            val targetAlias = "com.michael.cinematracker.$aliasName"
            val pm = context.packageManager

            for (alias in aliases) {
                val state = if (alias == targetAlias) {
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                } else {
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                }
                pm.setComponentEnabledSetting(
                    ComponentName(context, alias),
                    state,
                    PackageManager.DONT_KILL_APP
                )
            }
            showJsToast("Ikon aplikasi diperbarui")
        }

        @JavascriptInterface
        fun downloadPosterImage(posterUrl: String, movieTitle: String) {
            if (posterUrl.isEmpty()) {
                showJsToast("Poster belum tersedia")
                return
            }
            showJsToast("Mengunduh poster HD...")
            executor.execute {
                try {
                    val hdUrl = posterUrl.replace("/w500/", "/original/")
                    val url = URL(hdUrl)
                    val connection = url.openConnection() as HttpURLConnection
                    connection.doInput = true
                    connection.connectTimeout = 8000
                    connection.readTimeout = 8000
                    connection.connect()
                    val bytes = connection.inputStream.readBytes()

                    val safeName = movieTitle.replace(Regex("[^a-zA-Z0-9_-]"), "_").take(35)
                    val fileName = "CinemaTracker_${safeName}_${System.currentTimeMillis()}.jpg"

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        val values = ContentValues().apply {
                            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/CinemaTracker")
                            put(MediaStore.Images.Media.IS_PENDING, 1)
                        }
                        val resolver = context.contentResolver
                        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                        if (uri != null) {
                            resolver.openOutputStream(uri)?.use { out -> out.write(bytes) }
                            values.clear()
                            values.put(MediaStore.Images.Media.IS_PENDING, 0)
                            resolver.update(uri, values, null, null)
                            showJsToast("Poster tersimpan di Galeri")
                        } else {
                            showJsToast("Gagal menyimpan poster")
                        }
                    } else {
                        val dir = File(
                            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                            "CinemaTracker"
                        )
                        if (!dir.exists()) dir.mkdirs()
                        val file = File(dir, fileName)
                        FileOutputStream(file).use { out -> out.write(bytes) }
                        val scanIntent = Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, Uri.fromFile(file))
                        context.sendBroadcast(scanIntent)
                        showJsToast("Poster tersimpan di Galeri")
                    }
                } catch (e: Exception) {
                    showJsToast("Gagal mengunduh poster")
                }
            }
        }

        @JavascriptInterface
        fun shareMovieText(text: String) {
            try {
                val sendIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, text)
                }
                val shareIntent = Intent.createChooser(sendIntent, "Bagikan Info Film")
                context.startActivity(shareIntent)
            } catch (_: Exception) {}
        }

        @JavascriptInterface
        fun sendCustomNotification(title: String, body: String, posterUrl: String) {
            executor.execute {
                var bitmap: Bitmap? = null
                try {
                    if (posterUrl.isNotEmpty()) {
                        val url = URL(posterUrl.replace("/w500/", "/w342/"))
                        val connection = url.openConnection() as HttpURLConnection
                        connection.doInput = true
                        connection.connectTimeout = 5000
                        connection.readTimeout = 5000
                        connection.connect()
                        bitmap = BitmapFactory.decodeStream(connection.inputStream)
                    }
                } catch (_: Exception) {}

                val intent = Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
                val notifId = (title.hashCode() and 0x7fffffff)
                val pendingIntent = PendingIntent.getActivity(
                    context,
                    notifId,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )

                val builder = NotificationCompat.Builder(context, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle(title)
                    .setContentText(body)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setAutoCancel(true)
                    .setContentIntent(pendingIntent)

                if (bitmap != null) {
                    builder.setLargeIcon(bitmap)
                }

                val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                manager.notify(notifId, builder.build())
            }
        }

        @JavascriptInterface
        fun openExternalUrl(url: String) {
            try {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                context.startActivity(intent)
            } catch (_: Exception) {}
        }

        @JavascriptInterface
        fun addCalendarEvent(title: String, description: String, location: String, startMillis: Long) {
            try {
                val intent = Intent(Intent.ACTION_EDIT).apply {
                    type = "vnd.android.cursor.item/event"
                    putExtra("beginTime", startMillis)
                    putExtra("endTime", startMillis + 7200000L)
                    putExtra("allDay", true)
                    putExtra("title", title)
                    putExtra("description", description)
                    putExtra("eventLocation", location)
                }
                context.startActivity(intent)
            } catch (e: Exception) {
                val url = "https://calendar.google.com/calendar/render?action=TEMPLATE&text=" +
                    Uri.encode(title) + "&details=" + Uri.encode(description) +
                    "&location=" + Uri.encode(location)
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            }
        }
    }

    companion object {
        const val CHANNEL_ID = "cinema_hype_alerts"
        const val PREFS_NAME = "cinema_tracker_prefs"
    }
}
