package com.veycad.app

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** Each project owns a physical copy, including captures shared with another project. */
class ProjectAssetStore(projectDirectory: File) {
    private val directory = ownedChild(projectDirectory, "sources")

    fun import(source: File, kind: ProjectAsset.Kind, durationUs: Long): ProjectAsset {
        require(source.isFile && durationUs > 0)
        directory.mkdirs()
        val temp = File.createTempFile(".import-", ".tmp", directory)
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            source.inputStream().use { input ->
                FileOutputStream(temp).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        digest.update(buffer, 0, count)
                        output.write(buffer, 0, count)
                    }
                    output.fd.sync()
                }
            }
            val hash = digest.digest().joinToString("") { "%02x".format(it) }
            val name = "$hash.${kind.name.lowercase()}"
            val target = ownedChild(directory, name)
            // Replacing identical content also repairs a damaged prior import, before references publish.
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            return ProjectAsset("${kind.name.lowercase()}-$hash", name, kind, durationUs, hash, source.name)
        } finally { temp.delete() }
    }

    fun resolve(asset: ProjectAsset): File = ownedChild(directory, asset.fileName)
}
