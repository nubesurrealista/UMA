package tsuki.util

import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Document

private val NEXT_F_REGEX = Regex(
    """self\.__next_f\.push\(\s*(\[.*])\s*\)\s*;?\s*$""",
    RegexOption.DOT_MATCHES_ALL
)

/**
 * Extracts all Next.js App Router RSC payloads from the document and resolves all
 * React Flight references, returning a single JSONObject containing all resolved data.
 */
fun Document.extractNextJsData(): JSONObject {
    val chunkCache = mutableMapOf<String, String>()
    val modelCache = mutableMapOf<String, JSONObject?>()
    val root = JSONObject()

    select("script:not([src])")
        .mapNotNull { it.data() }
        .filter { "self.__next_f.push" in it }
        .forEach { script ->
            val match = NEXT_F_REGEX.find(script) ?: return@forEach
            val raw = match.groupValues[1]
            val arr = try {
                JSONArray(raw)
            } catch (_: Exception) {
                return@forEach
            }
            if (arr.length() < 2) return@forEach
            val content = arr.optString(1) ?: return@forEach
            extractRscChunks(content, chunkCache, modelCache)
        }

    // Resolve all model objects
    val resolvedModels = modelCache.mapValues { (_, obj) ->
        if (obj != null) resolveRefs(obj, chunkCache, modelCache) else null
    }

    // Combine all resolved objects and arrays into root
    for ((_, obj) in resolvedModels) {
        if (obj != null) {
            when (obj) {
                is JSONObject -> {
                    for (key in obj.keys()) {
                        if (!root.has(key)) {
                            root.put(key, obj.get(key))
                        }
                    }
                }
                is JSONArray -> {
                    // Store resolved lists in _resolved_lists
                    if (!root.has("_resolved_lists")) {
                        root.put("_resolved_lists", JSONArray())
                    }
                    root.getJSONArray("_resolved_lists").put(obj)
                }
            }
        }
    }

    // Also try to parse any chunk strings that might be JSON and add them
    for ((_, chunk) in chunkCache) {
        if (chunk.startsWith("{") || chunk.startsWith("[")) {
            try {
                if (chunk.startsWith("{")) {
                    val parsed = JSONObject(chunk)
                    for (key in parsed.keys()) {
                        if (!root.has(key)) {
                            root.put(key, parsed.get(key))
                        }
                    }
                } else if (chunk.startsWith("[")) {
                    val parsed = JSONArray(chunk)
                    if (!root.has("_chunk_lists")) {
                        root.put("_chunk_lists", JSONArray())
                    }
                    root.getJSONArray("_chunk_lists").put(parsed)
                }
            } catch (_: Exception) {
                // Ignore parsing errors
            }
        }
    }

    return root
}

private fun extractRscChunks(
    body: String,
    chunkCache: MutableMap<String, String>,
    modelCache: MutableMap<String, JSONObject?>
) {
    var pos = 0
    while (pos < body.length) {
        val colonIdx = body.indexOf(':', pos)
        if (colonIdx == -1) break
        val id = body.substring(pos, colonIdx)
        if (id.isEmpty() || !id.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }) {
            pos++
            continue
        }
        pos = colonIdx + 1
        if (pos >= body.length) break

        if (body[pos] == 'T') {
            pos++
            val commaIdx = body.indexOf(',', pos)
            if (commaIdx == -1) break
            val byteLen = body.substring(pos, commaIdx).toIntOrNull(16) ?: break
            pos = commaIdx + 1
            var bytes = 0
            val start = pos
            while (pos < body.length && bytes < byteLen) {
                when {
                    body[pos].code < 0x80 -> bytes += 1
                    body[pos].code < 0x800 -> bytes += 2
                    Character.isHighSurrogate(body[pos]) -> {
                        bytes += 4
                        pos++
                    }
                    else -> bytes += 3
                }
                pos++
            }
            val chunkContent = body.substring(start, pos)
            chunkCache[id] = chunkContent
        } else {
            val (element, end) = parseJsonAt(body, pos)
            if (element != null) {
                modelCache[id] = element
            }
            pos = end
        }
    }
}

private fun parseJsonAt(body: String, start: Int): Pair<JSONObject?, Int> {
    if (start >= body.length) return Pair(null, start)
    var depth = 0
    var inString = false
    var escape = false
    var i = start
    while (i < body.length) {
        val c = body[i++]
        if (escape) {
            escape = false
            continue
        }
        if (c == '\\' && inString) {
            escape = true
            continue
        }
        if (c == '"') {
            inString = !inString
            continue
        }
        if (inString) continue
        when (c) {
            '{', '[' -> depth++
            '}', ']' -> {
                if (--depth == 0) {
                    val json = try {
                        JSONObject(body.substring(start, i))
                    } catch (_: Exception) {
                        null
                    }
                    return Pair(json, i)
                }
            }
        }
        if (depth == 0 && c.isWhitespace()) {
            val json = try {
                JSONObject(body.substring(start, i - 1))
            } catch (_: Exception) {
                null
            }
            return Pair(json, i)
        }
    }
    return Pair(null, i)
}

private fun resolveRefs(
    element: JSONObject,
    chunkCache: Map<String, String>,
    modelCache: Map<String, JSONObject?>
): JSONObject {
    val result = JSONObject()
    for (key in element.keys()) {
        val value = element.get(key)
        result.put(key, resolveValue(value, chunkCache, modelCache, emptySet()))
    }
    return result
}

private fun resolveValue(
    value: Any,
    chunkCache: Map<String, String>,
    modelCache: Map<String, JSONObject?>,
    resolving: Set<String>
): Any {
    return when (value) {
        is JSONObject -> {
            if (value.has("\$ref")) {
                val ref = value.getString("\$ref")
                resolveRef(ref, chunkCache, modelCache, resolving) ?: value
            } else {
                resolveRefs(value, chunkCache, modelCache)
            }
        }
        is JSONArray -> {
            val arr = JSONArray()
            for (i in 0 until value.length()) {
                arr.put(resolveValue(value.get(i), chunkCache, modelCache, resolving))
            }
            arr
        }
        is String -> {
            if (value.startsWith("$") && value.length > 1) {
                val str = value
                when {
                    str == "\$undefined" -> JSONObject.NULL
                    str == "\$Infinity" || str == "\$-Infinity" || str == "\$NaN" || str == "\$-0" ->
                        str.substring(1)
                    str[1] == '$' -> str.substring(1)
                    str[1] == 'D' -> str.substring(2)
                    str[1] == 'n' -> str.substring(2)
                    str[1] == 'Q' -> resolveMapRef(str.substring(2), chunkCache, modelCache, resolving) ?: str
                    str[1] == 'W' -> resolveSetRef(str.substring(2), chunkCache, modelCache, resolving) ?: str
                    else -> resolveModelRef(str.substring(1), chunkCache, modelCache, resolving) ?: str
                }
            } else {
                value
            }
        }
        else -> value
    }
}

private fun resolveRef(
    ref: String,
    chunkCache: Map<String, String>,
    modelCache: Map<String, JSONObject?>,
    resolving: Set<String>
): Any? {
    val segments = ref.split(':')
    val id = segments[0]
    if (id in resolving) return null
    val guard = resolving + id

    if (segments.size == 1) {
        val chunk = chunkCache[id]
        if (chunk != null) {
            try {
                return JSONObject(chunk)
            } catch (_: Exception) {
                return chunk
            }
        }
    }

    val base = modelCache[id] ?: return null
    var current: Any = base

    for (i in 1 until segments.size) {
        when (current) {
            is JSONObject -> {
                current = current.opt(segments[i]) ?: return null
            }
            is JSONArray -> {
                val index = segments[i].toIntOrNull()
                if (index != null && index < current.length()) {
                    current = current.get(index)
                } else {
                    return null
                }
            }
            else -> return null
        }
    }
    return resolveValue(current, chunkCache, modelCache, guard)
}

private fun resolveModelRef(
    reference: String,
    chunkCache: Map<String, String>,
    modelCache: Map<String, JSONObject?>,
    resolving: Set<String>
): Any? {
    val segments = reference.split(':')
    val id = segments[0]

    if (segments.size == 1) {
        val chunk = chunkCache[id]
        if (chunk != null) {
            try {
                return JSONObject(chunk)
            } catch (_: Exception) {
                return chunk
            }
        }
    }

    if (id in resolving) return null
    val guard = resolving + id
    var current: Any = modelCache[id] ?: return null

    for (i in 1 until segments.size) {
        val seg = segments[i]
        when (current) {
            is JSONObject -> {
                current = current.opt(seg) ?: return null
            }
            is JSONArray -> {
                if (current.length() >= 4 && current.opt(0) == "$") {
                    current = when (seg) {
                        "type" -> current.opt(1)
                        "key" -> current.opt(2)
                        "props" -> current.opt(3)
                        else -> {
                            val idx = seg.toIntOrNull()
                            if (idx != null && idx < current.length()) current.opt(idx) else null
                        }
                    } ?: return null
                } else {
                    val idx = seg.toIntOrNull()
                    if (idx != null && idx < current.length()) {
                        current = current.get(idx)
                    } else {
                        return null
                    }
                }
            }
            else -> return null
        }
        // Resolve any pending reference in the current node
        current = when (current) {
            is String -> {
                if (current.startsWith("$") && current.length > 1) {
                    resolveValue(current, chunkCache, modelCache, guard)
                } else current
            }
            else -> current
        }
    }
    return resolveValue(current, chunkCache, modelCache, guard)
}

private fun resolveMapRef(
    id: String,
    chunkCache: Map<String, String>,
    modelCache: Map<String, JSONObject?>,
    resolving: Set<String>
): JSONObject? {
    if (id in resolving) return null
    val entries = modelCache[id] as? JSONArray ?: return null
    val resolvedEntries = resolveValue(entries, chunkCache, modelCache, resolving + id) as? JSONArray ?: return null
    val result = JSONObject()
    for (i in 0 until resolvedEntries.length()) {
        val pair = resolvedEntries.optJSONArray(i) ?: continue
        if (pair.length() < 2) continue
        val key = pair.optString(0)
        val value = pair.opt(1)
        result.put(key, value)
    }
    return result
}

private fun resolveSetRef(
    id: String,
    chunkCache: Map<String, String>,
    modelCache: Map<String, JSONObject?>,
    resolving: Set<String>
): JSONArray? {
    if (id in resolving) return null
    val values = modelCache[id] as? JSONArray ?: return null
    return resolveValue(values, chunkCache, modelCache, resolving + id) as? JSONArray
}
