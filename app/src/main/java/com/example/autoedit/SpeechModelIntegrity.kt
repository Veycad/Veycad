package com.veycad.app

import java.io.File
import java.security.MessageDigest

internal object SpeechModelIntegrity {
    fun matches(file:File,sha256:String,size:Long,checkCancelled:()->Unit):Boolean {
        if(!file.isFile || file.length()!=size) return false
        val digest=MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val bytes=ByteArray(128*1024)
            while(true) { checkCancelled(); val n=input.read(bytes); if(n<0) break; digest.update(bytes,0,n) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }==sha256
    }
}
