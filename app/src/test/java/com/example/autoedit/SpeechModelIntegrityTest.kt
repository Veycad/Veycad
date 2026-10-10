package com.veycad.app

import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder

class SpeechModelIntegrityTest {
    @get:Rule val folder=TemporaryFolder()
    @Test fun equalSizeAndHashInNameDoNotHideCorruptedCacheBytes() {
        val sha="ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
        val file=folder.newFile("$sha.bin").apply { writeText("abc") }
        assertTrue(SpeechModelIntegrity.matches(file,sha,3,{}))
        file.writeText("abd")
        assertFalse(SpeechModelIntegrity.matches(file,sha,3,{}))
    }
}
