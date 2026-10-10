package com.veycad.app

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.opengl.EGL14
import android.opengl.EGLExt
import android.opengl.GLES20
import android.os.Build
import android.os.Looper
import android.view.Surface
import androidx.annotation.WorkerThread
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap

/** Declaration-only query plus an explicit worker probe, used only after Export is requested. */
class EncoderCapabilityProbe(
    private val backend: Backend = AndroidBackend(),
    private val fingerprint: () -> String = { Build.FINGERPRINT },
    private val cache: MutableMap<CacheKey, Boolean> = sharedCache
) {
    data class Capabilities(
        val codecName: String,
        val mime: String,
        val widthAlignment: Int,
        val heightAlignment: Int,
        val bitrateMin: Int,
        val bitrateMax: Int,
        val sizeAndRateSupported: (OutputSize, Int) -> Boolean,
        internal val surfaceStartResult: (ExportProfile) -> Boolean? = { null }
    ) {
        fun supports(size: OutputSize, fps: Int): Boolean =
            fps > 0 && widthAlignment > 0 && heightAlignment > 0 &&
                bitrateMin >= 0 && bitrateMax > 0 && bitrateMax >= bitrateMin &&
                size.width % widthAlignment == 0 && size.height % heightAlignment == 0 &&
                sizeAndRateSupported(size, fps)
    }

    data class CacheKey(val fingerprint: String, val codecName: String, val size: OutputSize,
        val fps: Int, val bitrate: Int)

    /** Injection boundary is the device codec operation; resolution and cache remain real JVM code. */
    interface Backend {
        fun query(): List<Capabilities>
        fun verifySurfaceStart(profile: ExportProfile, checkCancelled: () -> Unit): Boolean
    }

    fun query(): List<Capabilities> = backend.query().map { capabilities ->
        capabilities.copy(surfaceStartResult = { profile -> synchronized(cache) { cache[key(profile)] } })
    }

    @WorkerThread
    fun verifySurfaceStart(profile: ExportProfile, checkCancelled: () -> Unit): Boolean {
        checkCancelled()
        val key = key(profile)
        synchronized(cache) { cache[key] }?.let { return it }
        val result = backend.verifySurfaceStart(profile, checkCancelled)
        // A cancellation after the last device operation must not poison availability either.
        checkCancelled()
        synchronized(cache) { cache[key] = result }
        return result
    }

    private fun key(profile: ExportProfile) = CacheKey(fingerprint(), profile.encoderName,
        profile.size, profile.fps, profile.bitrate)

    private class AndroidBackend : Backend {
        override fun query(): List<Capabilities> =
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.mapNotNull { codec ->
                if (!codec.isEncoder || codec.supportedTypes.none { it.equals(AVC, true) }) return@mapNotNull null
                // Reading declarations does not instantiate, configure or start an encoder/EGL.
                runCatching {
                    val caps = codec.getCapabilitiesForType(AVC)
                    if (MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface !in caps.colorFormats) return@mapNotNull null
                    val video = caps.videoCapabilities ?: return@mapNotNull null
                    Capabilities(codec.name, AVC, video.widthAlignment, video.heightAlignment,
                        video.bitrateRange.lower, video.bitrateRange.upper,
                        { size, fps -> runCatching {
                            video.areSizeAndRateSupported(size.width, size.height, fps.toDouble())
                        }.getOrDefault(false) })
                }.getOrNull()
            }

        @WorkerThread
        override fun verifySurfaceStart(profile: ExportProfile, checkCancelled: () -> Unit): Boolean {
            check(Looper.myLooper() != Looper.getMainLooper()) { "Encoder probe must run on a worker" }
            var codec: MediaCodec? = null
            var surface: Surface? = null
            var egl: ProbeFrame? = null
            var started = false
            // Preserve any exception from the caller's cancellation hook, even if it is not a
            // CancellationException. Only actual codec/GL errors become an unsupported pair.
            var cancellation: Throwable? = null
            val check = {
                try { checkCancelled() } catch (failure: Throwable) {
                    cancellation = failure
                    throw failure
                }
            }
            return try {
                check()
                codec = MediaCodec.createByCodecName(profile.encoderName)
                val format = MediaFormat.createVideoFormat(AVC, profile.size.width, profile.size.height).apply {
                    setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                    setInteger(MediaFormat.KEY_BIT_RATE, profile.bitrate)
                    setInteger(MediaFormat.KEY_FRAME_RATE, profile.fps)
                    setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                }
                codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                check()
                surface = codec.createInputSurface()
                codec.start()
                started = true
                check()
                egl = ProbeFrame()
                egl.attach(surface)
                check()
                egl.submit(profile.size)
                check()
                codec.signalEndOfInputStream()
                codec.stop()
                started = false
                true
            } catch (failure: Exception) {
                if (cancellation != null) throw cancellation!!
                if (failure is CancellationException || failure is InterruptedException) throw failure
                false
            } finally {
                // Every allocation is owned before the next fallible step; partial attach is safe.
                egl?.close()
                if (started) runCatching { codec?.stop() }
                runCatching { surface?.release() }
                runCatching { codec?.release() }
            }
        }
    }

    /** One black frame, no muxer and no montage shaders. Owned only by this probe's worker. */
    private class ProbeFrame : AutoCloseable {
        private var display = EGL14.EGL_NO_DISPLAY
        private var context = EGL14.EGL_NO_CONTEXT
        private var eglSurface = EGL14.EGL_NO_SURFACE

        fun attach(surface: Surface) {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            check(display != EGL14.EGL_NO_DISPLAY)
            val version = IntArray(2)
            check(EGL14.eglInitialize(display, version, 0, version, 1))
            val configs = arrayOfNulls<android.opengl.EGLConfig>(1)
            val count = IntArray(1)
            check(EGL14.eglChooseConfig(display, intArrayOf(
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8, EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT, 0x3142, 1, EGL14.EGL_NONE
            ), 0, configs, 0, 1, count, 0) && count[0] > 0)
            context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
            check(context != EGL14.EGL_NO_CONTEXT)
            eglSurface = EGL14.eglCreateWindowSurface(display, configs[0], surface,
                intArrayOf(EGL14.EGL_NONE), 0)
            check(eglSurface != EGL14.EGL_NO_SURFACE)
            check(EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context))
        }

        fun submit(size: OutputSize) {
            GLES20.glViewport(0, 0, size.width, size.height)
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            check(GLES20.glGetError() == GLES20.GL_NO_ERROR)
            check(EGLExt.eglPresentationTimeANDROID(display, eglSurface, 0L))
            check(EGL14.eglSwapBuffers(display, eglSurface))
        }

        override fun close() {
            if (display != EGL14.EGL_NO_DISPLAY) {
                runCatching { EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT) }
                if (eglSurface != EGL14.EGL_NO_SURFACE) runCatching { EGL14.eglDestroySurface(display, eglSurface) }
                if (context != EGL14.EGL_NO_CONTEXT) runCatching { EGL14.eglDestroyContext(display, context) }
                runCatching { EGL14.eglTerminate(display) }
            }
            runCatching { EGL14.eglReleaseThread() }
        }
    }

    companion object {
        private const val AVC = "video/avc"
        private val sharedCache: MutableMap<CacheKey, Boolean> = ConcurrentHashMap()
    }
}
