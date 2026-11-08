package com.djanime.femboybrowser

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.SurfaceTexture
import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.media.MediaPlayer
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import kotlin.math.max

/** Тонкая полоска прогресса загрузки страницы. */
class ProgressView(ctx: Context) : View(ctx) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    var fraction = 0f
        set(v) { field = v; invalidate() }
    var color = Color.MAGENTA
        set(v) { field = v; paint.color = v; invalidate() }

    override fun onDraw(canvas: Canvas) {
        canvas.drawRoundRect(0f, 0f, width * fraction, height.toFloat(), height / 2f, height / 2f, paint)
    }
}

/** Анимированный фон: mp4 (MediaPlayer + TextureView, без звука, по кругу) или gif/webp (AnimatedImageDrawable). */
class BackgroundView(ctx: Context) : FrameLayout(ctx), TextureView.SurfaceTextureListener {
    private val tv = TextureView(ctx)
    private val iv = ImageView(ctx).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
    private var mp: MediaPlayer? = null
    private var surface: Surface? = null
    private var entry: BgEntry? = null
    private var curKey: String? = null
    private var vw = 0
    private var vh = 0

    init {
        setBackgroundColor(Color.BLACK)
        addView(tv, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(iv, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        tv.surfaceTextureListener = this
        tv.visibility = GONE
        iv.visibility = GONE
    }

    fun show(e: BgEntry?) {
        val key = e?.let { it.stem + ":" + it.isVideo + ":" + (it.file?.lastModified() ?: 0L) }
        if (key == curKey) return
        curKey = key
        entry = e
        releasePlayer()
        (iv.drawable as? AnimatedImageDrawable)?.stop()
        iv.setImageDrawable(null)
        when {
            e == null -> { tv.visibility = GONE; iv.visibility = GONE }
            e.isVideo -> {
                iv.visibility = GONE
                if (tv.visibility == VISIBLE && surface != null) startVideo() else tv.visibility = VISIBLE
            }
            else -> { tv.visibility = GONE; showImage(e) }
        }
    }

    private fun showImage(e: BgEntry) {
        try {
            val src = if (e.assetPath != null) ImageDecoder.createSource(context.assets, e.assetPath) else ImageDecoder.createSource(e.file!!)
            val d = ImageDecoder.decodeDrawable(src)
            iv.setImageDrawable(d)
            iv.visibility = VISIBLE
            if (d is AnimatedImageDrawable) {
                d.repeatCount = AnimatedImageDrawable.REPEAT_INFINITE
                d.start()
            }
        } catch (ex: Exception) {
            iv.visibility = GONE
        }
    }

    private fun startVideo() {
        val e = entry ?: return
        val s = surface ?: return
        releasePlayer()
        val p = MediaPlayer()
        try {
            if (e.assetPath != null) {
                context.assets.openFd(e.assetPath).use { p.setDataSource(it.fileDescriptor, it.startOffset, it.length) }
            } else {
                p.setDataSource(e.file!!.absolutePath)
            }
            p.setSurface(s)
            p.isLooping = true
            p.setVolume(0f, 0f)
            p.setOnVideoSizeChangedListener { _, w, h -> vw = w; vh = h; applyCrop() }
            p.setOnPreparedListener { it.start() }
            p.setOnErrorListener { _, _, _ -> true }
            p.prepareAsync()
            mp = p
        } catch (ex: Exception) {
            p.release()
        }
    }

    private fun releasePlayer() {
        try { mp?.release() } catch (ex: Exception) { /* ignore */ }
        mp = null
    }

    /** «cover»: заполнить экран, лишнее обрезать. */
    private fun applyCrop() {
        val w = width.toFloat()
        val h = height.toFloat()
        if (vw == 0 || vh == 0 || w == 0f || h == 0f) return
        val s = max(w / vw, h / vh)
        val m = Matrix()
        m.setScale(vw * s / w, vh * s / h, w / 2f, h / 2f)
        tv.setTransform(m)
    }

    fun pause() {
        try { mp?.pause() } catch (ex: Exception) { /* ignore */ }
        (iv.drawable as? AnimatedImageDrawable)?.stop()
    }

    fun resume() {
        try { mp?.start() } catch (ex: Exception) { /* ignore */ }
        (iv.drawable as? AnimatedImageDrawable)?.start()
    }

    fun destroy() = releasePlayer()

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        applyCrop()
    }

    override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
        surface = Surface(st)
        startVideo()
    }

    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) = applyCrop()

    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
        releasePlayer()
        surface?.release()
        surface = null
        return true
    }

    override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
}
