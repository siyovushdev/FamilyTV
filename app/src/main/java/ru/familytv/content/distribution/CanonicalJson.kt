package ru.familytv.content.distribution

import org.json.JSONArray
import org.json.JSONObject
import java.util.TreeMap

/**
 * Canonical JSON matching Python tools/signing/canonical.py:
 * sorted object keys, compact separators, UTF-8, ensure_ascii=False.
 */
object CanonicalJson {
    fun dumps(value: Any?): String = write(value)

    fun bytes(value: Any?): ByteArray = dumps(value).toByteArray(Charsets.UTF_8)

    /** Convert org.json types into maps/lists suitable for dumps(). */
    fun fromOrgJson(value: Any?): Any? = when (value) {
        null, JSONObject.NULL -> null
        is JSONObject -> {
            val map = LinkedHashMap<String, Any?>()
            val keys = value.keys().asSequence().toList().sorted()
            for (k in keys) map[k] = fromOrgJson(value.get(k))
            map
        }
        is JSONArray -> (0 until value.length()).map { fromOrgJson(value.get(it)) }
        is Number -> {
            val d = value.toDouble()
            if (d.isFinite() && d % 1.0 == 0.0 && d <= Long.MAX_VALUE && d >= Long.MIN_VALUE) {
                d.toLong()
            } else {
                d
            }
        }
        is Boolean, is String -> value
        else -> value.toString()
    }

    private fun write(value: Any?): String = when (value) {
        null -> "null"
        is Boolean -> if (value) "true" else "false"
        is Number -> {
            val d = value.toDouble()
            if (d.isFinite() && d % 1.0 == 0.0 && d <= Long.MAX_VALUE.toDouble() && d >= Long.MIN_VALUE.toDouble()) {
                d.toLong().toString()
            } else {
                val s = java.math.BigDecimal.valueOf(d).stripTrailingZeros().toPlainString()
                s
            }
        }
        is String -> quote(value)
        is Map<*, *> -> {
            val sorted = TreeMap<String, Any?>()
            value.forEach { (k, v) -> sorted[k.toString()] = v }
            sorted.entries.joinToString(",", "{", "}") { (k, v) ->
                quote(k) + ":" + write(v)
            }
        }
        is List<*> -> value.joinToString(",", "[", "]") { write(it) }
        else -> quote(value.toString())
    }

    /** RFC-compliant JSON string without \uXXXX for printable non-ASCII (Python ensure_ascii=False). */
    fun quote(s: String): String {
        val sb = StringBuilder(s.length + 2)
        sb.append('"')
        for (ch in s) {
            when (ch) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> {
                    if (ch.code < 0x20) {
                        sb.append("\\u%04x".format(ch.code))
                    } else {
                        sb.append(ch)
                    }
                }
            }
        }
        sb.append('"')
        return sb.toString()
    }
}
