package com.example.autoedit

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class CaptureDiagnosticsTest {
    @Test fun wrappedFailureRetainsTypesWithoutPrivateMessagesOrPaths() {
        val root = IOException("/storage/private-person/source.mov could not be read")
        val fields = CaptureDiagnostics.failureFields(IllegalStateException("private-person", root))
        assertEquals(IllegalStateException::class.java.name, fields["exception"])
        assertEquals(IOException::class.java.name, fields["root_exception"])
        assertEquals("2", fields["cause_depth"])
        assertEquals("false", fields["cause_truncated"])
        assertFalse(fields.values.any { it.contains("private-person") || it.contains("source.mov") })
    }

    @Test fun materialCodeSurvivesWrapperWithoutRejectingMovementAsStatic() {
        val fields = CaptureDiagnostics.failureFields(RuntimeException(MaterialRejectedException(
            "insufficient_motion_evidence", "Private source.mov: movement could not be measured")))
        assertEquals("insufficient_motion_evidence", fields["material_code"])
        assertEquals(MaterialRejectedException::class.java.name, fields["root_exception"])
        assertFalse(fields.values.any { it.contains("source.mov") })
    }

    @Test fun cyclicCauseChainIsBoundedAndMarkedAsTruncated() {
        val outer = RuntimeException("outer")
        val inner = IOException("inner")
        outer.initCause(inner)
        inner.initCause(outer)
        val fields = CaptureDiagnostics.failureFields(outer)
        assertEquals("2", fields["cause_depth"])
        assertEquals("true", fields["cause_truncated"])
    }
}
