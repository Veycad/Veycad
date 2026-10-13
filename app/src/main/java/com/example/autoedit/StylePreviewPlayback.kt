package com.veycad.app

import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.provider.Settings
import android.view.Surface
import android.view.TextureView
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner

/** Only these licensed, app-rendered demos may be packaged; private footage is never consulted. */
internal object StylePreviewAssets {
    data class Asset(val video: Int, val poster: Int)
    fun forStyle(style: MontageStyleCatalog.Style): Asset? = if (!style.available) null else when (style.recipe) {
        MontageStyleCatalog.Recipe.HEARTBEAT -> Asset(R.raw.style_preview_heartbeat, R.drawable.style_preview_heartbeat)
        MontageStyleCatalog.Recipe.FEAR_STROBE -> Asset(R.raw.style_preview_fear, R.drawable.style_preview_fear)
        MontageStyleCatalog.Recipe.DUALITY_LOOP -> Asset(R.raw.style_preview_duality, R.drawable.style_preview_duality)
        else -> null
    }
}

/** One decoder and one texture for the whole picker. Posters remain on every idle card. */
internal class StylePreviewPlayback(
    private val activity: AppCompatActivity,
    private val scroll: ScrollView,
    private val cards: List<Card>,
    private val selectedId: () -> String
) : DefaultLifecycleObserver, ViewTreeObserver.OnScrollChangedListener,
    ViewTreeObserver.OnWindowFocusChangeListener {
    data class Card(val id: String, val asset: StylePreviewAssets.Asset,
        val container: FrameLayout, val poster: ImageView, val status: TextView)

    private var active: Card? = null
    private var player: MediaPlayer? = null
    private var surface: Surface? = null
    private var requestedId: String? = null
    private var paused = false
    private var closed = false
    private var focused = true
    private var resumed = activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
    private var videoWidth = 0
    private var videoHeight = 0
    private val failed = mutableSetOf<String>()
    private val autoplay = Settings.Global.getFloat(activity.contentResolver,
        Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
    private val texture = TextureView(activity).apply {
        importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO
        surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(value: SurfaceTexture, width: Int, height: Int) { prepare(value) }
            override fun onSurfaceTextureSizeChanged(value: SurfaceTexture, width: Int, height: Int) { fitVideo() }
            override fun onSurfaceTextureUpdated(value: SurfaceTexture) = Unit
            override fun onSurfaceTextureDestroyed(value: SurfaceTexture): Boolean { releasePlayer(); return true }
        }
    }

    init {
        cards.forEach { describe(it, "Пример остановлен", "Воспроизвести пример") }
        activity.lifecycle.addObserver(this)
        scroll.viewTreeObserver.addOnScrollChangedListener(this)
        scroll.viewTreeObserver.addOnWindowFocusChangeListener(this)
    }

    fun request(id: String) {
        if (closed || cards.none { it.id == id }) return
        if (active?.id == id) {
            paused = true
            stop()
        } else {
            paused = false
            failed.remove(id)
            requestedId = id
            update()
        }
    }

    fun update() {
        if (closed || !focused || !resumed || paused) { stop(); return }
        val visible = cards.map { it to visibleFraction(it) }.filter { it.second > 0f && it.first.id !in failed }
        val autoplayCandidates = visible.filter { it.second >= .5f }
        val target = visible.firstOrNull { it.first.id == requestedId }?.first
            ?: if (autoplay) autoplayCandidates.firstOrNull { it.first.id == selectedId() }?.first
                ?: autoplayCandidates.maxByOrNull { it.second }?.first else null
        if (target?.id == active?.id) return
        stop()
        if (target == null) return
        active = target
        describe(target, "Загрузка примера", "Остановить пример")
        target.container.addView(texture, 1, FrameLayout.LayoutParams(-1, -1))
        if (texture.isAvailable) prepare(requireNotNull(texture.surfaceTexture))
    }

    private fun visibleFraction(card: Card): Float {
        val rect = Rect()
        if (card.container.width == 0 || card.container.height == 0 ||
            !card.container.getGlobalVisibleRect(rect)) return 0f
        return rect.width().toFloat() * rect.height() / (card.container.width.toFloat() * card.container.height)
    }

    private fun prepare(value: SurfaceTexture) {
        val card = active ?: return
        if (player != null || closed || !resumed || !focused) return
        val media = MediaPlayer()
        player = media
        surface = Surface(value)
        try {
            media.setSurface(surface)
            activity.resources.openRawResourceFd(card.asset.video).use {
                media.setDataSource(it.fileDescriptor, it.startOffset, it.length)
            }
            media.setOnPreparedListener {
                if (player !== media || active !== card || closed || !resumed || !focused) return@setOnPreparedListener
                videoWidth = media.videoWidth
                videoHeight = media.videoHeight
                fitVideo()
                media.isLooping = true
                media.setVolume(0f, 0f)
                media.start()
            }
            media.setOnInfoListener { _, what, _ ->
                if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START && player === media && active === card) {
                    card.poster.visibility = android.view.View.INVISIBLE
                    card.status.text = "Ⅱ Пример · 3 с"
                    describe(card, "Пример воспроизводится без звука", "Приостановить пример")
                    texture.tag = "style_preview_playing:${card.id}"
                }
                false
            }
            media.setOnErrorListener { _, _, _ ->
                if (player === media && active === card) fail(card)
                true
            }
            media.prepareAsync()
        } catch (_: Exception) { fail(card) }
    }

    private fun fail(card: Card) {
        failed += card.id
        stop()
        card.status.text = "Повторить пример"
        describe(card, "Не удалось воспроизвести пример", "Повторить пример")
    }

    private fun describe(card: Card, state: String, action: String) {
        ViewCompat.setStateDescription(card.container, state)
        ViewCompat.replaceAccessibilityAction(card.container,
            AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_CLICK, action, null)
    }

    private fun fitVideo() {
        if (videoWidth <= 0 || videoHeight <= 0 || texture.width == 0 || texture.height == 0) return
        val scale = minOf(texture.width.toFloat() / videoWidth, texture.height.toFloat() / videoHeight)
        texture.setTransform(Matrix().apply {
            setScale(videoWidth * scale / texture.width, videoHeight * scale / texture.height,
                texture.width / 2f, texture.height / 2f)
        })
    }

    private fun releasePlayer() {
        player?.apply {
            setOnPreparedListener(null); setOnErrorListener(null); setOnInfoListener(null)
            release()
        }
        player = null
        surface?.release()
        surface = null
    }

    private fun stop() {
        active?.let {
            it.poster.visibility = android.view.View.VISIBLE
            it.status.text = "▶ Пример · 3 с"
            describe(it, "Пример остановлен", "Воспроизвести пример")
        }
        active = null
        texture.tag = null
        releasePlayer()
        (texture.parent as? FrameLayout)?.removeView(texture)
    }

    fun close() {
        if (closed) return
        closed = true
        stop()
        activity.lifecycle.removeObserver(this)
        if (scroll.viewTreeObserver.isAlive) {
            scroll.viewTreeObserver.removeOnScrollChangedListener(this)
            scroll.viewTreeObserver.removeOnWindowFocusChangeListener(this)
        }
    }

    override fun onScrollChanged() = update()
    override fun onWindowFocusChanged(hasFocus: Boolean) { focused = hasFocus; update() }
    override fun onPause(owner: LifecycleOwner) { resumed = false; stop() }
    override fun onResume(owner: LifecycleOwner) { resumed = true; update() }
    override fun onDestroy(owner: LifecycleOwner) = close()
}
