package dev.lyra.music.lyrics

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.lyra.music.MainActivity
import dev.lyra.music.data.db.SongEntity
import dev.lyra.music.data.prefs.UserPrefs
import dev.lyra.music.data.repo.LibraryRepository
import dev.lyra.music.playback.PlayerConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * Desktop floating lyrics: a small draggable overlay showing the current line (and translation)
 * on top of other apps. Shown while the setting is on, the overlay permission is granted, the
 * player has a song and Lyra itself is not in the foreground. Double-tap opens Lyra.
 *
 * Plain Android views are used on purpose: an overlay window has no Compose host lifecycle.
 */
@Singleton
class FloatingLyricsController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val prefs: UserPrefs,
    private val player: PlayerConnection,
    private val library: LibraryRepository,
    private val lyricsRepository: LyricsRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val wm = context.getSystemService(WindowManager::class.java)
    private val appInForeground = MutableStateFlow(false)
    private var started = false
    private var view: LinearLayout? = null
    private var mainText: TextView? = null
    private var subText: TextView? = null
    private var params: WindowManager.LayoutParams? = null
    private var lyrics: Lyrics? = null
    private var offsetMs = 0L
    private var lastIndex = Int.MIN_VALUE
    private var lyricsJob: Job? = null

    fun hasPermission(): Boolean = Settings.canDrawOverlays(context)

    fun permissionIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Idempotent; call from the service and the activity so whichever lives longer keeps it running. */
    fun start() {
        if (started) return
        started = true
        ProcessLifecycleOwner.get().lifecycle.addObserver(LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_START -> appInForeground.value = true
                Lifecycle.Event.ON_STOP -> appInForeground.value = false
                else -> Unit
            }
        })

        // Which song → which lyrics / offset.
        scope.launch {
            player.state.map { it.songId }.distinctUntilChanged()
                .flatMapLatest { id -> if (id == null) flowOf(null) else library.observeSong(id) }
                .distinctUntilChanged { a, b -> a?.id == b?.id && a?.dateModified == b?.dateModified && a?.lyricsOffsetMs == b?.lyricsOffsetMs }
                .collect { song ->
                    offsetMs = song?.lyricsOffsetMs ?: 0L
                    loadLyrics(song)
                }
        }
        // Visibility.
        scope.launch {
            combine(prefs.floatingLyrics, player.state, appInForeground) { on, st, fg ->
                on && st.hasItem && !fg && hasPermission()
            }.distinctUntilChanged().collect { show -> if (show) attach() else detach() }
        }
        // Current line.
        scope.launch {
            player.state.collect { st -> render(st.positionMs) }
        }
    }

    private fun loadLyrics(song: SongEntity?) {
        lyricsJob?.cancel()
        lyrics = null
        lastIndex = Int.MIN_VALUE
        if (song == null) { render(0); return }
        lyricsJob = scope.launch {
            val loaded = withContext(Dispatchers.IO) { runCatching { lyricsRepository.load(song) }.getOrNull() }
            lyrics = loaded?.lyrics?.takeIf { it.synced && !it.isEmpty }
            lastIndex = Int.MIN_VALUE
            render(player.state.value.positionMs)
        }
    }

    private fun render(positionMs: Long) {
        val v = view ?: return
        val l = lyrics
        if (l == null) {
            if (lastIndex != -2) {
                lastIndex = -2
                mainText?.text = player.state.value.currentItem?.mediaMetadata?.title ?: ""
                subText?.visibility = View.GONE
            }
            return
        }
        val idx = l.indexAt(positionMs, offsetMs)
        if (idx == lastIndex) return
        lastIndex = idx
        val line = l.lines.getOrNull(idx)
        mainText?.text = line?.text?.ifBlank { "♪" } ?: "♪"
        val tr = line?.translation
        subText?.text = tr.orEmpty()
        subText?.visibility = if (tr.isNullOrBlank()) View.GONE else View.VISIBLE
        v.requestLayout()
    }

    private fun attach() {
        if (view != null) return
        val dp = { v: Float -> TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, context.resources.displayMetrics) }
        val main = TextView(context).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            maxLines = 2
            setShadowLayer(dp(2f), 0f, dp(1f), Color.BLACK)
        }
        val sub = TextView(context).apply {
            setTextColor(Color.argb(230, 255, 255, 255))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            gravity = Gravity.CENTER
            maxLines = 2
            visibility = View.GONE
            setShadowLayer(dp(2f), 0f, dp(1f), Color.BLACK)
        }
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(16f).toInt(), dp(8f).toInt(), dp(16f).toInt(), dp(8f).toInt())
            background = GradientDrawable().apply {
                cornerRadius = dp(14f)
                setColor(Color.argb(150, 0, 0, 0))
            }
            addView(main)
            addView(sub)
        }
        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = savedY
            horizontalMargin = 0.04f
        }
        layout.setOnTouchListener(DragListener(p) { params ->
            savedY = params.y
            runCatching { wm.updateViewLayout(layout, params) }
        })
        runCatching { wm.addView(layout, p) }.onFailure { return }
        view = layout; mainText = main; subText = sub; params = p
        lastIndex = Int.MIN_VALUE
        render(player.state.value.positionMs)
    }

    private fun detach() {
        val v = view ?: return
        runCatching { wm.removeViewImmediate(v) }
        view = null; mainText = null; subText = null; params = null
    }

    private var savedY: Int = (context.resources.displayMetrics.heightPixels * 0.12f).toInt()

    /** Vertical drag; double-tap opens the app. */
    private inner class DragListener(
        private val p: WindowManager.LayoutParams,
        private val onMove: (WindowManager.LayoutParams) -> Unit,
    ) : View.OnTouchListener {
        private var startY = 0f
        private var startParamY = 0
        private var lastTapAt = 0L
        private var moved = false

        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { startY = e.rawY; startParamY = p.y; moved = false }
                MotionEvent.ACTION_MOVE -> {
                    val dy = e.rawY - startY
                    if (abs(dy) > 8) moved = true
                    p.y = (startParamY + dy).toInt().coerceAtLeast(0)
                    onMove(p)
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) {
                        val now = System.currentTimeMillis()
                        if (now - lastTapAt < 350) {
                            context.startActivity(
                                Intent(context, MainActivity::class.java).apply {
                                    action = MainActivity.ACTION_OPEN_PLAYER
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                                }
                            )
                        }
                        lastTapAt = now
                    }
                    v.performClick()
                }
            }
            return true
        }
    }
}
