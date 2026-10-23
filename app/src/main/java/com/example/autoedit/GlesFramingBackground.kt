package com.veycad.app

import android.opengl.GLES11Ext
import android.opengl.GLES20
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.ceil

/** Borrowed GL names: the compositor's existing allocation owner releases every target/program. */
internal class GlesFramingBackground(width: Int, height: Int,
    program: (String, String) -> Int, target: (Int, Int) -> Target) {
    data class Target(val framebuffer: Int, val texture: Int, val width: Int, val height: Int)
    private val w = maxOf(1, (width + 1) / 2)
    private val h = maxOf(1, (height + 1) / 2)
    private val a = target(w, h)
    private val b = target(w, h)
    private val atlas = target(w * 2, h)
    private val sourceProgram = program(VERTEX, SOURCE)
    private val blurProgram = program(VERTEX, BLUR)
    private val positions = buffer(floatArrayOf(-1f,-1f, 1f,-1f, -1f,1f, 1f,1f))
    private val coordinates = buffer(floatArrayOf(0f,0f, 1f,0f, 0f,1f, 1f,1f))
    private val sigma = FramingPlan.BLUR_SIGMA_FRACTION * minOf(w, h)
    private val radius = ceil(3 * sigma.toDouble()).toInt()
    init { require(radius in 1..36) { "Fit background supports project short edges up to 1080" } }
    val texture: Int get() = atlas.texture
    val atlasWidth: Int get() = atlas.width
    val atlasHeight: Int get() = atlas.height

    fun draw(incomingTexture: Int, incomingMatrix: FloatArray, incoming: FramingPlan.Sample?,
        outgoingTexture: Int, outgoingMatrix: FloatArray, outgoing: FramingPlan.Sample?) {
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, atlas.framebuffer)
        GLES20.glViewport(0, 0, atlas.width, atlas.height)
        GLES20.glClearColor(0f, 0f, 0f, 1f); GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        role(incomingTexture, incomingMatrix, incoming, 0)
        role(outgoingTexture, outgoingMatrix, outgoing, 1)
        check(GLES20.glGetError() == GLES20.GL_NO_ERROR) { "Fit background rendering failed" }
    }

    private fun role(texture: Int, matrix: FloatArray, sample: FramingPlan.Sample?, page: Int) {
        val crop = sample?.backgroundSource ?: return
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, a.framebuffer)
        GLES20.glViewport(0, 0, w, h); GLES20.glUseProgram(sourceProgram)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(sourceProgram, "uSource"), 0)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(sourceProgram, "uMatrix"), 1, false, matrix, 0)
        GLES20.glUniform4f(GLES20.glGetUniformLocation(sourceProgram, "uCrop"),
            crop.left, crop.top, crop.right - crop.left, crop.bottom - crop.top)
        quad(sourceProgram)
        blur(a.texture, b.framebuffer, 0, 1f, 0f)
        blur(b.texture, atlas.framebuffer, page * w, 0f, 1f)
    }

    private fun blur(texture: Int, framebuffer: Int, left: Int, dx: Float, dy: Float) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffer)
        GLES20.glViewport(left, 0, w, h); GLES20.glUseProgram(blurProgram)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(blurProgram, "uSource"), 0)
        GLES20.glUniform2f(GLES20.glGetUniformLocation(blurProgram, "uStep"), dx / w, dy / h)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(blurProgram, "uSigma"), sigma)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(blurProgram, "uRadius"), radius.toFloat())
        quad(blurProgram)
    }

    private fun quad(program: Int) {
        val p = GLES20.glGetAttribLocation(program, "aPosition")
        val uv = GLES20.glGetAttribLocation(program, "aTexCoord")
        GLES20.glEnableVertexAttribArray(p); GLES20.glEnableVertexAttribArray(uv)
        positions.position(0); coordinates.position(0)
        GLES20.glVertexAttribPointer(p, 2, GLES20.GL_FLOAT, false, 8, positions)
        GLES20.glVertexAttribPointer(uv, 2, GLES20.GL_FLOAT, false, 8, coordinates)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(p); GLES20.glDisableVertexAttribArray(uv)
    }

    companion object {
        private fun buffer(values: FloatArray) = ByteBuffer.allocateDirect(values.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(values); position(0) }
        private const val VERTEX = "attribute vec2 aPosition,aTexCoord; varying vec2 vUv; void main(){gl_Position=vec4(aPosition,0.,1.);vUv=aTexCoord;}"
        private const val SOURCE = """#extension GL_OES_EGL_image_external : require
precision mediump float;
uniform samplerExternalOES uSource; uniform mat4 uMatrix; uniform vec4 uCrop; varying vec2 vUv;
void main(){vec2 p=uCrop.xy+vUv*uCrop.zw;gl_FragColor=texture2D(uSource,(uMatrix*vec4(p,0.,1.)).xy);}
"""
        private const val BLUR = """precision mediump float;
uniform sampler2D uSource; uniform vec2 uStep; uniform float uSigma,uRadius; varying vec2 vUv;
void main(){vec4 c=texture2D(uSource,vUv);float total=1.;
for(int i=1;i<=36;i++){float x=float(i);if(x<=uRadius){float weight=exp(-.5*x*x/(uSigma*uSigma));
c+=(texture2D(uSource,vUv-uStep*x)+texture2D(uSource,vUv+uStep*x))*weight;total+=2.*weight;}}
gl_FragColor=c/total;}
"""
    }
}
