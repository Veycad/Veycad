package com.veycad.app

import android.app.Activity
import android.net.Uri
import android.os.Bundle
import android.content.Intent

/** UI-test-only receiving screen: performs an actual ContentResolver read, without gallery copying.
 * Same APK/UID; the integration test also checks the outgoing grant, but not cross-UID enforcement.
 */
class ShareReadActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            @Suppress("DEPRECATION")
            val uri = requireNotNull(intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
            receivedUri = uri
            receivedBytes = requireNotNull(contentResolver.openInputStream(uri)).use { it.readBytes() }
        } catch (error: Exception) {
            failure = error
        } finally { finish() }
    }

    companion object {
        @Volatile var receivedBytes: ByteArray? = null
            private set
        @Volatile var receivedUri: Uri? = null
            private set
        @Volatile var failure: Exception? = null
            private set
        fun reset() { receivedBytes = null; receivedUri = null; failure = null }
    }
}
