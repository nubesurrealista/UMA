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

@MangaSourceParser("LMTOONLINE", "LMTO", "es")
internal class LmtoOnline(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.LMTOONLINE, pageSize = 20) {

    override val configKeyDomain = ConfigKey.Domain("lmtos.net")
    private val baseUrl = "https://$domain"

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    @Volatile
    private var mangaCache: List<MangaDto>? = null
    @Volatile
    private var cacheTimestamp = 0L
    private val cacheDuration = 10 * 60 * 1000L

    override fun getRequestHeaders(): Headers {
        return super.getRequestHeaders().newBuilder()
            .set("Referer", "$baseUrl/")
            .set("Origin", "$baseUrl")
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
        val nextDataRaw = doc.selectFirst("script#__NEXT_DATA__")?.data()
            ?: throw Exception("Could not retrieve source data")

        val json = JSONObject(nextDataRaw)
        val mangasArray = findArray(json, "mangas") ?: JSONArray()
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
        return pagedList.map { it.toManga(baseUrl) }
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val doc = webClient.httpGet(manga.publicUrl).parseHtml()
        val nextDataRaw = doc.selectFirst("script#__NEXT_DATA__")?.data()
            ?: throw Exception("Could not retrieve manga details")

        val json = JSONObject(nextDataRaw)
        val mangaObj = findObject(json, "manga") ?: throw Exception("Invalid details structure")
        val chaptersArray = findArray(json, "chapters") ?: JSONArray()

        val dto = MangaDto.fromJson(mangaObj)

        val chapters = ArrayList<MangaChapter>()
        for (i in 0 until chaptersArray.length()) {
            val ch = chaptersArray.getJSONObject(i)
            val slug = ch.optString("slug")
            val chNumber = ch.optDouble("number", -1.0).toFloat()
            val chHref = "/manga/${manga.url.substringAfterLast("/")}/$slug"
            chapters.add(
                MangaChapter(
                    id = generateUid(chHref),
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

        return dto.toManga(baseUrl).copy(
            chapters = chapters,
            description = dto.description ?: manga.description,
        )
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val fullUrl = chapter.url.toAbsoluteUrl(domain)
        val doc = webClient.httpGet(fullUrl).parseHtml()
        val nextDataRaw = doc.selectFirst("script#__NEXT_DATA__")?.data()
            ?: throw Exception("Could not retrieve chapter pages")

        val json = JSONObject(nextDataRaw)
        val chapterObj = findObject(json, "chapter") ?: throw Exception("Invalid chapter structure")
        val pagesArray = chapterObj.optJSONArray("pages") ?: JSONArray()

        val pages = ArrayList<MangaPage>()
        for (i in 0 until pagesArray.length()) {
            val pageUrl = pagesArray.getString(i)
            pages.add(
                MangaPage(
                    id = generateUid(pageUrl),
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
        return runCatching { dateFormat.parse(dateStr)?.time ?: 0L }.getOrDefault(0L)
    }

    private fun findObject(json: JSONObject, key: String): JSONObject? {
        if (json.has(key)) {
            val obj = json.optJSONObject(key)
            if (obj != null) return obj
        }
        val keys = json.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val value = json.get(k)
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

    private fun findArray(json: JSONObject, key: String): JSONArray? {
        if (json.has(key)) {
            val arr = json.optJSONArray(key)
            if (arr != null) return arr
        }
        val keys = json.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val value = json.get(k)
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
        fun toManga(baseUrl: String): Manga {
            val path = "/manga/$slug"
            val tags = genres?.map { g -> MangaTag(key = g.lowercase(), title = g, source = source) }.orEmpty().toSet()
            val state = when (status?.lowercase()) {
                "ongoing" -> MangaState.ONGOING
                "completed" -> MangaState.FINISHED
                "paused" -> MangaState.PAUSED
                else -> null
            }
            return Manga(
                id = generateUid(path),
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
                    ?.let { dateFormat.parse(it)?.time }
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
