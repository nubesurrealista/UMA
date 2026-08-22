package tsuki.site.es

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
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
import tsuki.util.oneOrThrowIfMany
import tsuki.util.parseHtml
import tsuki.util.toAbsoluteUrl
import java.text.SimpleDateFormat
import java.util.EnumSet
import java.util.Locale
import java.util.TimeZone

@MangaSourceParser("LMTOONLINE", "LMTO", "es")
internal class LmtoOnline(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.LMTOONLINE, pageSize = 20) {

    override val configKeyDomain = ConfigKey.Domain("lmto.online")

    private val json = Json { ignoreUnknownKeys = true }

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    @Volatile
    private var mangaCache = emptyList<LmtoManga>()

    @Volatile
    private var cacheTimestamp = 0L

    private val cacheDuration = 10 * 60 * 1000L

    override fun getRequestHeaders(): okhttp3.Headers {
        val builder = super.getRequestHeaders().newBuilder()
            .set("Referer", "https://$domain/")
            .set("Origin", "https://$domain")
            .set("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8")
            .set("Accept-Language", "es-ES,es;q=0.9,en;q=0.8")
            .set("User-Agent", config[userAgentKey])
        return builder.build()
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
        availableContentRatings = EnumSet.of(
            ContentRating.SAFE,
            ContentRating.ADULT,
        ),
    )

    @Synchronized
    private suspend fun fetchMangas(): List<LmtoManga> {
        val now = System.currentTimeMillis()
        if (mangaCache.isNotEmpty() && now - cacheTimestamp < cacheDuration) {
            return mangaCache
        }

        val doc = webClient.httpGet("https://$domain/series").parseHtml()
        val nextDataRaw = doc.selectFirst("script#__NEXT_DATA__")?.data()
            ?: throw Exception("Could not retrieve source data")

        val parsed = json.decodeFromString<NextData<SeriesProps>>(nextDataRaw)
        val mangas = parsed.props?.pageProps?.mangas.orEmpty()

        mangaCache = mangas
        cacheTimestamp = now
        return mangas
    }

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val allMangas = fetchMangas()
        val query = filter.query?.trim().orEmpty()

        val selectedGenres = filter.tags.map { it.key }
        val selectedState = filter.states.firstOrNull()
        val selectedType = filter.types.firstOrNull()
        val selectedRating = filter.contentRating.oneOrThrowIfMany()

        val filtered = allMangas.asSequence()
            .filter { manga ->
                query.isEmpty() ||
                    manga.title.contains(query, ignoreCase = true) ||
                    manga.alternativeTitles?.any { it.contains(query, ignoreCase = true) } == true
            }
            .filter { manga ->
                when (selectedRating) {
                    ContentRating.ADULT -> manga.isAdult
                    ContentRating.SAFE -> !manga.isAdult
                    else -> true
                }
            }
            .filter { manga ->
                if (selectedType == null) true
                else when (selectedType) {
                    ContentType.MANGA -> manga.type?.lowercase() == "manga"
                    ContentType.MANHUA -> manga.type?.lowercase() == "manhua"
                    ContentType.MANHWA -> manga.type?.lowercase() == "manhwa"
                    ContentType.ONE_SHOT -> manga.type?.lowercase() == "oneshot"
                    else -> true
                }
            }
            .filter { manga ->
                if (selectedState == null) true
                else when (selectedState) {
                    MangaState.ONGOING -> manga.status?.lowercase() == "ongoing"
                    MangaState.FINISHED -> manga.status?.lowercase() == "completed"
                    MangaState.PAUSED -> manga.status?.lowercase() == "paused"
                    else -> true
                }
            }
            .filter { manga ->
                selectedGenres.isEmpty() || selectedGenres.all { genre ->
                    manga.genres?.contains(genre) == true
                }
            }
            .let { sequence ->
                when (order) {
                    SortOrder.ALPHABETICAL -> sequence.sortedBy { it.title }
                    SortOrder.UPDATED -> sequence.sortedByDescending { parseDate(it.latestChapterCreatedAt) }
                    SortOrder.POPULARITY -> sequence.sortedByDescending { it.totalViews ?: 0 }
                    else -> sequence.sortedBy { it.title }
                }
            }
            .toList()

        val pagedList = filtered.drop((page - 1) * pageSize).take(pageSize)
        return pagedList.map { it.toManga(domain, source) }
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val doc = webClient.httpGet(manga.publicUrl).parseHtml()
        val nextDataRaw = doc.selectFirst("script#__NEXT_DATA__")?.data()
            ?: throw Exception("Could not retrieve manga details")

        val parsed = json.decodeFromString<NextData<MangaDetailsProps>>(nextDataRaw)
        val detailsProps = parsed.props?.pageProps
            ?: throw Exception("Invalid details structure")

        val lmtoManga = detailsProps.manga
        val lmtoChapters = detailsProps.chapters.orEmpty()

        val title = lmtoManga?.title ?: manga.title
        val cover = lmtoManga?.coverImage ?: manga.coverUrl
        val description = lmtoManga?.description ?: manga.description

        val authorsSet = listOfNotNull(lmtoManga?.author, lmtoManga?.artist)
            .filter { it.isNotBlank() }
            .toSet()

        val tags = lmtoManga?.genres?.map { g ->
            MangaTag(key = g, title = g, source = source)
        }?.toSet() ?: manga.tags

        val state = when (lmtoManga?.status?.lowercase()) {
            "ongoing" -> MangaState.ONGOING
            "completed" -> MangaState.FINISHED
            "paused" -> MangaState.PAUSED
            else -> manga.state
        }

        val chapters = lmtoChapters.map { ch ->
            val chHref = "/manga/${manga.url.substringAfterLast("/")}/${ch.slug}"
            val chNumber = ch.number ?: -1f
            MangaChapter(
                id = generateUid(chHref),
                url = chHref,
                title = "Ch. ${chNumber.toString().removeSuffix(".0")}",
                number = chNumber,
                volume = 0,
                uploadDate = parseDate(ch.createdAt),
                source = source,
                scanlator = null,
                branch = null,
            )
        }.sortedBy { it.number }

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

        val parsed = json.decodeFromString<NextData<ChapterPagesProps>>(nextDataRaw)
        val pages = parsed.props?.pageProps?.chapter?.pages.orEmpty()

        return pages.mapIndexed { _, pageUrl ->
            MangaPage(
                id = generateUid(pageUrl),
                url = pageUrl,
                preview = null,
                source = source,
            )
        }
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

@Serializable
private class NextData<T>(
    val props: NextProps<T>? = null,
)

@Serializable
private class NextProps<T>(
    val pageProps: T? = null,
)

@Serializable
private class SeriesProps(
    val mangas: List<LmtoManga>? = null,
)

@Serializable
private class MangaDetailsProps(
    val manga: LmtoManga? = null,
    val chapters: List<LmtoChapter>? = null,
)

@Serializable
private class ChapterPagesProps(
    val chapter: LmtoChapter? = null,
)

@Serializable
private class LmtoManga(
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
    val latestChapterCreatedAt: String? = null,
    val totalViews: Int? = null,
) {
    fun toManga(domain: String, source: MangaParserSource): Manga {
        val path = "/manga/$slug"
        return Manga(
            id = generateUid(path),
            url = path,
            publicUrl = path.toAbsoluteUrl(domain),
            title = title,
            altTitles = alternativeTitles?.toSet() ?: emptySet(),
            coverUrl = coverImage,
            rating = RATING_UNKNOWN,
            contentRating = if (isAdult) ContentRating.ADULT else ContentRating.SAFE,
            tags = genres?.map { g -> MangaTag(key = g, title = g, source = source) }?.toSet() ?: emptySet(),
            state = when (status?.lowercase()) {
                "ongoing" -> MangaState.ONGOING
                "completed" -> MangaState.FINISHED
                "paused" -> MangaState.PAUSED
                else -> null
            },
            authors = listOfNotNull(author, artist).filter { it.isNotBlank() }.toSet(),
            source = source,
        )
    }
}

@Serializable
private class LmtoChapter(
    val slug: String,
    val number: Float? = null,
    val createdAt: String? = null,
    val pages: List<String>? = null,
)
