package com.veycad.app

/** EGL and presentation belong to the output, supplied Android Surface remains caller-owned. */
interface RenderTarget : AutoCloseable {
    val size: OutputSize
    fun makeCurrent()
    fun resize(size: OutputSize)
    fun present(outputTimeUs: Long)
}

internal enum class RenderTargetPolicy(val recordable: Boolean) {
    DISPLAY(false), ENCODER(true);
    fun presentationTimeNs(outputTimeUs: Long): Long? {
        require(outputTimeUs >= 0)
        return if(recordable) Math.multiplyExact(outputTimeUs,1_000L) else null
    }
}

internal data class OutputViewport(val x: Int, val y: Int, val width: Int, val height: Int) {
    companion object {
        fun fit(composition: OutputSize, actual: OutputSize): OutputViewport {
            val scale = minOf(actual.width.toDouble()/composition.width,actual.height.toDouble()/composition.height)
            val width = (composition.width*scale).toInt().coerceIn(1,actual.width)
            val height = (composition.height*scale).toInt().coerceIn(1,actual.height)
            return OutputViewport((actual.width-width)/2,(actual.height-height)/2,width,height)
        }
    }
}

/** Internal output binding; does not expand the public RenderTarget contract. */
internal interface ViewportRenderTarget {
    fun bindOutput()
}
