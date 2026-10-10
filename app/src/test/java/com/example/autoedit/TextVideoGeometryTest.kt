package com.veycad.app
import org.junit.Assert.assertEquals
import org.junit.Test
class TextVideoGeometryTest {
    @Test fun swapsQuarterTurnsOnly() {
        assertEquals(1280 to 720, TextVideoGeometry.oriented(720,1280,90))
        assertEquals(1280 to 720, TextVideoGeometry.oriented(720,1280,270))
        assertEquals(720 to 1280, TextVideoGeometry.oriented(720,1280,180))
    }
}
