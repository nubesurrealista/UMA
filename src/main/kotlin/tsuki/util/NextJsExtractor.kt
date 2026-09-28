package tsuki.util

import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Document

/**
 * Extracts Next.js App Router RSC payloads from the document and resolves
 * React Flight references, returning a combined JSONObject.
 */
fun Document.extractNextJsData(): JSONObject {
    val chunkCache = mutableMapOf<String, String>()
    val modelCache = mutableMapOf<String, Any?>()
    val root = JSONObject()

    select("script:not([src])")
        .mapNotNull { it.data() }
        .filter { "self.__next_f.push" in it }
        .forEach { script ->
            val arrays = extractNextFArraysFromScript(script)
            for (arr in arrays) {
                if (arr.length() >= 2) {
                    val content = arr.optString(1)
                    if (!content.isNullOrEmpty()) {
                        extractRscChunks(content, chunkCache, modelCache)
                    }
                }
            }
        }

    val resolvedModels = modelCache.mapValues { (_, obj) ->
        if (obj != null) resolveValue(obj, chunkCache, modelCache, emptySet()) else null
    }

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
                    if (!root.has("_resolved_lists")) {
                        root.put("_resolved_lists", JSONArray())
                    }
                    root.getJSONArray("_resolved_lists").put(obj)
                }
            }
        }
    }

    for ((_, chunk) in chunkCache) {
        val trimmed = chunk.trim()
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            try {
                if (trimmed.startsWith("{")) {
                    val parsed = JSONObject(trimmed)
                    for (key in parsed.keys()) {
                        if (!root.has(key)) {
                            root.put(key, parsed.get(key))
                        }
                    }
                } else if (trimmed.startsWith("[")) {
                    val parsed = JSONArray(trimmed)
                    if (!root.has("_chunk_lists")) {
                        root.put("_chunk_lists", JSONArray())
                    }
                    root.getJSONArray("_chunk_lists").put(parsed)
                }
            } catch (_: Exception) {
            }
        }
    }

    return root
}

private fun extractNextFArraysFromScript(script: String): List<JSONArray> {
    val arrays = mutableListOf<JSONArray>()
    val pushPattern = "self.__next_f.push"
    var searchIdx = 0

    while (searchIdx < script.length) {
        val pushIdx = script.indexOf(pushPattern, searchIdx)
        if (pushIdx == -1) break

        var i = pushIdx + pushPattern.length
        while (i < script.length && script[i] != '[') {
            i++
        }
        if (i >= script.length) {
            searchIdx = pushIdx + pushPattern.length
            continue
        }

        val bracketStart = i
        var bracketCount = 0
        var inString = false
        var escape = false
        var j = bracketStart

        while (j < script.length) {
            val c = script[j]
            if (escape) {
                escape = false
                j++
                continue
            }
            if (c == '\\' && inString) {
                escape = true
                j++
                continue
            }
            if (c == '"' && !escape) {
                inString = !inString
                j++
                continue
            }
            if (!inString) {
                if (c == '[') {
                    bracketCount++
                } else if (c == ']') {
                    bracketCount--
                    if (bracketCount == 0) {
                        val arrayStr = script.substring(bracketStart, j + 1)
                        try {
                            arrays.add(JSONArray(arrayStr))
                        } catch (_: Exception) {
                        }
                        searchIdx = j + 1
                        break
                    }
                }
            }
            j++
        }

        if (bracketCount != 0) {
            searchIdx = bracketStart + 1
        }
    }
    return arrays
}

private fun extractRscChunks(
    body: String,
    chunkCache: MutableMap<String, String>,
    modelCache: MutableMap<String, Any?>
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

private fun parseJsonAt(body: String, start: Int): Pair<Any?, Int> {
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
                    val raw = body.substring(start, i)
                    val parsed = parseJsonOrRaw(raw)
                    return Pair(parsed, i)
                }
            }
        }
        if (depth == 0 && c.isWhitespace()) {
            val raw = body.substring(start, i - 1)
            val parsed = parseJsonOrRaw(raw)
            return Pair(parsed, i)
        }
    }
    return Pair(null, i)
}

private fun parseJsonOrRaw(content: String): Any? {
    val trimmed = content.trim()
    return try {
        if (trimmed.startsWith("{")) {
            JSONObject(trimmed)
        } else if (trimmed.startsWith("[")) {
            JSONArray(trimmed)
        } else {
            null
        }
    } catch (_: Exception) {
        null
    }
}

private fun resolveValue(
    value: Any?,
    chunkCache: Map<String, String>,
    modelCache: Map<String, Any?>,
    resolving: Set<String>
): Any? {
    if (value == null || value == JSONObject.NULL) return null
    return when (value) {
        is JSONObject -> {
            if (value.has("\$ref")) {
                val ref = value.getString("\$ref")
                resolveRef(ref, chunkCache, modelCache, resolving) ?: value
            } else {
                val result = JSONObject()
                for (key in value.keys()) {
                    val v = value.get(key)
                    val resolved = resolveValue(v, chunkCache, modelCache, resolving)
                    result.put(key, resolved ?: JSONObject.NULL)
                }
                result
            }
        }
        is JSONArray -> {
            val arr = JSONArray()
            for (i in 0 until value.length()) {
                val v = value.opt(i)
                val resolved = resolveValue(v, chunkCache, modelCache, resolving)
                arr.put(resolved ?: JSONObject.NULL)
            }
            arr
        }
        is String -> {
            if (value.startsWith("$") && value.length > 1) {
                when {
                    value == "\$undefined" -> JSONObject.NULL
                    value == "\$Infinity" || value == "\$-Infinity" || value == "\$NaN" || value == "\$-0" ->
                        value.substring(1)
                    value[1] == '$' -> value.substring(1)
                    value[1] == 'D' -> value.substring(2)
                    value[1] == 'n' -> value.substring(2)
                    value[1] == 'Q' -> resolveMapRef(value.substring(2), chunkCache, modelCache, resolving) ?: value
                    value[1] == 'W' -> resolveSetRef(value.substring(2), chunkCache, modelCache, resolving) ?: value
                    else -> resolveModelRef(value.substring(1), chunkCache, modelCache, resolving) ?: value
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
    modelCache: Map<String, Any?>,
    resolving: Set<String>
): Any? {
    val segments = ref.split(':')
    val id = segments[0]
    if (id in resolving) return null
    val guard = resolving + id

    if (segments.size == 1) {
        val chunk = chunkCache[id]
        if (chunk != null) {
            return parseJsonOrRaw(chunk) ?: chunk
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
                val index = segments[i].toIntOrNull() ?: return null
                if (index in 0 until current.length()) {
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
    modelCache: Map<String, Any?>,
    resolving: Set<String>
): Any? {
    val segments = reference.split(':')
    val id = segments[0]

    if (segments.size == 1) {
        val chunk = chunkCache[id]
        if (chunk != null) {
            return parseJsonOrRaw(chunk) ?: chunk
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
                            if (idx != null && idx in 0 until current.length()) current.opt(idx) else null
                        }
                    } ?: return null
                } else {
                    val idx = seg.toIntOrNull()
                    if (idx != null && idx in 0 until current.length()) {
                        current = current.get(idx)
                    } else {
                        return null
                    }
                }
            }
            else -> return null
        }

        if (current is String && current.startsWith("$") && current.length > 1) {
            current = resolveValue(current, chunkCache, modelCache, guard) ?: return null
        }
    }
    return resolveValue(current, chunkCache, modelCache, guard)
}

private fun resolveMapRef(
    id: String,
    chunkCache: Map<String, String>,
    modelCache: Map<String, Any?>,
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
    modelCache: Map<String, Any?>,
    resolving: Set<String>
): JSONArray? {
    if (id in resolving) return null
    val values = modelCache[id] as? JSONArray ?: return null
    return resolveValue(values, chunkCache, modelCache, resolving + id) as? JSONArray
}
