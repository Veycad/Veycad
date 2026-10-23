package com.veycad.app

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Collections
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/** Small pointer only: display names are metadata, never filesystem paths. */
internal object SourceDraftManifest {
    const val VERSION = 1
    const val MAX_SOURCES = 20
    const val MAX_BYTES = 65_536
    enum class Origin { IMPORT, LEGACY }
    data class Entry(val fileName: String, val displayName: String, val length: Long)
    class Snapshot(val batchId: UUID, val origin: Origin, entries: List<Entry>) {
        val entries: List<Entry> = Collections.unmodifiableList(ArrayList(entries))
        override fun equals(other: Any?) = other is Snapshot && batchId == other.batchId && origin == other.origin && entries == other.entries
        override fun hashCode() = 31 * (31 * batchId.hashCode() + origin.hashCode()) + entries.hashCode()
        override fun toString() = "Snapshot($batchId, $origin, $entries)"
    }
    fun encode(snapshot: Snapshot): ByteArray {
        validate(snapshot)
        return JSONObject().apply {
            put("version", VERSION); put("batchId", snapshot.batchId.toString())
            put("origin", snapshot.origin.name.lowercase(java.util.Locale.ROOT))
            put("sources", JSONArray().apply { snapshot.entries.forEach { e ->
                put(JSONObject().put("fileName", e.fileName).put("displayName", e.displayName).put("length", e.length))
            } })
        }.toString().toByteArray(Charsets.UTF_8).also { require(it.size <= MAX_BYTES) { "Слишком длинные имена исходников" } }
    }
    fun decode(bytes: ByteArray): Snapshot {
        require(bytes.size <= MAX_BYTES)
        val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        val tokens = JSONTokener(text)
        val json = tokens.nextValue() as? JSONObject ?: error("Invalid source manifest")
        require(tokens.nextClean() == '\u0000')
        require(json.keys().asSequence().toSet() == setOf("version", "batchId", "origin", "sources"))
        require(integer(json.get("version")) == VERSION.toLong())
        val id = uuid(json.get("batchId") as? String ?: error("Invalid batch UUID"))
        val origin = when (json.get("origin")) { "import" -> Origin.IMPORT; "legacy" -> Origin.LEGACY; else -> error("Invalid origin") }
        val sources = json.get("sources") as? JSONArray ?: error("Invalid sources")
        require(sources.length() <= MAX_SOURCES)
        val entries = (0 until sources.length()).map { index ->
            val row = sources.get(index) as? JSONObject ?: error("Invalid source entry")
            require(row.keys().asSequence().toSet() == setOf("fileName", "displayName", "length"))
            Entry(row.get("fileName") as? String ?: error("Invalid path"),
                row.get("displayName") as? String ?: error("Invalid display name"), integer(row.get("length")))
        }
        return Snapshot(id, origin, entries).also(::validate)
    }
    private fun integer(value: Any): Long {
        require(value is Int || value is Long) { "Expected integer" }
        return (value as Number).toLong()
    }
    internal fun uuid(value: String): UUID = UUID.fromString(value).also { require(it.toString() == value) }
    internal fun directName(name: String): Boolean = name.isNotBlank() && name != "." && name != ".." &&
        !name.endsWith('.') && !name.endsWith(' ') && name.none { it == '/' || it == '\\' || it == ':' || it.code < 32 }
    private fun validate(snapshot: Snapshot) {
        require(snapshot.entries.size <= MAX_SOURCES && snapshot.entries.map { it.fileName }.distinct().size == snapshot.entries.size)
        snapshot.entries.forEachIndexed { i, e ->
            require(directName(e.fileName) && e.length > 0)
            if (snapshot.origin == Origin.IMPORT) require(e.fileName == "source-$i-${snapshot.batchId}.mp4")
        }
    }
}
