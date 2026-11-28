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
import android.provider.Settings
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.io.FileOutputStream
import java.io.StringReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var rootContainer: FrameLayout
    private lateinit var webView: WebView
    private val executor = Executors.newFixedThreadPool(6)
    private var isPageLoaded = false
    private var pendingMovieId: String? = null
    private var pendingTab: String? = null
    private var fileChooserCallback: ValueCallback<Array<Uri>>? = null

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> }

    private val fileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val uris = WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
        fileChooserCallback?.onReceiveValue(uris)
        fileChooserCallback = null
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val initialDark = prefs.getBoolean("is_dark_theme", false)
        val initialBarColor = if (initialDark) Color.parseColor("#09090B") else Color.parseColor("#FFFFFF")
        val initialPageBg = if (initialDark) Color.parseColor("#09090B") else Color.parseColor("#F8FAFC")

        createNotificationChannels()
        requestNotificationPermissionIfNeeded()
        scheduleBackgroundWorker()
        captureDeepLinkIntent(intent)

        webView = WebView(this).apply {
            setBackgroundColor(initialPageBg)
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
            webChromeClient = object : WebChromeClient() {
                override fun onShowFileChooser(
                    webView: WebView?,
                    filePathCallback: ValueCallback<Array<Uri>>?,
                    fileChooserParams: FileChooserParams?
                ): Boolean {
                    fileChooserCallback?.onReceiveValue(null)
                    fileChooserCallback = filePathCallback
                    return try {
                        val intent = fileChooserParams?.createIntent()
                            ?: Intent(Intent.ACTION_GET_CONTENT).apply {
                                addCategory(Intent.CATEGORY_OPENABLE)
                                type = "application/json"
                            }
                        fileChooserLauncher.launch(intent)
                        true
                    } catch (_: Exception) {
                        fileChooserCallback?.onReceiveValue(null)
                        fileChooserCallback = null
                        showJsToast("Gunakan opsi tempel teks JSON backup")
                        false
                    }
                }
            }
            addJavascriptInterface(AndroidBridge(this@MainActivity), "AndroidBridge")
            loadUrl("file:///android_asset/index.html")
        }

        rootContainer = FrameLayout(this).apply {
            setBackgroundColor(initialBarColor)
            addView(
                webView,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
        }

        ViewCompat.setOnApplyWindowInsetsListener(rootContainer) { view, windowInsets ->
            val insets = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or
                    WindowInsetsCompat.Type.displayCutout() or
                    WindowInsetsCompat.Type.ime()
            )
            view.setPadding(insets.left, insets.top, insets.right, insets.bottom)
            WindowInsetsCompat.CONSUMED
        }

        setContentView(rootContainer)
        ViewCompat.requestApplyInsets(rootContainer)
        applySystemBarAppearance(initialDark)

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
        val intervalHours = prefs.getLong("sync_interval_hours", 3L).coerceIn(1L, 24L)

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
        val quoted = JSONObject.quote(message)
        runOnUiThread {
            if (::webView.isInitialized) {
                webView.evaluateJavascript("window.showToast && window.showToast($quoted)", null)
            }
        }
    }

    private fun dispatchRssCallback(callbackId: String, jsonArrayStr: String) {
        val qId = JSONObject.quote(callbackId)
        val qPayload = JSONObject.quote(jsonArrayStr)
        runOnUiThread {
            if (::webView.isInitialized) {
                webView.evaluateJavascript("window.__onAndroidRssResult && window.__onAndroidRssResult($qId, $qPayload)", null)
            }
        }
    }

    private fun dispatchCinemaFeedCallback(callbackId: String, jsonStr: String) {
        val qId = JSONObject.quote(callbackId)
        val qPayload = JSONObject.quote(jsonStr)
        runOnUiThread {
            if (::webView.isInitialized) {
                webView.evaluateJavascript("window.__onAndroidCinemaFeedResult && window.__onAndroidCinemaFeedResult($qId, $qPayload)", null)
            }
        }
    }

    private fun dispatchArticleTextCallback(callbackId: String, jsonStr: String) {
        val qId = JSONObject.quote(callbackId)
        val qPayload = JSONObject.quote(jsonStr)
        runOnUiThread {
            if (::webView.isInitialized) {
                webView.evaluateJavascript("window.__onAndroidArticleTextResult && window.__onAndroidArticleTextResult($qId, $qPayload)", null)
            }
        }
    }

    private fun dispatchUpdateCallback(callbackId: String, jsonStr: String) {
        val qId = JSONObject.quote(callbackId)
        val qPayload = JSONObject.quote(jsonStr)
        runOnUiThread {
            if (::webView.isInitialized) {
                webView.evaluateJavascript("window.__onAndroidUpdateMeta && window.__onAndroidUpdateMeta($qId, $qPayload)", null)
            }
        }
    }

    private fun openHttpsConnectionFollowingRedirects(startUrl: String, connectMs: Int = 8000, readMs: Int = 20000): HttpURLConnection {
        var current = startUrl
        for (hop in 0..5) {
            val conn = (URL(current).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = connectMs
                readTimeout = readMs
                setRequestProperty("User-Agent", "CinemaTracker-Android/4.4.2")
            }
            val code = conn.responseCode
            if (code in listOf(301, 302, 303, 307, 308)) {
                val loc = conn.getHeaderField("Location")
                conn.disconnect()
                if (loc.isNullOrEmpty() || !loc.startsWith("https://")) {
                    throw IllegalStateException("Invalid redirect")
                }
                current = loc
                continue
            }
            return conn
        }
        throw IllegalStateException("Too many redirects")
    }

    private fun applySystemBarAppearance(isDark: Boolean) {
        val barColor = if (isDark) Color.parseColor("#09090B") else Color.parseColor("#FFFFFF")
        val pageBg = if (isDark) Color.parseColor("#09090B") else Color.parseColor("#F8FAFC")
        if (::rootContainer.isInitialized) {
            rootContainer.setBackgroundColor(barColor)
        }
        @Suppress("DEPRECATION")
        window.statusBarColor = barColor
        @Suppress("DEPRECATION")
        window.navigationBarColor = barColor
        if (::webView.isInitialized) {
            webView.setBackgroundColor(pageBg)
        }
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.isAppearanceLightStatusBars = !isDark
        controller.isAppearanceLightNavigationBars = !isDark
    }

    inner class AndroidBridge(private val context: Context) {

        @JavascriptInterface
        fun setSystemBarTheme(isDark: Boolean) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean("is_dark_theme", isDark)
                .apply()
            runOnUiThread {
                applySystemBarAppearance(isDark)
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
        fun saveRadarConfig(feedUrl: String, serverUrl: String) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit()
                .putString("radar_feed_url", feedUrl.trim())
                .putString("radar_server_url", serverUrl.trim())
                .apply()
        }

        @JavascriptInterface
        fun getRadarConfigJson(): String {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return JSONObject().apply {
                put("feedUrl", prefs.getString("radar_feed_url", "") ?: "")
                put("serverUrl", prefs.getString("radar_server_url", "") ?: "")
            }.toString()
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
        fun exportWatchlistBackup(jsonStr: String, fileName: String) {
            val safeName = fileName.replace(Regex("[^a-zA-Z0-9._-]"), "_").ifEmpty {
                "cinema_tracker_backup.json"
            }
            executor.execute {
                try {
                    val bytes = jsonStr.toByteArray(StandardCharsets.UTF_8)

                    try {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            val values = ContentValues().apply {
                                put(MediaStore.Downloads.DISPLAY_NAME, safeName)
                                put(MediaStore.Downloads.MIME_TYPE, "application/json")
                                put(
                                    MediaStore.Downloads.RELATIVE_PATH,
                                    Environment.DIRECTORY_DOWNLOADS + "/CinemaTracker"
                                )
                                put(MediaStore.Downloads.IS_PENDING, 1)
                            }
                            val resolver = context.contentResolver
                            val dlUri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                            if (dlUri != null) {
                                resolver.openOutputStream(dlUri)?.use { it.write(bytes) }
                                values.clear()
                                values.put(MediaStore.Downloads.IS_PENDING, 0)
                                resolver.update(dlUri, values, null, null)
                            }
                        } else if (ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.WRITE_EXTERNAL_STORAGE
                            ) == PackageManager.PERMISSION_GRANTED
                        ) {
                            @Suppress("DEPRECATION")
                            val dir = File(
                                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                                "CinemaTracker"
                            )
                            if (!dir.exists()) dir.mkdirs()
                            FileOutputStream(File(dir, safeName)).use { it.write(bytes) }
                        }
                    } catch (_: Exception) {}

                    val backupDir = File(context.cacheDir, "backups")
                    if (!backupDir.exists()) backupDir.mkdirs()
                    backupDir.listFiles()?.forEach { old -> if (old.name != safeName) old.delete() }
                    val cacheFile = File(backupDir, safeName)
                    FileOutputStream(cacheFile).use { it.write(bytes) }

                    val shareUri = FileProvider.getUriForFile(
                        context,
                        "${context.packageName}.fileprovider",
                        cacheFile
                    )
                    val sendIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "application/json"
                        putExtra(Intent.EXTRA_STREAM, shareUri)
                        putExtra(Intent.EXTRA_SUBJECT, "Backup Watchlist Cinema Tracker")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    val chooser = Intent.createChooser(sendIntent, "Simpan / Bagikan Backup Watchlist")
                    runOnUiThread {
                        try {
                            context.startActivity(chooser)
                            showJsToast("Backup siap disimpan")
                        } catch (_: Exception) {
                            showJsToast("Backup tersimpan di Downloads/CinemaTracker")
                        }
                    }
                } catch (_: Exception) {
                    showJsToast("Gagal membuat file backup")
                }
            }
        }

        @JavascriptInterface
        fun fetchGoogleNewsRss(callbackId: String, movieTitle: String) {
            if (movieTitle.trim().length < 2) {
                dispatchRssCallback(callbackId, "[]")
                return
            }
            executor.execute {
                try {
                    val items = CinemaScraperHelper.fetchMovieNewsRss(movieTitle)
                    dispatchRssCallback(callbackId, items.toString())
                } catch (_: Exception) {
                    dispatchRssCallback(callbackId, "[]")
                }
            }
        }

        @JavascriptInterface
        fun fetchIndonesianReissueNewsRss(callbackId: String) {
            executor.execute {
                try {
                    val items = CinemaScraperHelper.fetchIndonesianReissueNewsRss()
                    dispatchRssCallback(callbackId, items.toString())
                } catch (_: Exception) {
                    dispatchRssCallback(callbackId, "[]")
                }
            }
        }

        @JavascriptInterface
        fun fetchIndonesianCinemaFeeds(callbackId: String, forceRefresh: Boolean) {
            executor.execute {
                try {
                    val payload = CinemaScraperHelper.fetchIndonesianCinemaFeedsJson(context, forceRefresh)
                    dispatchCinemaFeedCallback(callbackId, payload)
                } catch (_: Exception) {
                    dispatchCinemaFeedCallback(callbackId, "{\"entries\":[]}")
                }
            }
        }

        @JavascriptInterface
        fun fetchArticleScheduleText(callbackId: String, articleUrl: String) {
            val cleanUrl = articleUrl.trim()
            if (!cleanUrl.startsWith("http://") && !cleanUrl.startsWith("https://")) {
                dispatchArticleTextCallback(callbackId, "{\"url\":\"\",\"text\":\"\"}")
                return
            }
            executor.execute {
                try {
                    val resJson = CinemaScraperHelper.resolveArticleScheduleText(cleanUrl).toString()
                    dispatchArticleTextCallback(callbackId, resJson)
                } catch (_: Exception) {
                    dispatchArticleTextCallback(callbackId, "{\"url\":\"\",\"text\":\"\"}")
                }
            }
        }

        @JavascriptInterface
        fun getAppVersionInfoJson(): String {
            return try {
                val pInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
                } else {
                    @Suppress("DEPRECATION")
                    context.packageManager.getPackageInfo(context.packageName, 0)
                }
                val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    pInfo.longVersionCode
                } else {
                    @Suppress("DEPRECATION")
                    pInfo.versionCode.toLong()
                }
                JSONObject().apply {
                    put("versionCode", code)
                    put("versionName", pInfo.versionName ?: "4.4.2")
                    put("packageName", context.packageName)
                }.toString()
            } catch (_: Exception) {
                "{\"versionCode\":48,\"versionName\":\"4.4.2\",\"packageName\":\"com.michael.cinematracker\"}"
            }
        }

        @JavascriptInterface
        fun checkForApkUpdate(callbackId: String, metaUrl: String) {
            val cleanUrl = metaUrl.trim()
            if (!cleanUrl.startsWith("https://")) {
                dispatchUpdateCallback(callbackId, "{\"error\":\"URL tidak valid\"}")
                return
            }
            executor.execute {
                var conn: HttpURLConnection? = null
                try {
                    conn = openHttpsConnectionFollowingRedirects(cleanUrl, 7000, 7000)
                    if (conn.responseCode !in 200..299) {
                        dispatchUpdateCallback(callbackId, "{\"error\":\"HTTP_${conn.responseCode}\"}")
                        return@execute
                    }
                    val body = conn.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
                    dispatchUpdateCallback(callbackId, body)
                } catch (_: Exception) {
                    dispatchUpdateCallback(callbackId, "{\"error\":\"NETWORK\"}")
                } finally {
                    conn?.disconnect()
                }
            }
        }

        @JavascriptInterface
        fun downloadAndInstallApk(apkUrl: String) {
            val cleanUrl = apkUrl.trim()
            if (!cleanUrl.startsWith("https://")) {
                showJsToast("URL APK tidak valid")
                return
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
                showJsToast("Aktifkan izin install dari aplikasi ini, lalu tekan Update lagi")
                runOnUiThread {
                    try {
                        val permIntent = Intent(
                            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:${context.packageName}")
                        ).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        context.startActivity(permIntent)
                    } catch (_: Exception) {}
                }
                return
            }

            showJsToast("Mengunduh pembaruan APK...")
            executor.execute {
                var conn: HttpURLConnection? = null
                try {
                    conn = openHttpsConnectionFollowingRedirects(cleanUrl, 10000, 45000)
                    if (conn.responseCode !in 200..299) {
                        showJsToast("Gagal mengunduh update (HTTP ${conn.responseCode})")
                        return@execute
                    }
                    val updatesDir = File(context.cacheDir, "updates")
                    if (!updatesDir.exists()) updatesDir.mkdirs()
                    updatesDir.listFiles()?.forEach { it.delete() }
                    val targetApk = File(updatesDir, "CinemaTracker-update.apk")
                    conn.inputStream.use { input ->
                        FileOutputStream(targetApk).use { out ->
                            input.copyTo(out)
                        }
                    }
                    if (!targetApk.exists() || targetApk.length() < 500_000L) {
                        targetApk.delete()
                        showJsToast("File update tidak lengkap atau rusak")
                        return@execute
                    }

                    val apkUri = FileProvider.getUriForFile(
                        context,
                        "${context.packageName}.fileprovider",
                        targetApk
                    )
                    val installIntent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(apkUri, "application/vnd.android.package-archive")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    runOnUiThread {
                        try {
                            context.startActivity(installIntent)
                        } catch (_: Exception) {
                            showJsToast("Gagal membuka installer Android")
                        }
                    }
                } catch (_: Exception) {
                    showJsToast("Gagal mengunduh file update APK")
                } finally {
                    conn?.disconnect()
                }
            }
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
