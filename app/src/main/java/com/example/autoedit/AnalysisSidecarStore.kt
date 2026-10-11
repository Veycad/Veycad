package com.veycad.app

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** Replaceable analysis only. Source media never enters this directory. */
class AnalysisSidecarStore(private val directory: File) {
    fun write(kind: String, data: ByteArray): String {
        require(kind.matches(Regex("[a-z-]{1,40}")))
        require(data.size <= MAX_BYTES)
        val snapshot = data.copyOf()
        val hash = contentHash(snapshot)
        directory.mkdirs()
        val target = ownedChild(directory, hash)
        if (read(hash) == null) atomicWrite(target, snapshot)
        return hash
    }

    fun read(hash: String): ByteArray? {
        require(hash.matches(Regex("[a-f0-9]{64}")))
        val file = ownedChild(directory, hash)
        return try {
            if (!file.isFile || file.length() > MAX_BYTES) null
            else file.inputStream().use { input ->
                val bytes = input.readBytesBounded(MAX_BYTES)
                bytes.takeIf { contentHash(it) == hash }
            }
        } catch (_: IOException) { null }
    }

    companion object { internal const val MAX_BYTES = 16 * 1024 * 1024 }
}

internal fun contentHash(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

internal fun ownedChild(directory: File, name: String): File {
    require(name.isNotBlank() && name != "." && name != ".." && !name.endsWith('.') && !name.endsWith(' '))
    require(name.none { it == '/' || it == '\\' || it == ':' || it.code < 32 })
    val parent = directory.canonicalFile
    val child = File(parent, name)
    require(child.canonicalFile.parentFile == parent) { "Path leaves its owning directory" }
    require(!Files.isSymbolicLink(child.toPath())) { "Project entries cannot be symbolic links" }
    return child
}

/** No non-atomic fallback: on unsupported filesystems the previous pointer survives. */
internal fun atomicWrite(target: File, bytes: ByteArray, beforeReplace: () -> Unit = {}) {
    target.parentFile!!.mkdirs()
    val temp = File.createTempFile(".pending-", ".tmp", target.parentFile)
    try {
        FileOutputStream(temp).use { output -> output.write(bytes); output.fd.sync() }
        beforeReplace()
        Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } finally { temp.delete() }
}

internal fun java.io.InputStream.readBytesBounded(limit: Int): ByteArray {
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val size = read(buffer)
        if (size < 0) break
        require(output.size() <= limit - size) { "File exceeds storage limit" }
        output.write(buffer, 0, size)
    }
    return output.toByteArray()
}
