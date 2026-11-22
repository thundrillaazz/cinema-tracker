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
import android.os.Environment
import android.provider.MediaStore
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
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
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private val executor = Executors.newSingleThreadExecutor()
    private var isPageLoaded = false
    private var pendingMovieId: String? = null
    private var pendingTab: String? = null

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        createNotificationChannels()
        requestNotificationPermissionIfNeeded()
        scheduleBackgroundWorker()
        captureDeepLinkIntent(intent)

        webView = WebView(this).apply {
            setBackgroundColor(Color.parseColor("#F8FAFC"))
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.loadsImagesAutomatically = true
            settings.cacheMode = WebSettings.LOAD_DEFAULT
            settings.allowFileAccess = true
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    isPageLoaded = true
                    dispatchPendingDeepLink()
                }

                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest?
                ): Boolean {
                    val uri = request?.url ?: return false
                    if (uri.scheme == "file" && uri.path?.startsWith("/android_asset/") == true) {
                        return false
                    }
                    return try {
                        startActivity(Intent(Intent.ACTION_VIEW, uri))
                        true
                    } catch (_: Exception) {
                        true
                    }
                }
            }
            webChromeClient = WebChromeClient()
            addJavascriptInterface(AndroidBridge(this@MainActivity), "AndroidBridge")
            loadUrl("file:///android_asset/index.html")
        }

        setContentView(webView)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                webView.evaluateJavascript("window.handleAndroidBack ? window.handleAndroidBack() : false") { result ->
                    if (result != "true" && result != "\"true\"") {
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

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        captureDeepLinkIntent(intent)
        if (isPageLoaded) {
            dispatchPendingDeepLink()
        }
    }

    override fun onResume() {
        super.onResume()
        if (::webView.isInitialized && isPageLoaded) {
            webView.evaluateJavascript("window.onAppResume && window.onAppResume()", null)
        }
    }

    override fun onDestroy() {
        if (::webView.isInitialized) {
            webView.destroy()
        }
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun captureDeepLinkIntent(intent: Intent?) {
        val mId = intent?.getStringExtra(EXTRA_OPEN_MOVIE_ID)
        val tab = intent?.getStringExtra(EXTRA_OPEN_TAB)
        if (!mId.isNullOrEmpty()) pendingMovieId = mId
        if (!tab.isNullOrEmpty()) pendingTab = tab
    }

    private fun dispatchPendingDeepLink() {
        val mId = pendingMovieId
        val tab = pendingTab
        if (mId == null && tab == null) return
        pendingMovieId = null
        pendingTab = null
        val qId = JSONObject.quote(mId ?: "")
        val qTab = JSONObject.quote(tab ?: "")
        if (::webView.isInitialized) {
            webView.evaluateJavascript(
                "window.openMovieFromNotification && window.openMovieFromNotification($qId, $qTab)",
                null
            )
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

    private fun hasNotificationPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            val urgentChannel = NotificationChannel(
                CHANNEL_WATCHLIST_URGENT,
                "Pengingat Watchlist & Pre-Sale",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifikasi Hari-H, H-1, pre-sale tiket, konfirmasi TBA ID, dan perubahan jadwal"
                enableLights(true)
                lightColor = Color.BLUE
                enableVibration(true)
            }

            val digestChannel = NotificationChannel(
                CHANNEL_WEEKLY_DIGEST,
                "Radar Mingguan & Rilis Baru",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Ringkasan mingguan bioskop Indonesia dan rilis populer baru"
            }

            val legacyChannel = NotificationChannel(
                CHANNEL_ID,
                "Jadwal Bioskop Indonesia",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifikasi umum jadwal bioskop Indonesia"
            }

            manager.createNotificationChannel(urgentChannel)
            manager.createNotificationChannel(digestChannel)
            manager.createNotificationChannel(legacyChannel)
        }
    }

    private fun scheduleBackgroundWorker() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val intervalHours = prefs.getLong("sync_interval_hours", 6L).coerceIn(1L, 24L)

        // NetworkType.NOT_REQUIRED ensures local watchlist reminders (H-1, Hari-H, Pre-sale)
        // still fire reliably when offline. Worker checks connectivity before TMDB requests.
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
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
        val quoted = JSONObject.quote(message)
        runOnUiThread {
            if (::webView.isInitialized) {
                webView.evaluateJavascript("window.showToast && window.showToast($quoted)", null)
            }
        }
    }

    inner class AndroidBridge(private val context: Context) {

        @JavascriptInterface
        fun setSystemBarTheme(isDark: Boolean) {
            runOnUiThread {
                val bgColor = if (isDark) Color.parseColor("#09090B") else Color.parseColor("#FFFFFF")
                val pageBg = if (isDark) Color.parseColor("#09090B") else Color.parseColor("#F8FAFC")
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
        fun updateTbaWatchlistJson(tbaWatchIdsJson: String) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putString("tba_watch_list_json", tbaWatchIdsJson).apply()
        }

        @JavascriptInterface
        fun saveSettings(apiKey: String, minHype: Int, intervalHours: Int, tbaWatchIdsJson: String) {
            val cleanKey = apiKey.trim()
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit()
                .putString("tmdb_api_key", cleanKey)
                .putInt("min_hype_threshold", minHype)
                .putLong("sync_interval_hours", intervalHours.toLong().coerceIn(1L, 24L))
                .putString("tba_watch_list_json", tbaWatchIdsJson)
                .apply()

            scheduleBackgroundWorker()

            if (cleanKey.isNotEmpty()) {
                val immediateWork = OneTimeWorkRequestBuilder<CinemaCheckWorker>()
                    .setInputData(Data.Builder().putBoolean("force_network_sync", true).build())
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
        }

        @JavascriptInterface
        fun runWatchlistReminderCheckNow() {
            val immediateWork = OneTimeWorkRequestBuilder<CinemaCheckWorker>()
                .setInputData(Data.Builder().putBoolean("force_network_sync", true).build())
                .build()
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
                "com.michael.cinematracker.LauncherTrio",
                "com.michael.cinematracker.LauncherSciFi",
                "com.michael.cinematracker.LauncherNoir",
                "com.michael.cinematracker.LauncherBudapest",
                "com.michael.cinematracker.LauncherCyber",
                "com.michael.cinematracker.LauncherImax",
                "com.michael.cinematracker.LauncherReel35",
                "com.michael.cinematracker.LauncherTrinity",
                "com.michael.cinematracker.LauncherAction"
            )
            val targetAlias = "com.michael.cinematracker.$aliasName"
            if (!aliases.contains(targetAlias)) return

            val pm = context.packageManager

            // Enable target alias FIRST so the launcher never sees zero active aliases
            pm.setComponentEnabledSetting(
                ComponentName(context, targetAlias),
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP
            )

            for (alias in aliases) {
                if (alias != targetAlias) {
                    pm.setComponentEnabledSetting(
                        ComponentName(context, alias),
                        PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                        PackageManager.DONT_KILL_APP
                    )
                }
            }
            showJsToast("Ikon aplikasi diperbarui")
        }

        @JavascriptInterface
        fun downloadPosterImage(posterUrl: String, movieTitle: String) {
            if (posterUrl.isEmpty()) {
                showJsToast("Poster belum tersedia")
                return
            }
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                showJsToast("Izin penyimpanan diperlukan")
                return
            }

            showJsToast("Mengunduh poster...")
            executor.execute {
                var connection: HttpURLConnection? = null
                try {
                    val hdUrl = posterUrl.replace("/w500/", "/original/")
                    connection = (URL(hdUrl).openConnection() as HttpURLConnection).apply {
                        doInput = true
                        connectTimeout = 8000
                        readTimeout = 8000
                    }
                    connection.connect()
                    val bytes = connection.inputStream.use { it.readBytes() }

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
                        @Suppress("DEPRECATION")
                        val dir = File(
                            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                            "CinemaTracker"
                        )
                        if (!dir.exists()) dir.mkdirs()
                        val file = File(dir, fileName)
                        FileOutputStream(file).use { out -> out.write(bytes) }
                        @Suppress("DEPRECATION")
                        val scanIntent = Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, Uri.fromFile(file))
                        context.sendBroadcast(scanIntent)
                        showJsToast("Poster tersimpan di Galeri")
                    }
                } catch (_: Exception) {
                    showJsToast("Gagal mengunduh poster")
                } finally {
                    connection?.disconnect()
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
            if (!hasNotificationPermission()) {
                showJsToast("Aktifkan izin notifikasi Android")
                return
            }
            executor.execute {
                var rawBitmap: Bitmap? = null
                var bannerBitmap: Bitmap? = null
                var connection: HttpURLConnection? = null
                try {
                    if (posterUrl.isNotEmpty()) {
                        val url = URL(posterUrl.replace("/w500/", "/w342/"))
                        connection = (url.openConnection() as HttpURLConnection).apply {
                            doInput = true
                            connectTimeout = 5000
                            readTimeout = 5000
                        }
                        connection.connect()
                        val opts = BitmapFactory.Options().apply {
                            inPreferredConfig = Bitmap.Config.RGB_565
                        }
                        rawBitmap = connection.inputStream.use { BitmapFactory.decodeStream(it, null, opts) }
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
                    putExtra(EXTRA_OPEN_TAB, "watchlist")
                }
                val notifId = (title.hashCode() and 0x7fffffff)
                val pendingIntent = PendingIntent.getActivity(
                    context,
                    notifId,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )

                val builder = NotificationCompat.Builder(context, CHANNEL_WATCHLIST_URGENT)
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

                rawBitmap?.recycle()
                bannerBitmap?.recycle()
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
            } catch (_: Exception) {
                val url = "https://calendar.google.com/calendar/render?action=TEMPLATE&text=" +
                    Uri.encode(title) + "&details=" + Uri.encode(description) +
                    "&location=" + Uri.encode(location)
                try {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                } catch (_: Exception) {}
            }
        }
    }

    companion object {
        const val CHANNEL_ID = "cinema_hype_alerts"
        const val CHANNEL_WATCHLIST_URGENT = "cinema_watchlist_urgent"
        const val CHANNEL_WEEKLY_DIGEST = "cinema_weekly_digest"
        const val EXTRA_OPEN_MOVIE_ID = "open_movie_id"
        const val EXTRA_OPEN_TAB = "open_tab"
        const val PREFS_NAME = "cinema_tracker_prefs"
    }
}
