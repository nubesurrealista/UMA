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
import tsuki.util.parseJson
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

    override val configKeyDomain = ConfigKey.Domain("lmto.online")

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    @Volatile
    private var mangaCache: List<JSONObject>? = null

    @Volatile
    private var cacheTimestamp = 0L

    private val cacheDuration = 10 * 60 * 1000L

    override fun getRequestHeaders(): Headers {
        return super.getRequestHeaders().newBuilder()
            .set("Referer", "https://$domain/")
            .set("Origin", "https://$domain")
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

    private suspend fun fetchMangas(): List<JSONObject> {
        val cached = mangaCache
        val now = System.currentTimeMillis()
        if (cached != null && now - cacheTimestamp < cacheDuration) {
            return cached
        }

        val doc = webClient.httpGet("https://$domain/series").parseHtml()
        val nextDataRaw = doc.selectFirst("script#__NEXT_DATA__")?.data()
            ?: throw Exception("Could not retrieve source data")

        val json = JSONObject(nextDataRaw)
        val mangasArray = json.optJSONObject("props")
            ?.optJSONObject("pageProps")
            ?.optJSONArray("mangas") ?: JSONArray()

        val list = ArrayList<JSONObject>(mangasArray.length())
        for (i in 0 until mangasArray.length()) {
            list.add(mangasArray.getJSONObject(i))
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
            .filter { obj ->
                if (query.isEmpty()) return@filter true
                val title = obj.optString("title", "")
                if (title.contains(query, ignoreCase = true)) return@filter true
                val altTitles = obj.optJSONArray("alternativeTitles")
                if (altTitles != null) {
                    for (i in 0 until altTitles.length()) {
                        if (altTitles.optString(i).contains(query, ignoreCase = true)) return@filter true
                    }
                }
                false
            }
            .filter { obj ->
                val isAdult = obj.optBoolean("isAdult", false)
                when (filter.contentRating.firstOrNull()) {
                    ContentRating.ADULT -> isAdult
                    ContentRating.SAFE -> !isAdult
                    else -> true
                }
            }
            .filter { obj ->
                if (selectedType == null) true
                else {
                    val type = obj.optString("type", "").lowercase()
                    when (selectedType) {
                        ContentType.MANGA -> type == "manga"
                        ContentType.MANHUA -> type == "manhua"
                        ContentType.MANHWA -> type == "manhwa"
                        ContentType.ONE_SHOT -> type == "oneshot"
                        else -> true
                    }
                }
            }
            .filter { obj ->
                if (selectedState == null) true
                else {
                    val status = obj.optString("status", "").lowercase()
                    when (selectedState) {
                        MangaState.ONGOING -> status == "ongoing"
                        MangaState.FINISHED -> status == "completed"
                        MangaState.PAUSED -> status == "paused"
                        else -> true
                    }
                }
            }
            .filter { obj ->
                if (selectedGenres.isEmpty()) true
                else {
                    val genresArr = obj.optJSONArray("genres") ?: return@filter false
                    val genresList = (0 until genresArr.length()).map { genresArr.getString(it) }
                    selectedGenres.all { g -> genresList.contains(g) }
                }
            }
            .let { sequence ->
                when (order) {
                    SortOrder.ALPHABETICAL -> sequence.sortedBy { it.optString("title", "") }
                    SortOrder.UPDATED -> sequence.sortedByDescending { parseDate(it.optString("latestChapterCreatedAt")) }
                    SortOrder.POPULARITY -> sequence.sortedByDescending { it.optInt("totalViews", 0) }
                    else -> sequence.sortedBy { it.optString("title", "") }
                }
            }
            .toList()

        val pagedList = filtered.drop((page - 1) * pageSize).take(pageSize)
        return pagedList.map { parseMangaFromObject(it) }
    }

    private fun parseMangaFromObject(obj: JSONObject): Manga {
        val slug = obj.getString("slug")
        val title = obj.getString("title")
        val cover = obj.optString("coverImage").ifBlank { null }
        val isAdult = obj.optBoolean("isAdult", false)
        val status = obj.optString("status", "").lowercase()

        val altTitlesArr = obj.optJSONArray("alternativeTitles")
        val altTitles = if (altTitlesArr != null) {
            (0 until altTitlesArr.length()).map { altTitlesArr.getString(it) }.toSet()
        } else emptySet()

        val genresArr = obj.optJSONArray("genres")
        val tags = if (genresArr != null) {
            (0 until genresArr.length()).map { g ->
                val name = genresArr.getString(g)
                MangaTag(key = name, title = name, source = source)
            }.toSet()
        } else emptySet()

        val author = obj.optString("author").ifBlank { null }
        val artist = obj.optString("artist").ifBlank { null }
        val path = "/manga/$slug"

        return Manga(
            id = generateUid(path),
            url = path,
            publicUrl = path.toAbsoluteUrl(domain),
            title = title,
            altTitles = altTitles,
            coverUrl = cover,
            rating = RATING_UNKNOWN,
            contentRating = if (isAdult) ContentRating.ADULT else ContentRating.SAFE,
            tags = tags,
            state = when (status) {
                "ongoing" -> MangaState.ONGOING
                "completed" -> MangaState.FINISHED
                "paused" -> MangaState.PAUSED
                else -> null
            },
            authors = setOfNotNull(author, artist),
            source = source,
        )
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val doc = webClient.httpGet(manga.publicUrl).parseHtml()
        val nextDataRaw = doc.selectFirst("script#__NEXT_DATA__")?.data()
            ?: throw Exception("Could not retrieve manga details")

        val json = JSONObject(nextDataRaw)
        val pageProps = json.optJSONObject("props")?.optJSONObject("pageProps")
            ?: throw Exception("Invalid details structure")

        val lmtoManga = pageProps.optJSONObject("manga")
        val lmtoChapters = pageProps.optJSONArray("chapters") ?: JSONArray()

        val title = lmtoManga?.optString("title")?.ifBlank { null } ?: manga.title
        val cover = lmtoManga?.optString("coverImage")?.ifBlank { null } ?: manga.coverUrl
        val description = lmtoManga?.optString("description")?.ifBlank { null } ?: manga.description

        val author = lmtoManga?.optString("author")?.ifBlank { null }
        val artist = lmtoManga?.optString("artist")?.ifBlank { null }
        val authorsSet = setOfNotNull(author, artist)

        val genresArr = lmtoManga?.optJSONArray("genres")
        val tags = if (genresArr != null) {
            (0 until genresArr.length()).map { g ->
                val name = genresArr.getString(g)
                MangaTag(key = name, title = name, source = source)
            }.toSet()
        } else manga.tags

        val state = when (lmtoManga?.optString("status")?.lowercase()) {
            "ongoing" -> MangaState.ONGOING
            "completed" -> MangaState.FINISHED
            "paused" -> MangaState.PAUSED
            else -> manga.state
        }

        val chapters = ArrayList<MangaChapter>()
        for (i in 0 until lmtoChapters.length()) {
            val ch = lmtoChapters.getJSONObject(i)
            val slug = ch.optString("slug")
            val chHref = "/manga/${manga.url.substringAfterLast("/")}/$slug"
            val chNumber = ch.optDouble("number", -1.0).toFloat()

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

        return manga.copy(
            title = title,
            coverUrl = cover,
            description = description,
            authors = if (authorsSet.isNotEmpty()) authorsSet else manga.authors,
            tags = tags,
            state = state,
            chapters = chapters,
        )
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val fullUrl = chapter.url.toAbsoluteUrl(domain)
        val doc = webClient.httpGet(fullUrl).parseHtml()
        val nextDataRaw = doc.selectFirst("script#__NEXT_DATA__")?.data()
            ?: throw Exception("Could not retrieve chapter pages")

        val json = JSONObject(nextDataRaw)
        val pagesArray = json.optJSONObject("props")
            ?.optJSONObject("pageProps")
            ?.optJSONObject("chapter")
            ?.optJSONArray("pages") ?: JSONArray()

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
        return runCatching { dateFormat.parse(dateStr)?.time }.getOrDefault(0L)
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
