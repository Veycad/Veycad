package com.example.autoedit

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.opengl.EGL14
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.util.Log
import android.view.Surface
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Decodes ascending analysis targets in one codec session and reads scaled RGB through GLES.
 *
 * The external-OES path intentionally matches production rendering: MediaCodec applies the track
 * rotation at the Surface boundary and the device performs its normal YUV/HDR conversion. Each
 * target uses the first decoded frame at or after its PTS, matching the production decoder clock.
 * Output buffers are never retained: retaining even one can exhaust a vendor Surface buffer pool
 * and turn hardware decoding into a multi-minute operation.
 */
internal object SequentialBitmapDecoder {
    data class Metadata(
        val durationUs: Long,
        val displayWidth: Int,
        val displayHeight: Int
    )

    data class Stats(val requestedFrames: Int, val decodedFrames: Int)

    fun metadata(source: File): Metadata = MediaExtractor().let { extractor ->
        try {
            extractor.setDataSource(source.absolutePath)
            val format = extractor.videoFormat()
            val durationUs = format.getLong(MediaFormat.KEY_DURATION)
            val width = format.getInteger(MediaFormat.KEY_WIDTH)
            val height = format.getInteger(MediaFormat.KEY_HEIGHT)
            val rotation = format.integerOrDefault(MediaFormat.KEY_ROTATION, 0).normalizedRotation()
            Metadata(
                durationUs,
                if (rotation % 180 == 0) width else height,
                if (rotation % 180 == 0) height else width
            )
        } finally {
            extractor.release()
        }
    }

    fun intervalTargets(durationUs: Long, intervalUs: Long): LongArray {
        require(durationUs > 0L && intervalUs > 0L)
        val first = minOf(intervalUs / 2L, durationUs - 1L)
        val targets = ArrayList<Long>()
        var target = first
        while (target < durationUs) {
            targets += target
            target += intervalUs
        }
        return targets.toLongArray()
    }

    fun decode(
        source: File,
        targetsUs: LongArray,
        width: Int,
        height: Int,
        requireExactPts: Boolean = false,
        onFrame: (targetUs: Long, bitmap: Bitmap) -> Unit
    ): Stats {
        require(source.isFile && width > 0 && height > 0)
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        var output: GlOutput? = null
        try {
            extractor.setDataSource(source.absolutePath)
            val track = extractor.videoTrack()
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val durationUs = format.getLong(MediaFormat.KEY_DURATION)
            Log.i(TAG, "Decode target preflight: exactPts=$requireExactPts, " +
                "declaredVideoDurationUs=$durationUs, lastTargetUs=${targetsUs.lastOrNull()}, " +
                "targetCount=${targetsUs.size}")
            SourceAnalysisTimeline.validateDecodeTargets(targetsUs, durationUs, requireExactPts)
            output = GlOutput(width, height)
            decoder = MediaCodec.createDecoderByType(
                requireNotNull(format.getString(MediaFormat.KEY_MIME))
            ).apply {
                configure(format, output.surface, null, 0)
                start()
            }
            return decodeAscending(extractor, decoder, output, targetsUs, requireExactPts, onFrame)
        } finally {
            runCatching { decoder?.stop() }
            decoder?.release()
            extractor.release()
            output?.release()
        }
    }

    private fun decodeAscending(
        extractor: MediaExtractor,
        decoder: MediaCodec,
        output: GlOutput,
        targetsUs: LongArray,
        requireExactPts: Boolean,
        onFrame: (Long, Bitmap) -> Unit
    ): Stats {
        val info = MediaCodec.BufferInfo()
        var inputEnded = false
        var outputEnded = false
        var targetIndex = 0
        var decodedFrames = 0
        var idleIterations = 0

        fun publish(index: Int, ptsUs: Long) {
            decoder.releaseOutputBuffer(index, true)
            output.awaitAndUpdate()
            val bitmap = output.readBitmap()
            try {
                while (targetIndex < targetsUs.size && targetsUs[targetIndex] <= ptsUs) {
                    onFrame(targetsUs[targetIndex], bitmap)
                    targetIndex++
                }
            } finally {
                bitmap.recycle()
            }
        }

        while (!outputEnded && targetIndex < targetsUs.size) {
            var progressed = false
            if (!inputEnded) {
                val inputIndex = decoder.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
                if (inputIndex >= 0) {
                    val input = requireNotNull(decoder.getInputBuffer(inputIndex)).apply { clear() }
                    val size = extractor.readSampleData(input, 0)
                    if (size < 0) {
                        decoder.queueInputBuffer(
                            inputIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                        )
                        inputEnded = true
                    } else {
                        decoder.queueInputBuffer(inputIndex, 0, size, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                    progressed = true
                }
            }

            when (val index = decoder.dequeueOutputBuffer(info, DEQUEUE_TIMEOUT_US)) {
                MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> progressed = true
                else -> if (index >= 0) {
                    progressed = true
                    val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    if (info.size > 0) {
                        val ptsUs = info.presentationTimeUs.coerceAtLeast(0L)
                        decodedFrames++
                        if (targetIndex < targetsUs.size && ptsUs >= targetsUs[targetIndex]) {
                            if (requireExactPts) SourceAnalysisTimeline.requireDecodedPts(
                                targetsUs[targetIndex], ptsUs)
                            publish(index, ptsUs)
                        } else decoder.releaseOutputBuffer(index, false)
                    } else {
                        decoder.releaseOutputBuffer(index, false)
                    }
                    outputEnded = eos
                }
            }
            idleIterations = if (progressed) 0 else idleIterations + 1
            check(idleIterations < MAX_IDLE_ITERATIONS) { "Sequential video decoder stalled" }
        }

        check(targetIndex == targetsUs.size) {
            "Decoded $targetIndex of ${targetsUs.size} analysis targets"
        }
        return Stats(targetsUs.size, decodedFrames)
    }

    private class GlOutput(private val width: Int, private val height: Int) {
        private val display: android.opengl.EGLDisplay
        private val context: android.opengl.EGLContext
        private val eglSurface: android.opengl.EGLSurface
        private val texture: Int
        private val surfaceTexture: SurfaceTexture
        val surface: Surface
        private val program: Int
        private val positionBuffer = floatBuffer(floatArrayOf(
            -1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f
        ))
        private val textureBuffer = floatBuffer(
            VideoDisplayOrientation.externalOesTextureCoordinates()
        )
        private val frameLock = Object()
        private var frameAvailable = false

        init {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            check(display != EGL14.EGL_NO_DISPLAY)
            val version = IntArray(2)
            check(EGL14.eglInitialize(display, version, 0, version, 1))
            val configs = arrayOfNulls<android.opengl.EGLConfig>(1)
            val count = IntArray(1)
            val attributes = intArrayOf(
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_NONE
            )
            check(EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0) && count[0] > 0)
            context = EGL14.eglCreateContext(
                display,
                configs[0],
                EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE),
                0
            )
            check(context != EGL14.EGL_NO_CONTEXT)
            eglSurface = EGL14.eglCreatePbufferSurface(
                display,
                configs[0],
                intArrayOf(EGL14.EGL_WIDTH, width, EGL14.EGL_HEIGHT, height, EGL14.EGL_NONE),
                0
            )
            check(eglSurface != EGL14.EGL_NO_SURFACE)
            makeCurrent()
            texture = createExternalTexture()
            surfaceTexture = SurfaceTexture(texture).apply {
                setOnFrameAvailableListener {
                    synchronized(frameLock) {
                        frameAvailable = true
                        frameLock.notifyAll()
                    }
                }
            }
            surface = Surface(surfaceTexture)
            program = createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        }

        fun awaitAndUpdate() {
            synchronized(frameLock) {
                val deadline = System.nanoTime() + FRAME_TIMEOUT_NS
                while (!frameAvailable && System.nanoTime() < deadline) frameLock.wait(20L)
                check(frameAvailable) { "Timed out waiting for sequential decoder frame" }
                frameAvailable = false
            }
            makeCurrent()
            surfaceTexture.updateTexImage()
        }

        fun readBitmap(): Bitmap {
            makeCurrent()
            GLES20.glViewport(0, 0, width, height)
            GLES20.glUseProgram(program)
            val position = GLES20.glGetAttribLocation(program, "aPosition")
            val texCoord = GLES20.glGetAttribLocation(program, "aTexCoord")
            positionBuffer.position(0)
            textureBuffer.position(0)
            GLES20.glEnableVertexAttribArray(position)
            GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 0, positionBuffer)
            GLES20.glEnableVertexAttribArray(texCoord)
            GLES20.glVertexAttribPointer(texCoord, 2, GLES20.GL_FLOAT, false, 0, textureBuffer)
            val matrix = FloatArray(16).also(surfaceTexture::getTransformMatrix)
            GLES20.glUniformMatrix4fv(
                GLES20.glGetUniformLocation(program, "uTexMatrix"), 1, false, matrix, 0
            )
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture)
            GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uTexture"), 0)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            val rgba = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder())
            GLES20.glReadPixels(0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, rgba)
            check(GLES20.glGetError() == GLES20.GL_NO_ERROR) { "Unable to read analysis frame" }
            val pixels = IntArray(width * height)
            repeat(height) { targetY ->
                val sourceY = height - targetY - 1
                repeat(width) { x ->
                    val offset = (sourceY * width + x) * 4
                    pixels[targetY * width + x] = Color.argb(
                        rgba.get(offset + 3).toInt() and 0xff,
                        rgba.get(offset).toInt() and 0xff,
                        rgba.get(offset + 1).toInt() and 0xff,
                        rgba.get(offset + 2).toInt() and 0xff
                    )
                }
            }
            return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
        }

        fun release() {
            runCatching { makeCurrent() }
            runCatching { surface.release(); surfaceTexture.release() }
            runCatching {
                GLES20.glDeleteProgram(program)
                GLES20.glDeleteTextures(1, intArrayOf(texture), 0)
            }
            EGL14.eglDestroySurface(display, eglSurface)
            EGL14.eglDestroyContext(display, context)
            EGL14.eglReleaseThread()
            EGL14.eglTerminate(display)
        }

        private fun makeCurrent() {
            check(EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context))
        }

        private fun createExternalTexture(): Int = IntArray(1).also {
            GLES20.glGenTextures(1, it, 0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, it[0])
            GLES20.glTexParameteri(
                GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR
            )
            GLES20.glTexParameteri(
                GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR
            )
            GLES20.glTexParameteri(
                GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE
            )
            GLES20.glTexParameteri(
                GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE
            )
        }[0]

        private fun createProgram(vertex: String, fragment: String): Int {
            fun shader(type: Int, source: String): Int = GLES20.glCreateShader(type).also { id ->
                GLES20.glShaderSource(id, source)
                GLES20.glCompileShader(id)
                val status = IntArray(1)
                GLES20.glGetShaderiv(id, GLES20.GL_COMPILE_STATUS, status, 0)
                check(status[0] == GLES20.GL_TRUE) { GLES20.glGetShaderInfoLog(id) }
            }
            return GLES20.glCreateProgram().also { id ->
                GLES20.glAttachShader(id, shader(GLES20.GL_VERTEX_SHADER, vertex))
                GLES20.glAttachShader(id, shader(GLES20.GL_FRAGMENT_SHADER, fragment))
                GLES20.glLinkProgram(id)
                val status = IntArray(1)
                GLES20.glGetProgramiv(id, GLES20.GL_LINK_STATUS, status, 0)
                check(status[0] == GLES20.GL_TRUE) { GLES20.glGetProgramInfoLog(id) }
            }
        }
    }

    private fun MediaExtractor.videoTrack(): Int = (0 until trackCount).firstOrNull { index ->
        getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
    } ?: error("Video track unavailable")

    private fun MediaExtractor.videoFormat(): MediaFormat = getTrackFormat(videoTrack())

    private fun MediaFormat.integerOrDefault(key: String, fallback: Int): Int =
        if (containsKey(key)) getInteger(key) else fallback

    private fun Int.normalizedRotation(): Int = ((this % 360) + 360) % 360

    private fun floatBuffer(values: FloatArray): FloatBuffer = ByteBuffer
        .allocateDirect(values.size * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .apply { put(values); position(0) }

    private const val DEQUEUE_TIMEOUT_US = 10_000L
    private const val TAG = "VeycadDecode"
    private const val MAX_IDLE_ITERATIONS = 500
    private const val FRAME_TIMEOUT_NS = 2_000_000_000L
    private const val VERTEX_SHADER = """
        attribute vec4 aPosition;
        attribute vec4 aTexCoord;
        uniform mat4 uTexMatrix;
        varying vec2 vTexCoord;
        void main() {
            gl_Position = aPosition;
            vTexCoord = (uTexMatrix * aTexCoord).xy;
        }
    """
    private const val FRAGMENT_SHADER = """
        #extension GL_OES_EGL_image_external : require
        precision mediump float;
        varying vec2 vTexCoord;
        uniform samplerExternalOES uTexture;
        void main() { gl_FragColor = texture2D(uTexture, vTexCoord); }
    """
}
