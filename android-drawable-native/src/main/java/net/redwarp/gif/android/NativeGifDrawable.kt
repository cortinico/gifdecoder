package net.redwarp.gif.android

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.vectordrawable.graphics.drawable.Animatable2Compat
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import net.redwarp.gif.decoder.LoopCount
import net.redwarp.gif.decoder.NativeGif
import net.redwarp.gif.decoder.Parser
import net.redwarp.gif.decoder.PixelPacking
import java.io.File
import java.io.InputStream
import java.util.concurrent.CancellationException

class NativeGifDrawable(inputStream: InputStream) : Drawable(), Animatable2Compat {
    constructor(file: File) : this(file.inputStream())

    private val gifDescriptor = Parser.parse(inputStream, PixelPacking.ARGB)
    private val gif = NativeGif(gifDescriptor)

    private val bitmapCache = NativeBitmapPool()

    private val callbacks = mutableListOf<Animatable2Compat.AnimationCallback>()
    private val lock = Object()

    @Volatile
    private var isRunning: Boolean = false
    private var loopJob: Job? = null
    private val width = gif.dimension.width
    private val height = gif.dimension.height

    private val pixels = IntArray(gif.dimension.size)
    private var bitmap: Bitmap = getCurrentFrame()

    private val bitmapPaint = Paint().apply {
        isAntiAlias = false
        isFilterBitmap = false
        isDither = bitmap.config == Bitmap.Config.RGB_565
    }

    init {
        setBounds(0, 0, intrinsicWidth, intrinsicHeight)
    }

    private var loopIteration = 0
    private var _loopCount: LoopCount? = null
    private val handler: Handler by lazy { Handler(Looper.getMainLooper()) }

    fun getLoopCount(): LoopCount {
        return _loopCount ?: gif.loopCount
    }

    /**
     * Override the gif's intrinsic loop settings with your own
     * @param loopCount Pass in NoLoop if you don't want animation,
     * Infinite for infinite loop,
     * of Fixed to restrain to a set amount of loops.
     */
    fun setLoopCount(loopCount: LoopCount?) {
        _loopCount = loopCount
    }

    override fun getIntrinsicWidth(): Int {
        return if (gif.aspectRatio >= 1.0) {
            (width.toDouble() * gif.aspectRatio).toInt()
        } else {
            width
        }
    }

    override fun getIntrinsicHeight(): Int {
        return if (gif.aspectRatio >= 1.0) {
            height
        } else {
            (height.toDouble() / gif.aspectRatio).toInt()
        }
    }

    override fun draw(canvas: Canvas) {
        synchronized(lock) {
            canvas.drawBitmap(bitmap, null, bounds, bitmapPaint)
        }
    }

    override fun setAlpha(alpha: Int) {
        bitmapPaint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        bitmapPaint.colorFilter = colorFilter
    }

    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    override fun start() {
        if (isRunning) return // Already running.

        if (!gif.isAnimated) return // No need to animate single gifs

        loopJob?.run { if (isActive) cancel() }

        loopJob = CoroutineScope(Dispatchers.Default + CoroutineName(this.toString())).launch {
            animationLoop()
        }

        postAnimationStart()

        isRunning = true
    }

    override fun stop() {
        if (!isRunning) return // Already stopped.

        loopJob?.cancel(CancellationException("Finishing the job"))

        postAnimationEnd()

        isRunning = false
    }

    override fun isRunning(): Boolean = isRunning

    override fun registerAnimationCallback(callback: Animatable2Compat.AnimationCallback) {
        callbacks.add(callback)
    }

    override fun unregisterAnimationCallback(callback: Animatable2Compat.AnimationCallback): Boolean {
        return callbacks.remove(callback)
    }

    override fun clearAnimationCallbacks() {
        callbacks.clear()
    }

    private fun postAnimationStart() {
        if (!callbacks.isEmpty())
            handler.post {
                callbacks.forEach { callback ->
                    callback.onAnimationStart(this)
                }
            }
    }

    private fun postAnimationEnd() {
        if (!callbacks.isEmpty())
            handler.post {
                callbacks.forEach { callback ->
                    callback.onAnimationEnd(this)
                }
            }
    }

    private suspend fun animationLoop() = coroutineScope {
        if (!gif.isAnimated || getLoopCount() == LoopCount.NoLoop) return@coroutineScope
        val loopCount = getLoopCount()
        if (loopCount is LoopCount.Fixed) {
            if (loopIteration >= loopCount.count) {
                return@coroutineScope
            }
        }

        while (true) {
            ensureActive()
            val frameDelay = gif.currentDelay.let {
                // If the frame delay is 0, let's at last have 2 frame before we display it.
                if (it == 0L) 32L else it
            }
            gif.advance()
            if (gif.currentIndex == 0) {
                // We looped back to the first frame
                loopIteration++
            }
            // Checking if we are finished looping already
            @Suppress("NAME_SHADOWING") val loopCount = getLoopCount()
            if (loopCount is LoopCount.Fixed) {
                if (loopIteration >= loopCount.count) {
                    postAnimationEnd()
                    return@coroutineScope
                }
            }

            val startTime = SystemClock.elapsedRealtime()

            val nextFrame = getCurrentFrame()

            val elapsedTime: Long = SystemClock.elapsedRealtime() - startTime
            val delay: Long = (frameDelay - elapsedTime).coerceIn(0L, frameDelay)

            delay(delay)

            synchronized(lock) {
                val oldBitmap = bitmap
                bitmap = nextFrame
                bitmapCache.release(oldBitmap)
                bitmapPaint.isDither = bitmap.config == Bitmap.Config.RGB_565
            }
            withContext(Dispatchers.Main) {
                invalidateSelf()
            }
            yield()
        }
    }

    private fun getCurrentFrame(): Bitmap {
        gif.getCurrentFrame(pixels)

        val transparent = pixels.any { it == 0 }

        val nextFrame: Bitmap = bitmapCache.obtain(
            width = width,
            height = height,
            config = if (transparent) Bitmap.Config.ARGB_8888 else Bitmap.Config.RGB_565
        )
        nextFrame.setPixels(pixels, 0, width, 0, 0, width, height)
        return nextFrame
    }

    companion object {
        fun from(inputStream: InputStream): NativeGifDrawable = NativeGifDrawable(inputStream)
    }
}
