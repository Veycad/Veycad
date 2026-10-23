package com.veycad.app

import org.junit.Assert.*
import org.junit.Test

class NativeResourcesTest {
    @Test fun failedConstructionReleasesEveryEarlierResourceInReverseOrder() {
        val live = linkedSetOf("encoder", "surface", "muxer")
        val released = mutableListOf<String>()
        val cause = IllegalStateException("configure failed")
        val cleanup = IllegalArgumentException("surface release failed")
        assertSame(cause, assertThrows(IllegalStateException::class.java) {
            NativeResources().use { resources ->
                live.toList().forEach { name ->
                    resources.own(name) {
                        live.remove(it)
                        released += it
                        if (it == "surface") throw cleanup
                    }
                }
                throw cause
            }
        })
        assertTrue(live.isEmpty())
        assertEquals(listOf("muxer", "surface", "encoder"), released)
        assertSame(cleanup, cause.suppressed.single())
    }

    @Test fun transferredResourcesSurviveConstructionAndCloseIsIdempotent() {
        var releases = 0
        val resources = NativeResources()
        resources.use {
            it.own(7) { releases++ }
            it.transfer()
        }
        resources.close()
        assertEquals(0, releases)
        val active = NativeResources()
        active.own(8) { releases++ }
        active.close()
        active.close()
        assertEquals(1, releases)
    }

    @Test fun cleanupWithoutAnEarlierFailureReportsTheFirstErrorAndContinues() {
        val first = IllegalStateException("stop failed")
        val second = IllegalArgumentException("release failed")
        val resources = NativeResources()
        resources.defer { throw second }
        resources.defer { throw first }
        assertSame(first, assertThrows(IllegalStateException::class.java, resources::close))
        assertSame(second, first.suppressed.single())
        resources.close()
    }
}
