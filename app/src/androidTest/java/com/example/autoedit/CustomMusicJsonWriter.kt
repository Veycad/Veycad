package com.veycad.app

import java.io.File
import java.io.Writer
import java.lang.reflect.Array as ReflectArray
import java.lang.reflect.Modifier

/** Test-only JSON encoder. Auxiliary memory depends on nesting, never on the number of pixels. */
internal object CustomMusicJsonWriter {
    const val BUFFER_CHARS = 8 * 1024

    /** Only a previously validated inspector document may be embedded verbatim. */
    data class InspectorDocument(val file: File)

    fun write(file: File, value: Any?) {
        file.bufferedWriter(Charsets.UTF_8, BUFFER_CHARS).use { writeValue(it, value) }
    }

    fun writeValue(writer: Writer, value: Any?) {
        when (value) {
            null -> writer.write("null")
            is String -> string(writer, value)
            is Boolean -> writer.write(value.toString())
            is Number -> {
                require(value !is Float || value.isFinite()) { "Non-finite diagnostic float" }
                require(value !is Double || value.isFinite()) { "Non-finite diagnostic double" }
                writer.write(value.toString())
            }
            is Enum<*> -> string(writer, value.name)
            is InspectorDocument -> value.file.bufferedReader(Charsets.UTF_8, BUFFER_CHARS).use {
                it.copyTo(writer, BUFFER_CHARS)
            }
            is Map<*, *> -> {
                writer.write("{")
                var first = true
                value.forEach { (key, item) ->
                    if (!first) writer.write(",")
                    first = false
                    string(writer, key.toString())
                    writer.write(":")
                    writeValue(writer, item)
                }
                writer.write("}")
            }
            is Iterable<*> -> {
                writer.write("[")
                var first = true
                value.forEach {
                    if (!first) writer.write(",")
                    first = false
                    writeValue(writer, it)
                }
                writer.write("]")
            }
            else -> if (value.javaClass.isArray) {
                writer.write("[")
                repeat(ReflectArray.getLength(value)) { index ->
                    if (index > 0) writer.write(",")
                    // Only the current primitive is boxed, not the complete plane.
                    writeValue(writer, ReflectArray.get(value, index))
                }
                writer.write("]")
            } else {
                require(value.javaClass.name.startsWith("com.veycad.app.")) {
                    "Unsupported diagnostic value: ${value.javaClass.name}"
                }
                writer.write("{")
                value.javaClass.declaredFields.filterNot {
                    Modifier.isStatic(it.modifiers) || it.isSynthetic
                }.sortedBy { it.name }.forEachIndexed { index, field ->
                    if (index > 0) writer.write(",")
                    field.isAccessible = true
                    string(writer, field.name)
                    writer.write(":")
                    writeValue(writer, field.get(value))
                }
                writer.write("}")
            }
        }
    }

    private fun string(writer: Writer, value: String) {
        writer.write("\"")
        value.forEach { char ->
            when (char) {
                '"' -> writer.write("\\\"")
                '\\' -> writer.write("\\\\")
                '\b' -> writer.write("\\b")
                '\u000c' -> writer.write("\\f")
                '\n' -> writer.write("\\n")
                '\r' -> writer.write("\\r")
                '\t' -> writer.write("\\t")
                else -> if (char < ' ' || char in '\uD800'..'\uDFFF') {
                    writer.write("\\u" + char.code.toString(16).padStart(4, '0'))
                } else writer.write(char.code)
            }
        }
        writer.write("\"")
    }
}
