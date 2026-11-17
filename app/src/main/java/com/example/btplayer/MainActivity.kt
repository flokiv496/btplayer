package com.example.btplayer

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

class MainActivity : Activity() {

    private val immersive = true // false — оставить системные панели магнитолы

    private lateinit var msm: MediaSessionManager
    private lateinit var listener: ComponentName
    private lateinit var root: View
    private lateinit var cover: ImageView
    private lateinit var tvTitle: TextView
    private lateinit var tvArtist: TextView
    private lateinit var tvAlbum: TextView
    private lateinit var tvSource: TextView
    private lateinit var tvElapsed: TextView
    private lateinit var tvRemaining: TextView
    private lateinit var seek: SeekBar
    private lateinit var btnPlay: ImageButton
    private lateinit var permPanel: View

    private val ui = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private var controller: MediaController? = null
    private var duration = 0L
    private var dragging = false
    private var tick = 0
    private var artKey: String? = null

    private val controllerCb = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = showMetadata(metadata)
        override fun onPlaybackStateChanged(state: PlaybackState?) = showState(state)
        override fun onSessionDestroyed() = attach()
    }
    private val sessionsCb = MediaSessionManager.OnActiveSessionsChangedListener { pick(it ?: emptyList()) }

    private val ticker = object : Runnable {
        override fun run() {
            updateProgress()
            if (++tick % 4 == 0) attach() // раз в 2 с: вдруг заиграла другая сессия
            ui.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)
        msm = getSystemService(MEDIA_SESSION_SERVICE) as MediaSessionManager
        listener = ComponentName(this, MediaListenerService::class.java)

        root = findViewById(R.id.root)
        cover = findViewById(R.id.cover)
        tvTitle = findViewById(R.id.title)
        tvArtist = findViewById(R.id.artist)
        tvAlbum = findViewById(R.id.album)
        tvSource = findViewById(R.id.source)
        tvElapsed = findViewById(R.id.elapsed)
        tvRemaining = findViewById(R.id.remaining)
        seek = findViewById(R.id.seek)
        btnPlay = findViewById(R.id.btnPlay)
        permPanel = findViewById(R.id.permPanel)

        cover.outlineProvider = ViewOutlineProvider.BACKGROUND
        cover.clipToOutline = true

        findViewById<View>(R.id.btnPrev).setOnClickListener { controller?.transportControls?.skipToPrevious() }
        findViewById<View>(R.id.btnNext).setOnClickListener { controller?.transportControls?.skipToNext() }
        btnPlay.setOnClickListener {
            val tc = controller?.transportControls ?: return@setOnClickListener
            if (controller?.playbackState?.state == PlaybackState.STATE_PLAYING) tc.pause() else tc.play()
        }
        findViewById<Button>(R.id.permBtn).setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            } catch (e: ActivityNotFoundException) {
                Toast.makeText(this, R.string.perm_adb, Toast.LENGTH_LONG).show()
            }
        }
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                if (fromUser) tvElapsed.text = fmt(p.toLong())
            }
            override fun onStartTrackingTouch(s: SeekBar) { dragging = true }
            override fun onStopTrackingTouch(s: SeekBar) {
                dragging = false
                controller?.transportControls?.seekTo(s.progress.toLong())
            }
        })
        showMetadata(null)
        showState(null)
    }

    override fun onResume() {
        super.onResume()
        if (immersive) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }
        try { msm.addOnActiveSessionsChangedListener(sessionsCb, listener, ui) } catch (e: SecurityException) {}
        attach()
        ui.post(ticker)
    }

    override fun onPause() {
        super.onPause()
        ui.removeCallbacks(ticker)
        try { msm.removeOnActiveSessionsChangedListener(sessionsCb) } catch (e: Exception) {}
        controller?.unregisterCallback(controllerCb)
        controller = null
    }

    // ---------- выбор сессии ----------

    private fun attach() {
        val list = try {
            msm.getActiveSessions(listener)
        } catch (e: SecurityException) {
            permPanel.visibility = View.VISIBLE
            return
        }
        permPanel.visibility = View.GONE
        pick(list)
    }

    private fun isBt(pkg: String) = pkg.contains("bluetooth", true) || pkg.contains("btmusic", true) ||
        pkg.contains("a2dp", true) || pkg.contains(".bt", true)

    private fun pick(list: List<MediaController>) {
        val best = list.filter { it.packageName != packageName }.sortedWith(
            compareBy<MediaController>(
                { it.playbackState?.state != PlaybackState.STATE_PLAYING }, // играющая — первой
                { !isBt(it.packageName) }                                    // затем Bluetooth-источник
            )
        ).firstOrNull()
        if (best?.sessionToken == controller?.sessionToken && (best != null || controller == null)) return
        controller?.unregisterCallback(controllerCb)
        controller = best
        best?.registerCallback(controllerCb, ui)
        tvSource.text = best?.let { "BLUETOOTH · " + appLabel(it.packageName) } ?: getString(R.string.source_none)
        showMetadata(best?.metadata)
        showState(best?.playbackState)
    }

    private fun appLabel(pkg: String): String = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (e: PackageManager.NameNotFoundException) { pkg }

    // ---------- отрисовка ----------

    private fun showMetadata(m: MediaMetadata?) {
        tvTitle.text = m?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
            ?: m?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: getString(R.string.idle)
        tvArtist.text = m?.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: m?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: m?.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE) ?: ""
        tvAlbum.text = m?.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: ""
        duration = m?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        seek.max = max(duration, 0L).toInt()

        val bmp = m?.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: m?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: m?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        if (bmp != null) { artKey = null; setArt(bmp); return }
        val uri = m?.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI)
            ?: m?.getString(MediaMetadata.METADATA_KEY_ART_URI)
            ?: m?.getString(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI)
        if (uri == null) { artKey = null; setArt(null); return }
        if (uri == artKey) return
        artKey = uri
        io.execute {
            val b = try {
                contentResolver.openInputStream(Uri.parse(uri))?.use { BitmapFactory.decodeStream(it) }
            } catch (e: Exception) { null }
            ui.post { if (artKey == uri) setArt(b) }
        }
    }

    private fun setArt(b: Bitmap?) {
        if (b == null) {
            val pad = (48 * resources.displayMetrics.density).toInt()
            cover.setPadding(pad, pad, pad, pad)
            cover.scaleType = ImageView.ScaleType.FIT_CENTER
            cover.setImageResource(R.drawable.ic_note)
            root.setBackgroundColor(getColor(R.color.bg))
            return
        }
        cover.setPadding(0, 0, 0, 0)
        cover.scaleType = ImageView.ScaleType.CENTER_CROP
        cover.setImageBitmap(b)
        // Акцентный фон из среднего цвета обложки, приглушённый до тёмного
        val px = Bitmap.createScaledBitmap(b, 1, 1, true).getPixel(0, 0)
        val hsv = FloatArray(3)
        Color.colorToHSV(px, hsv)
        hsv[1] = min(hsv[1], 0.75f); hsv[2] = 0.30f
        root.background = GradientDrawable(
            GradientDrawable.Orientation.TL_BR, intArrayOf(Color.HSVToColor(hsv), getColor(R.color.bg))
        )
    }

    private fun showState(s: PlaybackState?) {
        val playing = s?.state == PlaybackState.STATE_PLAYING
        btnPlay.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
        seek.isEnabled = s != null && duration > 0 && (s.actions and PlaybackState.ACTION_SEEK_TO) != 0L
        updateProgress()
    }

    private fun updateProgress() {
        val s = controller?.playbackState
        if (s == null) { seek.progress = 0; tvElapsed.text = "0:00"; tvRemaining.text = "--:--"; return }
        if (dragging) return
        var pos = max(s.position, 0L)
        if (s.state == PlaybackState.STATE_PLAYING)
            pos += ((SystemClock.elapsedRealtime() - s.lastPositionUpdateTime) * s.playbackSpeed).toLong()
        if (duration > 0) pos = pos.coerceIn(0L, duration)
        seek.progress = pos.toInt()
        tvElapsed.text = fmt(pos)
        tvRemaining.text = if (duration > 0) "-" + fmt(duration - pos) else "--:--"
    }

    private fun fmt(ms: Long): String {
        val t = ms / 1000
        return if (t >= 3600) "%d:%02d:%02d".format(t / 3600, t % 3600 / 60, t % 60) else "%d:%02d".format(t / 60, t % 60)
    }
}
