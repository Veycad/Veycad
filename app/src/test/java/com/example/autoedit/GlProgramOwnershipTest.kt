package com.veycad.app

import org.junit.Assert.*
import org.junit.Test

class GlProgramOwnershipTest {
    private class Driver : GlProgramOwnership.Driver {
        val shaders = mutableSetOf<Int>()
        val programs = mutableSetOf<Int>()
        var next = 1
        var failCompile = 0
        var failLink = false
        override fun createShader(type: Int): Int = next++.also { shaders += it }
        override fun compile(shader: Int, source: String) { check(shader != failCompile) }
        override fun createProgram(): Int = next++.also { programs += it }
        override fun attach(program: Int, shader: Int) { check(shader in shaders) }
        override fun link(program: Int) { check(!failLink) }
        override fun deleteShader(shader: Int) { check(shaders.remove(shader)) }
        override fun deleteProgram(program: Int) { check(programs.remove(program)) }
    }

    @Test fun successfulLinkReleasesBothShadersAndTransfersProgram() {
        val driver = Driver()
        val program = GlProgramOwnership.create(driver, "vertex", "fragment")
        assertTrue(driver.shaders.isEmpty())
        assertEquals(setOf(program), driver.programs)
    }

    @Test fun failureAtEitherCompileStageReleasesEveryAllocatedObject() {
        for (stage in 1..2) {
            val driver = Driver().apply { failCompile = stage }
            assertThrows(IllegalStateException::class.java) {
                GlProgramOwnership.create(driver, "vertex", "fragment")
            }
            assertTrue(driver.shaders.isEmpty())
            assertTrue(driver.programs.isEmpty())
        }
    }

    @Test fun linkFailureReleasesProgramAndShaders() {
        val driver = Driver().apply { failLink = true }
        assertThrows(IllegalStateException::class.java) {
            GlProgramOwnership.create(driver, "vertex", "fragment")
        }
        assertTrue(driver.shaders.isEmpty())
        assertTrue(driver.programs.isEmpty())
    }
}
