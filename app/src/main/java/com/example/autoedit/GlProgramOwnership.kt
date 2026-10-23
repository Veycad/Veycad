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
        var program = 0
        try {
            return NativeResources().use { resources ->
                fun compile(type: Int, source: String): Int {
                    val shader = driver.createShader(type)
                    check(shader != 0) { "Unable to allocate GL shader" }
                    resources.own(shader, driver::deleteShader)
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
                program
            }
        } catch (error: Throwable) {
            NativeResources().use { resources ->
                if (program != 0) resources.defer { driver.deleteProgram(program) }
                throw error
            }
        }
    }
}
