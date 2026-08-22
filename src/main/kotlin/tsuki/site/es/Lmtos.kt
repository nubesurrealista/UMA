package tsuki.site.es

import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.config.ConfigKey
import tsuki.core.PagedMangaParser
import tsuki.model.ContentRating
import tsuki.model.ContentType
import tsuki.model.Manga
import tsuki.model.MangaChapter
import tsuki.model.MangaListFilter
import tsuki.model.MangaListFilterCapabilities
import tsuki.model.MangaListFilterOptions
import tsuki.model.MangaPage
import tsuki.model.MangaParserSource
import tsuki.model.MangaSource
import tsuki.model.MangaState
import tsuki.model.MangaTag
import tsuki.model.RATING_UNKNOWN
import tsuki.model.SortOrder
import tsuki.util.generateUid
import tsuki.util.parseHtml
import tsuki.util.toAbsoluteUrl
import okhttp3.Headers
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.EnumSet
import java.util.Locale
import java.util.TimeZone

private val NEXT_F_REGEX = Regex("""self\.__next_f\.push\(\s*(\[.*])\s*\)\s*;?\s*$""", RegexOption.DOT_MATCHES_ALL)

@MangaSourceParser("LMTO", "LMTO", "es")
internal class Lmtos(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.LMTO, pageSize = 20) {

    override val configKeyDomain = ConfigKey.Domain("lmtos.net")
    private val baseUrl = "https://$domain"

    @Volatile
    private var mangaCache: List<MangaDto>? = null

    @Volatile
    private var cacheTimestamp = 0L
    private val cacheDuration = 10 * 60 * 1000L

    override fun getRequestHeaders(): Headers {
        return super.getRequestHeaders().newBuilder()
            .set("Referer", "$baseUrl/")
            .set("Origin", baseUrl)
            .set("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8")
            .set("Accept-Language", "es-ES,es;q=0.9,en;q=0.8")
            .set("User-Agent", config[userAgentKey])
            .build()
    }

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.ALPHABETICAL,
        SortOrder.UPDATED,
        SortOrder.POPULARITY,
    )

    override val filterCapabilities = MangaListFilterCapabilities(
        isSearchSupported = true,
        isMultipleTagsSupported = true,
    )

    override suspend fun getFilterOptions() = MangaListFilterOptions(
        availableTags = GENRES.map { genre ->
            MangaTag(key = genre, title = genre, source = source)
        }.toSet(),
        availableStates = EnumSet.of(
            MangaState.ONGOING,
            MangaState.FINISHED,
            MangaState.PAUSED,
        ),
        availableContentTypes = EnumSet.of(
            ContentType.MANGA,
            ContentType.MANHUA,
            ContentType.MANHWA,
            ContentType.ONE_SHOT,
        ),
    )

    private suspend fun fetchMangas(): List<MangaDto> {
        val cached = mangaCache
        val now = System.currentTimeMillis()
        if (cached != null && now - cacheTimestamp < cacheDuration) {
            return cached
        }

        val doc = webClient.httpGet("$baseUrl/series").parseHtml()
        val resolved = extractNextJsData(doc)
        val mangasArray = findArray(resolved, "mangas") ?: throw Exception("Could not find 'mangas' array")
        val list = ArrayList<MangaDto>(mangasArray.length())
        for (i in 0 until mangasArray.length()) {
            val obj = mangasArray.getJSONObject(i)
            list.add(MangaDto.fromJson(obj))
        }

        mangaCache = list
        cacheTimestamp = now
        return list
    }

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val allMangas = fetchMangas()
        val query = filter.query?.trim().orEmpty()

        val selectedGenres = filter.tags.map { it.key }
        val selectedState = filter.states.firstOrNull()
        val selectedType = filter.types.firstOrNull()

        val filtered = allMangas.asSequence()
            .filter { manga ->
                if (query.isEmpty()) return@filter true
                manga.title.contains(query, ignoreCase = true) ||
                        manga.alternativeTitles?.any { it.contains(query, ignoreCase = true) } == true
            }
            .filter { manga ->
                when (filter.contentRating.firstOrNull()) {
                    ContentRating.ADULT -> manga.isAdult
                    ContentRating.SAFE -> !manga.isAdult
                    else -> true
                }
            }
            .filter { manga ->
                if (selectedType == null) true
                else {
                    when (selectedType) {
                        ContentType.MANGA -> manga.type == "manga"
                        ContentType.MANHUA -> manga.type == "manhua"
                        ContentType.MANHWA -> manga.type == "manhwa"
                        ContentType.ONE_SHOT -> manga.type == "oneshot"
                        else -> true
                    }
                }
            }
            .filter { manga ->
                if (selectedState == null) true
                else {
                    when (selectedState) {
                        MangaState.ONGOING -> manga.status == "ongoing"
                        MangaState.FINISHED -> manga.status == "completed"
                        MangaState.PAUSED -> manga.status == "paused"
                        else -> true
                    }
                }
            }
            .filter { manga ->
                if (selectedGenres.isEmpty()) true
                else selectedGenres.all { g -> manga.genres?.contains(g) == true }
            }
            .let { sequence ->
                when (order) {
                    SortOrder.ALPHABETICAL -> sequence.sortedBy { it.title }
                    SortOrder.UPDATED -> sequence.sortedByDescending { it.latestChapterCreatedAt ?: 0L }
                    SortOrder.POPULARITY -> sequence.sortedByDescending { it.totalViews ?: 0 }
                    else -> sequence
                }
            }
            .toList()

        val pagedList = filtered.drop((page - 1) * pageSize).take(pageSize)
        return pagedList.map { it.toManga(baseUrl, source) }
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val doc = webClient.httpGet(manga.publicUrl).parseHtml()
        val resolved = extractNextJsData(doc)
        val mangaObj = findObject(resolved, "manga") ?: throw Exception("Could not find 'manga' object")
        val chaptersArray = findArray(resolved, "chapters") ?: JSONArray()

        val dto = MangaDto.fromJson(mangaObj)

        val chapters = ArrayList<MangaChapter>()
        val mangaSlug = manga.url.substringAfterLast("/")
        for (i in 0 until chaptersArray.length()) {
            val ch = chaptersArray.getJSONObject(i)
            val slug = ch.optString("slug")
            val chNumber = ch.optDouble("number", -1.0).toFloat()
            val chHref = "/manga/$mangaSlug/$slug"
            chapters.add(
                MangaChapter(
                    id = generateUid(source, chHref),
                    url = chHref,
                    title = "Ch. ${chNumber.toString().removeSuffix(".0")}",
                    number = chNumber,
                    volume = 0,
                    uploadDate = parseDate(ch.optString("createdAt")),
                    source = source,
                    scanlator = null,
                    branch = null,
                )
            )
        }
        chapters.sortBy { it.number }

        return dto.toManga(baseUrl, source).copy(
            chapters = chapters,
            description = dto.description ?: manga.description,
        )
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val fullUrl = chapter.url.toAbsoluteUrl(domain)
        val doc = webClient.httpGet(fullUrl).parseHtml()
        val resolved = extractNextJsData(doc)
        val chapterObj = findObject(resolved, "chapter") ?: throw Exception("Could not find 'chapter' object")
        val pagesArray = chapterObj.optJSONArray("pages") ?: JSONArray()

        val pages = ArrayList<MangaPage>()
        for (i in 0 until pagesArray.length()) {
            val pageUrl = pagesArray.getString(i)
            pages.add(
                MangaPage(
                    id = generateUid(source, pageUrl),
                    url = pageUrl,
                    preview = null,
                    source = source,
                )
            )
        }
        return pages
    }

    private fun parseDate(dateStr: String?): Long {
        if (dateStr.isNullOrBlank()) return 0L
        return runCatching { createDateFormat().parse(dateStr)?.time ?: 0L }.getOrDefault(0L)
    }

    // ── RSC extractor ──

    private fun extractNextJsData(doc: org.jsoup.nodes.Document): JSONObject {
        val chunkCache = mutableMapOf<String, String>()
        val modelCache = mutableMapOf<String, JSONObject?>()
        val root = JSONObject()

        val scripts = doc.select("script:not([src])")
            .mapNotNull { it.data() }
            .filter { "self.__next_f.push" in it }

        for (script in scripts) {
            val match = NEXT_F_REGEX.find(script) ?: continue
            val raw = match.groupValues[1]
            val arr = try {
                JSONArray(raw)
            } catch (_: Exception) {
                continue
            }
            if (arr.length() < 2) continue
            val content = arr.optString(1) ?: continue
            extractRscChunks(content, chunkCache, modelCache)
        }

        for ((id, obj) in modelCache) {
            if (obj != null) {
                modelCache[id] = resolveRefs(obj, chunkCache, modelCache)
            }
        }

        for ((_, obj) in modelCache) {
            if (obj != null) {
                for (key in obj.keys()) {
                    if (!root.has(key)) {
                        root.put(key, obj.get(key))
                    }
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
                    resolveRef(value.substring(1), chunkCache, modelCache, resolving) ?: value
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

        var base: Any? = null
        if (segments.size == 1) {
            chunkCache[id]?.let { return it }
        }
        base = modelCache[id] ?: return null

        var current = base
        for (i in 1 until segments.size) {
            if (current is JSONObject) {
                current = current.opt(segments[i])
            } else if (current is JSONArray) {
                val index = segments[i].toIntOrNull()
                if (index != null && index < current.length()) {
                    current = current.get(index)
                } else {
                    return null
                }
            } else {
                return null
            }
        }
        return resolveValue(current ?: return null, chunkCache, modelCache, guard)
    }

    // ── Buscadores ──

    private fun findObject(root: JSONObject, key: String): JSONObject? {
        if (root.has(key)) {
            val obj = root.optJSONObject(key)
            if (obj != null) return obj
        }
        val keys = root.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val value = root.get(k)
            when (value) {
                is JSONObject -> {
                    val found = findObject(value, key)
                    if (found != null) return found
                }
                is JSONArray -> {
                    for (i in 0 until value.length()) {
                        val item = value.optJSONObject(i) ?: continue
                        val found = findObject(item, key)
                        if (found != null) return found
                    }
                }
            }
        }
        return null
    }

    private fun findArray(root: JSONObject, key: String): JSONArray? {
        if (root.has(key)) {
            val arr = root.optJSONArray(key)
            if (arr != null) return arr
        }
        val keys = root.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val value = root.get(k)
            when (value) {
                is JSONObject -> {
                    val found = findArray(value, key)
                    if (found != null) return found
                }
                is JSONArray -> {
                    for (i in 0 until value.length()) {
                        val item = value.optJSONObject(i) ?: continue
                        val found = findArray(item, key)
                        if (found != null) return found
                    }
                }
            }
        }
        return null
    }

    // ── MangaDto ──

    private data class MangaDto(
        val slug: String,
        val title: String,
        val alternativeTitles: List<String>? = null,
        val description: String? = null,
        val coverImage: String? = null,
        val isAdult: Boolean = false,
        val type: String? = null,
        val status: String? = null,
        val demographic: String? = null,
        val genres: List<String>? = null,
        val author: String? = null,
        val artist: String? = null,
        val latestChapterCreatedAt: Long? = null,
        val totalViews: Int? = null,
    ) {
        fun toManga(baseUrl: String, source: MangaSource): Manga {
            val path = "/manga/$slug"
            val tags = genres?.map { g -> MangaTag(key = g.lowercase(), title = g, source = source) }.orEmpty().toSet()
            val state = when (status?.lowercase()) {
                "ongoing" -> MangaState.ONGOING
                "completed" -> MangaState.FINISHED
                "paused" -> MangaState.PAUSED
                else -> null
            }
            return Manga(
                id = generateUid(source, path),
                url = path,
                publicUrl = "$baseUrl$path",
                title = title,
                altTitles = alternativeTitles.orEmpty().toSet(),
                coverUrl = coverImage?.takeIf { it.isNotEmpty() }?.let {
                    if (it.startsWith("http")) it else "$baseUrl/$it"
                } ?: "",
                rating = RATING_UNKNOWN,
                contentRating = if (isAdult) ContentRating.ADULT else ContentRating.SAFE,
                tags = tags,
                state = state,
                authors = setOfNotNull(author, artist),
                source = source,
            )
        }

        companion object {
            fun fromJson(obj: JSONObject): MangaDto {
                val altTitles = obj.optJSONArray("alternativeTitles")?.let { arr ->
                    (0 until arr.length()).map { arr.getString(it) }
                }
                val genres = obj.optJSONArray("genres")?.let { arr ->
                    (0 until arr.length()).map { arr.getString(it) }
                }
                val latest = obj.optString("latestChapterCreatedAt").takeIf { it.isNotEmpty() }
                    ?.let { createDateFormat().parse(it)?.time }
                return MangaDto(
                    slug = obj.getString("slug"),
                    title = obj.getString("title"),
                    alternativeTitles = altTitles,
                    description = obj.optString("description").takeIf { it.isNotEmpty() },
                    coverImage = obj.optString("coverImage").takeIf { it.isNotEmpty() },
                    isAdult = obj.optBoolean("isAdult", false),
                    type = obj.optString("type").takeIf { it.isNotEmpty() },
                    status = obj.optString("status").takeIf { it.isNotEmpty() },
                    demographic = obj.optString("demographic").takeIf { it.isNotEmpty() },
                    genres = genres,
                    author = obj.optString("author").takeIf { it.isNotEmpty() },
                    artist = obj.optString("artist").takeIf { it.isNotEmpty() },
                    latestChapterCreatedAt = latest,
                    totalViews = obj.optInt("totalViews").takeIf { it > 0 },
                )
            }
        }
    }

    companion object {
        private fun createDateFormat(): SimpleDateFormat {
            return SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
        }

        private val GENRES = listOf(
            "Acción",
            "Artes Marciales",
            "Aventuras",
            "Carreras",
            "Ciencia Ficción",
            "Comedia",
            "Demencia",
            "Demonios",
            "Deportes",
            "Drama",
            "Ecchi",
            "Escolares",
            "Gore",
            "Harem",
            "Isekai",
            "Juegos",
            "Magia",
            "Mecha",
            "Militar",
            "Misterio",
            "Música",
            "Parodia",
            "Policía",
            "Psicológico",
            "Recuentos de la vida",
            "Romance",
            "Romcom",
            "Samurai",
            "Sobrenatural",
            "Superpoderes",
            "Suspenso",
            "Terror",
            "Vampiros",
            "Yaoi",
            "Yuri",
        )
    }
}
