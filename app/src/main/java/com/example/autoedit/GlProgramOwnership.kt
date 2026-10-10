package com.veycad.app

/** Transfers only a linked program to the session; temporary shaders never escape. */
internal object GlProgramOwnership {
    interface Driver {
        fun createShader(type: Int): Int
        fun compile(shader: Int, source: String)
        fun createProgram(): Int
        fun attach(program: Int, shader: Int)
        fun link(program: Int)
        fun deleteShader(shader: Int)
        fun deleteProgram(program: Int)
    }

    fun create(driver: Driver, vertex: String, fragment: String): Int {
        val shaders = mutableListOf<Int>()
        var program = 0
        try {
            fun compile(type: Int, source: String): Int {
                val shader = driver.createShader(type)
                check(shader != 0) { "Unable to allocate GL shader" }
                shaders += shader
                driver.compile(shader, source)
                return shader
            }
            val vertexShader = compile(0x8B31, vertex)
            val fragmentShader = compile(0x8B30, fragment)
            program = driver.createProgram()
            check(program != 0) { "Unable to allocate GL program" }
            driver.attach(program, vertexShader)
            driver.attach(program, fragmentShader)
            driver.link(program)
            return program
        } catch (error: Throwable) {
            if (program != 0) driver.deleteProgram(program)
            throw error
        } finally {
            shaders.forEach(driver::deleteShader)
        }
    }
}
