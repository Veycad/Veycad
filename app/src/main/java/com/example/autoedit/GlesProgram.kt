package com.veycad.app

import android.opengl.GLES20

internal object GlesProgram : GlProgramOwnership.Driver {
    fun create(vertex: String, fragment: String): Int = GlProgramOwnership.create(this, vertex, fragment)
    override fun createShader(type: Int): Int = GLES20.glCreateShader(type)
    override fun compile(shader: Int, source: String) {
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        check(status[0] == GLES20.GL_TRUE) { "Shader compile failed: ${GLES20.glGetShaderInfoLog(shader)}" }
    }
    override fun createProgram(): Int = GLES20.glCreateProgram()
    override fun attach(program: Int, shader: Int) = GLES20.glAttachShader(program, shader)
    override fun link(program: Int) {
        GLES20.glLinkProgram(program)
        val status = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
        check(status[0] == GLES20.GL_TRUE) { "Program link failed: ${GLES20.glGetProgramInfoLog(program)}" }
        // Deleting an attached shader only marks it for deletion. Detach after linking so its
        // storage is actually reclaimed by GlProgramOwnership's finally block.
        val shaders = IntArray(2)
        val count = IntArray(1)
        GLES20.glGetAttachedShaders(program, shaders.size, count, 0, shaders, 0)
        repeat(count[0]) { GLES20.glDetachShader(program, shaders[it]) }
    }
    override fun deleteShader(shader: Int) = GLES20.glDeleteShader(shader)
    override fun deleteProgram(program: Int) = GLES20.glDeleteProgram(program)
}
