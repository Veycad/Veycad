package com.veycad.app

import android.graphics.Bitmap
import android.opengl.GLES20
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/** Private JNI-backed storage; neither handles nor NIO views escape an owned operation. */
internal class PreviewUploadBuffer private constructor(
    private val owner: PreviewAllocationOwner,
    val capacity: Int
) : AutoCloseable {
    private var handle = 0L
    private var bytes: ByteBuffer? = null
    private var vertices: FloatBuffer? = null
    private lateinit var reservation: PreviewAllocationOwner.Reservation

    private fun data(required: Int): ByteBuffer {
        owner.requireUsable()
        check(handle != 0L) { "Preview upload storage is closed" }
        require(required in 1..capacity)
        return checkNotNull(bytes).apply { clear(); limit(required) }
    }

    fun setVertices(values: FloatArray) {
        require(values.size in 1..(capacity / 4))
        val buffer = data(values.size * 4).order(ByteOrder.nativeOrder())
        vertices = buffer.asFloatBuffer().apply { put(values); position(0) }
    }

    fun bindAttribute(location: Int) {
        data(capacity)
        GLES20.glVertexAttribPointer(location, 2, GLES20.GL_FLOAT, false, 0, checkNotNull(vertices))
    }

    fun uploadPlane(width: Int, height: Int, plane: FrameAttachments.Plane?,
        blend: FrameAttachments.Plane?, progress: Float, fresh: Boolean) {
        val buffer = data(pixelCapacity(width, height, 1))
        if (plane == null) buffer.put(0) else if (blend != null && blend.width == width && blend.height == height) {
            (0 until plane.size).forEach { index ->
                val value = plane[index] + (blend[index] - plane[index]) * progress
                buffer.put((value.coerceIn(0f, 1f) * 255f).toInt().toByte())
            }
        } else for (index in 0 until plane.size) buffer.put((plane[index].coerceIn(0f, 1f) * 255f).toInt().toByte())
        upload(buffer, width, height, GLES20.GL_LUMINANCE, fresh)
    }

    fun uploadFlow(width: Int, height: Int, flow: FrameAttachments.FlowPlane?, fresh: Boolean) {
        val buffer = data(pixelCapacity(width, height, 2))
        if (flow == null) { buffer.put(127.toByte()); buffer.put(127.toByte()) }
        else flow.vectors.forEach { buffer.put((((it.coerceIn(-1f, 1f) * .5f + .5f) * 255f).toInt()).toByte()) }
        upload(buffer, width, height, GLES20.GL_LUMINANCE_ALPHA, fresh)
    }

    fun uploadTitle(bitmap: Bitmap?) {
        val buffer = data(bitmap?.byteCount ?: 1)
        if (bitmap == null) buffer.put(0) else bitmap.copyPixelsToBuffer(buffer)
        upload(buffer, bitmap?.width ?: 1, bitmap?.height ?: 1, GLES20.GL_ALPHA, true)
    }

    /** Reuses the retained staging allocation and texture storage for output-coordinate text. */
    fun uploadRgba(bitmap: Bitmap, fresh: Boolean) {
        require(bitmap.config == Bitmap.Config.ARGB_8888 && !bitmap.isRecycled)
        require(bitmap.rowBytes == bitmap.width * 4) { "Text bitmap must have packed RGBA rows" }
        val buffer = data(pixelCapacity(bitmap.width, bitmap.height, 4))
        bitmap.copyPixelsToBuffer(buffer)
        upload(buffer, bitmap.width, bitmap.height, GLES20.GL_RGBA, fresh)
    }

    private fun upload(buffer: ByteBuffer, width: Int, height: Int, format: Int, fresh: Boolean) {
        buffer.position(0)
        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1)
        try {
            if (fresh) GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, format, width, height, 0,
                format, GLES20.GL_UNSIGNED_BYTE, buffer)
            else GLES20.glTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, width, height,
                format, GLES20.GL_UNSIGNED_BYTE, buffer)
            check(GLES20.glGetError() == GLES20.GL_NO_ERROR) { "Preview pixel upload failed" }
        } finally { GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 4) }
        // GLES client upload data is consumed before Tex(Sub)Image2D returns. No view is returned.
    }

    override fun close() = reservation.close()

    private fun dispose() {
        if (handle != 0L) {
            PreviewNativeMemory.free(handle)
            handle = 0L // Only after the JNI call acknowledges free; failures remain retryable.
            vertices = null
            bytes = null
        }
    }

    companion object {
        fun pixelCapacity(width: Int, height: Int, components: Int): Int {
            require(width > 0 && height > 0 && components in 1..4)
            val pixels = width.toLong() * height
            require(pixels <= Int.MAX_VALUE / components) { "Upload dimensions exceed addressable storage" }
            return (pixels * components).toInt()
        }

        fun create(owner: PreviewAllocationOwner, capacity: Int): PreviewUploadBuffer {
            val bytes = PreviewAllocationOwner.storageBytes(capacity)
            val storage = PreviewUploadBuffer(owner, capacity)
            storage.reservation = owner.reserve(bytes, storage::dispose)
            try {
                storage.handle = PreviewNativeMemory.allocate(capacity)
                check(storage.handle != 0L)
                storage.bytes = PreviewNativeMemory.view(storage.handle)
                return storage
            } catch (error: Throwable) {
                runCatching { storage.close() }.exceptionOrNull()?.let(error::addSuppressed)
                throw error
            }
        }
    }
}

@androidx.annotation.Keep // JNI uses these exact class/method names, including in a future minified build.
private object PreviewNativeMemory {
    init { System.loadLibrary("veycad_preview_memory") }
    external fun allocate(capacity: Int): Long
    external fun view(handle: Long): ByteBuffer
    external fun free(handle: Long)
}
