package com.veycad.app

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AnalysisSidecarStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    @Test fun verifiesHashAndTreatsMissingOrCorruptDataAsRegenerable() {
        val dir = temporary.newFolder()
        val store = AnalysisSidecarStore(dir)
        val hash = store.write("mask", byteArrayOf(1, 2, 3))
        assertEquals(hash, store.write("mask", byteArrayOf(1, 2, 3)))
        assertArrayEquals(byteArrayOf(1, 2, 3), store.read(hash))
        dir.listFiles()!!.single().writeBytes(byteArrayOf(4))
        assertNull(store.read(hash))
        dir.listFiles()!!.single().delete()
        assertNull(store.read(hash))
        assertThrows(IllegalArgumentException::class.java) { store.read("../x") }
    }
}
