package com.veycad.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PreviewUploadBufferDeviceTest {
    @Test fun owned_native_storage_is_admitted_and_explicitly_freed_without_escaping_views() {
        val budget = PreviewMemoryBudget()
        val owner = PreviewAllocationOwner(budget) {}
        val storage = PreviewUploadBuffer.create(owner, 32)
        assertTrue(budget.usedBytes >= 32)
        storage.setVertices(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
        storage.close()
        assertEquals(0L, budget.usedBytes)
        storage.close()
        assertThrows(IllegalStateException::class.java) { storage.setVertices(floatArrayOf(0f)) }
        owner.close()
    }

    @Test fun invalid_capacity_and_exhausted_budget_reject_before_native_storage() {
        val budget = PreviewMemoryBudget(1)
        val owner = PreviewAllocationOwner(budget) {}
        assertThrows(IllegalArgumentException::class.java) { PreviewUploadBuffer.create(owner, 0) }
        assertThrows(IllegalArgumentException::class.java) { PreviewUploadBuffer.create(owner, -1) }
        assertThrows(IllegalStateException::class.java) { PreviewUploadBuffer.create(owner, 32) }
        assertEquals(0L, budget.usedBytes)
    }

    @Test fun native_boundary_rejects_forged_and_already_freed_handles() {
        // Exercise the private JNI boundary, without exposing raw handles in the production API.
        val type = Class.forName("com.veycad.app.PreviewNativeMemory")
        val instance = type.getDeclaredField("INSTANCE").apply { isAccessible = true }.get(null)
        val allocate = type.getDeclaredMethod("allocate", Int::class.javaPrimitiveType).apply { isAccessible = true }
        val free = type.getDeclaredMethod("free", Long::class.javaPrimitiveType).apply { isAccessible = true }
        val view = type.getDeclaredMethod("view", Long::class.javaPrimitiveType).apply { isAccessible = true }
        fun rejected(handle: Long) {
            val failure = assertThrows(java.lang.reflect.InvocationTargetException::class.java) {
                free.invoke(instance, handle)
            }
            assertTrue(failure.cause is IllegalStateException)
        }
        rejected(-1L)
        rejected(Long.MAX_VALUE)
        val handle = allocate.invoke(instance, 32) as Long
        free.invoke(instance, handle)
        rejected(handle)
        val missing = assertThrows(java.lang.reflect.InvocationTargetException::class.java) { view.invoke(instance, handle) }
        assertTrue(missing.cause is IllegalStateException)
    }

    @Test fun failed_native_disposal_keeps_charge_and_retries_the_same_owned_storage() {
        val budget = PreviewMemoryBudget()
        val owner = PreviewAllocationOwner(budget) {}
        val storage = PreviewUploadBuffer.create(owner, 32)
        val handleField = PreviewUploadBuffer::class.java.getDeclaredField("handle").apply { isAccessible = true }
        val actualHandle = handleField.getLong(storage)
        val charged = budget.usedBytes
        try {
            handleField.setLong(storage, -1L)
            assertThrows(IllegalStateException::class.java) { storage.close() }
            assertEquals(charged, budget.usedBytes)
            assertThrows(IllegalStateException::class.java) { storage.setVertices(floatArrayOf(1f)) }
        } finally {
            handleField.setLong(storage, actualHandle)
            owner.close()
        }
        assertEquals(0L, budget.usedBytes)
        storage.close()
    }
}
