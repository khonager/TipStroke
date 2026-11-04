package dev.tipstroke.drawing.android

import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import android.view.TextureView
import android.widget.FrameLayout

/** Hardware-decoded video beneath native raster/Ink views. No per-frame Bitmap extraction. */
internal class MediaVideoView(
    context: Context,
    private val source: MediaSource,
    private val onPosition: (Long, Boolean, Boolean) -> Unit,
    private val onFailure: (String) -> Unit,
) : FrameLayout(context), TextureView.SurfaceTextureListener {
    private val texture = TextureView(context).apply { surfaceTextureListener = this@MediaVideoView; isOpaque = false }
    private val matrix = Matrix()
    private var canvasWidth = source.width
    private var canvasHeight = source.height
    private var session: Session? = null
    private var desiredTimeUs = 0L
    private var revision = 0

    init { addView(texture, LayoutParams(source.width, source.height)); clipChildren = false }

    fun colorSnapshot(): android.graphics.Bitmap? {
        if (!texture.isAvailable) return null
        val scale = minOf(1f, 1280f / maxOf(source.width, source.height))
        return texture.getBitmap((source.width * scale).toInt().coerceAtLeast(1), (source.height * scale).toInt().coerceAtLeast(1))
    }

    fun updateTransform(value: Matrix, width: Int, height: Int) {
        matrix.set(value); canvasWidth = width; canvasHeight = height; invalidate()
    }

    override fun dispatchDraw(canvas: Canvas) {
        val save = canvas.save()
        canvas.concat(matrix)
        canvas.clipRect(0, 0, canvasWidth, canvasHeight)
        super.dispatchDraw(canvas)
        canvas.restoreToCount(save)
    }

    fun seek(timeUs: Long) {
        desiredTimeUs = timeUs.coerceIn(0, source.durationUs - 1)
        val time = desiredTimeUs
        val token = ++revision
        onPosition(time, false, true)
        session?.let { it.handler.post { it.seek(time, token) } }
    }
    fun play() {
        val token = ++revision
        session?.let { it.handler.post { it.token = token; it.wantsPlayback = true; it.startIfReady() } }
    }
    fun pause() {
        val token = ++revision
        session?.let { it.handler.post { it.token = token; it.wantsPlayback = false; if (it.prepared) it.player?.pause(); it.handler.removeCallbacks(it.clock) } }
    }

    /** Each surface owns its own player/thread. Old teardown cannot release a new player. */
    private inner class Session(val output: Surface, initialTime: Long, initialToken: Int) {
        val thread = HandlerThread("TipStroke-video").apply { start() }
        val handler = Handler(thread.looper)
        var player: MediaPlayer? = null
        var token = initialToken
        var prepared = false
        var wantsPlayback = false
        var seeking: Long? = null
        var pendingSeek: Long? = initialTime
        private fun position(time: Long, playing: Boolean) {
            val updateToken = token
            post { if (session === this && revision == updateToken) { desiredTimeUs = time; onPosition(time, playing, false) } }
        }
        val clock = object : Runnable {
            override fun run() {
                if (prepared && seeking == null && wantsPlayback) {
                    position(((player?.currentPosition ?: 0) * 1000L).coerceAtMost(source.durationUs - 1), true)
                    handler.postDelayed(this, 16)
                }
            }
        }
        fun startIfReady() {
            if (prepared && seeking == null && pendingSeek == null && wantsPlayback) {
                player?.start(); handler.removeCallbacks(clock); handler.post(clock)
            }
        }
        fun seek(time: Long, revision: Int) {
            token = revision; wantsPlayback = false; handler.removeCallbacks(clock)
            if (prepared) player?.pause()
            pendingSeek = time; performSeek()
        }
        private fun performSeek() {
            if (!prepared || seeking != null) return
            val time = pendingSeek ?: return
            pendingSeek = null; seeking = time
            player?.seekTo(time / 1000, MediaPlayer.SEEK_CLOSEST)
        }
        fun initialize() = handler.post {
            try {
                val video = MediaPlayer().also { player = it }
                video.setVolume(0f, 0f)
                video.setSurface(output)
                video.setDataSource(source.file.path)
                video.setOnPreparedListener { prepared = true; performSeek() }
                video.setOnSeekCompleteListener {
                    val completed = seeking ?: return@setOnSeekCompleteListener
                    seeking = null
                    if (pendingSeek != null) performSeek()
                    else { position(completed, wantsPlayback); startIfReady() }
                }
                video.setOnErrorListener { _, what, extra -> fail("Video playback failed ($what/$extra). Try reopening the drawing."); true }
                video.setOnCompletionListener { wantsPlayback = false; position(source.durationUs - 1, false) }
                video.prepareAsync()

            } catch (error: Exception) { fail(error.message ?: "Cannot play video") }
        }
        private fun fail(message: String) {
            prepared = false; wantsPlayback = false
            post { if (session === this) onFailure(message) }
        }
        fun release() {
            // Keep initialization queued: it owns the Surface and must run before teardown.
            handler.post {
                handler.removeCallbacksAndMessages(null)
                player?.release(); player = null
                output.release()
                thread.quitSafely()
            }
        }
    }
    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        session?.release()
        onPosition(desiredTimeUs, false, true)
        session = Session(Surface(surface), desiredTimeUs, revision).also { it.initialize() }
    }
    fun release() { revision++; session?.release(); session = null }
    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean { release(); return true }
    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) = Unit
    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
}
