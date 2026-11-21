package com.michael.cinematracker

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ComponentName
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.CalendarContract
import android.provider.MediaStore
import android.view.View
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
import androidx.core.view.WindowInsetsControllerCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.net.HttpURLConnection
import java.net.URL
import java.util.Calendar
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private val executor = Executors.newSingleThreadExecutor()

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { _ -> }

    companion object {
        const val PREFS_NAME = "CinemaTrackerPrefs"
        const val CHANNEL_ID = "cinema_hype_alerts"
        const val WORK_NAME = "CinemaHypeBackgroundWork"
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val savedTheme = prefs.getString("app_theme", "light") ?: "light"
        applySystemBarsTheme(savedTheme)

        createNotificationChannel()
        requestNotificationPermissionIfNeeded()

        webView = WebView(this).apply {
            layoutParams = android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(if (isDarkModeActive(savedTheme)) Color.parseColor("#09090b") else Color.parseColor("#f8fafc"))
        }
        setContentView(webView)

        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.cacheMode = WebSettings.LOAD_DEFAULT
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        settings.allowFileAccess = true
        settings.allowContentAccess = true
        settings.mediaPlaybackRequiresUserGesture = false
        settings.setSupportZoom(false)
        settings.builtInZoomControls = false
        settings.displayZoomControls = false

        webView.addJavascriptInterface(AndroidBridge(this), "AndroidBridge")

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val uri = request?.url ?: return false
                val scheme = uri.scheme?.lowercase() ?: ""
                if (scheme == "http" || scheme == "https") {
                    val host = uri.host?.lowercase() ?: ""
                    if (host.contains("themoviedb.org") || host.contains("image.tmdb.org")) {
                        return false
                    }
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, uri))
                    } catch (_: Exception) {
                    }
                    return true
                }
                return false
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                val currentTheme = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .getString("app_theme", "light") ?: "light"
                applySystemBarsTheme(currentTheme)
            }
        }

        webView.webChromeClient = WebChromeClient()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (::webView.isInitialized) {
                    webView.evaluateJavascript("window.handleAndroidBack && window.handleAndroidBack()") { result ->
                        if (result != "true") {
                            isEnabled = false
                            onBackPressedDispatcher.onBackPressed()
                        }
                    }
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        webView.loadUrl("file:///android_asset/index.html")

        val savedInterval = prefs.getLong("check_interval_hours", 6L)
        scheduleBackgroundWorker(savedInterval)
    }

    override fun onResume() {
        super.onResume()
        if (::webView.isInitialized) {
            webView.evaluateJavascript("window.onNativeResume && window.onNativeResume()", null)
        }
    }

    override fun onDestroy() {
        executor.shutdown()
        if (::webView.isInitialized) {
            webView.removeJavascriptInterface("AndroidBridge")
            webView.destroy()
        }
        super.onDestroy()
    }

    private fun isDarkModeActive(mode: String): Boolean {
        return when (mode) {
            "dark" -> true
            "system" -> {
                val nightMask = resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK
                nightMask == android.content.res.Configuration.UI_MODE_NIGHT_YES
            }
            else -> false
        }
    }

    fun applySystemBarsTheme(mode: String) {
        runOnUiThread {
            val dark = isDarkModeActive(mode)
            val barColor = if (dark) Color.parseColor("#09090b") else Color.parseColor("#ffffff")
            val bgColor = if (dark) Color.parseColor("#09090b") else Color.parseColor("#f8fafc")
            WindowCompat.setDecorFitsSystemWindows(window, true)
            window.statusBarColor = barColor
            window.navigationBarColor = barColor
            if (::webView.isInitialized) {
                webView.setBackgroundColor(bgColor)
            }
            val controller = WindowInsetsControllerCompat(window, window.decorView)
            controller.isAppearanceLightStatusBars = !dark
            controller.isAppearanceLightNavigationBars = !dark
            window.decorView.systemUiVisibility = if (dark) {
                window.decorView.systemUiVisibility and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
            } else {
                window.decorView.systemUiVisibility or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Cinema Tracker Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifikasi jadwal tayang bioskop Indonesia & pengingat watchlist"
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
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

    fun scheduleBackgroundWorker(intervalHours: Long) {
        val safeHours = intervalHours.coerceIn(3L, 24L)
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val request = PeriodicWorkRequestBuilder<CinemaCheckWorker>(safeHours, TimeUnit.HOURS)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
            .build()

        WorkManager.getInstance(applicationContext).enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }

    inner class AndroidBridge(private val context: Context) {

        @JavascriptInterface
        fun saveApiKey(key: String) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString("tmdb_api_key", key.trim())
                .apply()
        }

        @JavascriptInterface
        fun getSavedApiKey(): String {
            return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString("tmdb_api_key", "") ?: ""
        }

        @JavascriptInterface
        fun saveWorkerSettings(minHype: Int, intervalHours: Int, tbaIdsJson: String) {
            val safeInterval = intervalHours.toLong().coerceIn(3L, 24L)
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putInt("min_hype_threshold", minHype.coerceIn(50, 99))
                .putLong("check_interval_hours", safeInterval)
                .putString("tba_watch_list_json", tbaIdsJson)
                .apply()
            scheduleBackgroundWorker(safeInterval)
        }

        @JavascriptInterface
        fun syncWatchlistReminders(watchlistFullJson: String, tbaIdsJson: String) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString("watchlist_full_json", watchlistFullJson)
                .putString("tba_watch_list_json", tbaIdsJson)
                .apply()
        }

        @JavascriptInterface
        fun getWatchlistFullJson(): String {
            return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString("watchlist_full_json", "{}") ?: "{}"
        }

        @JavascriptInterface
        fun syncCustomPosters(customPostersJson: String) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString("custom_posters_json", customPostersJson)
                .apply()
        }

        @JavascriptInterface
        fun getCustomPostersJson(): String {
            return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString("custom_posters_json", "{}") ?: "{}"
        }

        @JavascriptInterface
        fun saveThemeMode(mode: String) {
            val clean = mode.lowercase()
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString("app_theme", clean)
                .apply()
            applySystemBarsTheme(clean)
        }

        @JavascriptInterface
        fun changeLauncherIcon(aliasName: String) {
            val aliases = listOf(
                "com.michael.cinematracker.LauncherDefault",
                "com.michael.cinematracker.LauncherSciFi",
                "com.michael.cinematracker.LauncherAction",
                "com.michael.cinematracker.LauncherImax",
                "com.michael.cinematracker.LauncherNoir",
                "com.michael.cinematracker.LauncherSolaris",
                "com.michael.cinematracker.LauncherReel35"
            )
            val targetClass = when (aliasName) {
                "LauncherSciFi" -> "com.michael.cinematracker.LauncherSciFi"
                "LauncherAction" -> "com.michael.cinematracker.LauncherAction"
                "LauncherImax" -> "com.michael.cinematracker.LauncherImax"
                "LauncherNoir" -> "com.michael.cinematracker.LauncherNoir"
                "LauncherSolaris" -> "com.michael.cinematracker.LauncherSolaris"
                "LauncherReel35" -> "com.michael.cinematracker.LauncherReel35"
                else -> "com.michael.cinematracker.LauncherDefault"
            }
            runOnUiThread {
                try {
                    val pm = context.packageManager
                    for (cls in aliases) {
                        val state = if (cls == targetClass) {
                            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                        } else {
                            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                        }
                        pm.setComponentEnabledSetting(
                            ComponentName(context.packageName, cls),
                            state,
                            PackageManager.DONT_KILL_APP
                        )
                    }
                    Toast.makeText(context, "Ikon aplikasi diperbarui", Toast.LENGTH_SHORT).show()
                } catch (_: Exception) {
                }
            }
        }

        @JavascriptInterface
        fun runWorkerNow() {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val oneTime = androidx.work.OneTimeWorkRequestBuilder<CinemaCheckWorker>()
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(context.applicationContext).enqueue(oneTime)
        }

        @JavascriptInterface
        fun addCalendarEvent(title: String, dateIso: String, description: String) {
            runOnUiThread {
                try {
                    val parts = dateIso.split("-")
                    if (parts.size < 3) return@runOnUiThread
                    val y = parts[0].toInt()
                    val m = parts[1].toInt() - 1
                    val d = parts[2].toInt()

                    val startCal = Calendar.getInstance().apply {
                        set(y, m, d, 10, 0, 0)
                        set(Calendar.MILLISECOND, 0)
                    }
                    val endCal = Calendar.getInstance().apply {
                        set(y, m, d, 12, 0, 0)
                        set(Calendar.MILLISECOND, 0)
                    }

                    val intent = Intent(Intent.ACTION_EDIT).apply {
                        type = "vnd.android.cursor.item/event"
                        putExtra(CalendarContract.Events.TITLE, title)
                        putExtra(CalendarContract.Events.DESCRIPTION, description)
                        putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, startCal.timeInMillis)
                        putExtra(CalendarContract.EXTRA_EVENT_END_TIME, endCal.timeInMillis)
                    }
                    startActivity(intent)
                } catch (_: Exception) {
                }
            }
        }

        @JavascriptInterface
        fun shareText(text: String) {
            runOnUiThread {
                try {
                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, text)
                    }
                    startActivity(Intent.createChooser(shareIntent, "Bagikan jadwal film"))
                } catch (_: Exception) {
                }
            }
        }

        @JavascriptInterface
        fun savePosterToGallery(posterUrl: String, fileNamePrefix: String) {
            executor.execute {
                var conn: HttpURLConnection? = null
                try {
                    if (posterUrl.isEmpty()) return@execute
                    val url = URL(posterUrl)
                    conn = (url.openConnection() as HttpURLConnection).apply {
                        doInput = true
                        connectTimeout = 8000
                        readTimeout = 8000
                    }
                    conn.connect()
                    val bmp = BitmapFactory.decodeStream(conn.inputStream) ?: return@execute

                    val cleanName = fileNamePrefix.replace(Regex("[^a-zA-Z0-9_-]"), "_").take(32)
                    val displayName = "${cleanName}_${System.currentTimeMillis()}.jpg"

                    val resolver = context.contentResolver
                    val contentValues = ContentValues().apply {
                        put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
                        put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/CinemaTracker")
                            put(MediaStore.Images.Media.IS_PENDING, 1)
                        }
                    }

                    val uri: Uri? = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
                    if (uri != null) {
                        resolver.openOutputStream(uri)?.use { out ->
                            bmp.compress(Bitmap.CompressFormat.JPEG, 95, out)
                        }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            contentValues.clear()
                            contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
                            resolver.update(uri, contentValues, null, null)
                        }
                        runOnUiThread {
                            Toast.makeText(context, "Poster tersimpan ke Galeri", Toast.LENGTH_SHORT).show()
                        }
                    }
                } catch (_: Exception) {
                } finally {
                    conn?.disconnect()
                }
            }
        }

        @JavascriptInterface
        fun sendCustomNotification(title: String, body: String, posterUrl: String) {
            executor.execute {
                var rawBitmap: Bitmap? = null
                var bannerBitmap: Bitmap? = null
                var connection: HttpURLConnection? = null
                try {
                    if (posterUrl.isNotEmpty()) {
                        val cleanPosterUrl = posterUrl.replace("/w500/", "/w342/")
                        val url = URL(cleanPosterUrl)
                        connection = (url.openConnection() as HttpURLConnection).apply {
                            doInput = true
                            connectTimeout = 5000
                            readTimeout = 5000
                        }
                        connection.connect()
                        rawBitmap = BitmapFactory.decodeStream(connection.inputStream)
                        if (rawBitmap != null) {
                            val w = 640
                            val h = 320
                            val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                            val canvas = Canvas(out)
                            val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                                shader = LinearGradient(
                                    0f, 0f, w.toFloat(), h.toFloat(),
                                    0xFF09090B.toInt(), 0xFF1F2430.toInt(),
                                    Shader.TileMode.CLAMP
                                )
                            }
                            canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), bgPaint)
                            val pH = 280f
                            val pW = pH * (2f / 3f)
                            val left = (w - pW) / 2f
                            val top = (h - pH) / 2f
                            val rect = RectF(left, top, left + pW, top + pH)
                            val clipPath = Path().apply {
                                addRoundRect(rect, 16f, 16f, Path.Direction.CW)
                            }
                            canvas.save()
                            canvas.clipPath(clipPath)
                            canvas.drawBitmap(rawBitmap, null, rect, Paint(Paint.FILTER_BITMAP_FLAG))
                            canvas.restore()
                            val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                                color = 0x33FFFFFF
                                style = Paint.Style.STROKE
                                strokeWidth = 2f
                            }
                            canvas.drawRoundRect(rect, 16f, 16f, borderPaint)
                            bannerBitmap = out
                        }
                    }
                } catch (_: Exception) {
                } finally {
                    connection?.disconnect()
                }

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
                    .setColor(0xFF18181B.toInt())
                    .setSubText("Jadwal Bioskop ID")
                    .setContentTitle(title)
                    .setContentText(body)
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setAutoCancel(true)
                    .setContentIntent(pendingIntent)

                if (rawBitmap != null && bannerBitmap != null) {
                    builder.setLargeIcon(rawBitmap)
                    builder.setStyle(
                        NotificationCompat.BigPictureStyle()
                            .bigPicture(bannerBitmap)
                            .bigLargeIcon(null as Bitmap?)
                            .setBigContentTitle(title)
                            .setSummaryText(body)
                    )
                } else {
                    builder.setStyle(NotificationCompat.BigTextStyle().bigText(body).setBigContentTitle(title))
                }

                val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                manager.notify(notifId, builder.build())
            }
        }
    }
}
