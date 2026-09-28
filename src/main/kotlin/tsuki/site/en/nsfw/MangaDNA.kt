package tsuki.site.en.nsfw

import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.config.ConfigKey
import tsuki.core.PagedMangaParser

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

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import tsuki.model.ContentRating
import java.text.SimpleDateFormat
import java.util.EnumSet
import java.util.Locale
import java.util.TimeZone
import kotlin.takeIf

@MangaSourceParser("MANGADNA", "MangaDNA", "en", ContentType.HENTAI)
internal class MangaDNA(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.MANGADNA, 24) {

    override val configKeyDomain = ConfigKey.Domain("mangadna.com")

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.UPDATED,
        SortOrder.POPULARITY,
        SortOrder.RATING,
        SortOrder.NEWEST,
        SortOrder.ALPHABETICAL,
    )

    override val filterCapabilities: MangaListFilterCapabilities
        get() = MangaListFilterCapabilities(
            isSearchSupported = true,
            isSearchWithFiltersSupported = true,
            isMultipleTagsSupported = true,
            isTagsExclusionSupported = false,
        )

    override suspend fun getFilterOptions() = MangaListFilterOptions(
        availableTags = GENRES.mapTo(mutableSetOf()) {
            MangaTag(key = it.second, title = it.first, source = source)
        },
        availableStates = EnumSet.of(
            MangaState.ONGOING,
            MangaState.FINISHED,
            MangaState.PAUSED,
            MangaState.ABANDONED,
        ),
        availableContentTypes = EnumSet.noneOf(ContentType::class.java),
    )

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val query = filter.query?.trim()?.takeIf { it.isNotEmpty() }
        if (query != null) {
            val url = "https://$domain/search".toHttpUrl().newBuilder()
                .addQueryParameter("q", query)
                .addQueryParameter("page", page.toString())
                .build()
            return parseMangaList(webClient.httpGet(url).parseHtml())
        }

        val genre = filter.tags.firstOrNull()?.key?.takeIf { it.isNotEmpty() }
        val orderBy = sortByValue(order)

        val url = if (genre.isNullOrEmpty()) {
            "https://$domain/manga/page/$page".toHttpUrl().newBuilder()
        } else {
            "https://$domain/manga-genre/$genre/$page".toHttpUrl().newBuilder()
        }
        url.addQueryParameter("orderby", orderBy)
        return parseMangaList(webClient.httpGet(url.build()).parseHtml())
    }

    private fun parseMangaList(document: Document): List<Manga> =
        document.select("div.home-item").mapNotNull { card ->
            val link = card.selectFirst("h3.htitle a, .hthumb a") ?: return@mapNotNull null
            val url = link.attr("abs:href")
            Manga(
                id = generateUid(url),
                title = link.attr("title").ifBlank { link.text() },
                altTitles = emptySet(),
                url = url,
                publicUrl = url,
                rating = RATING_UNKNOWN,
                contentRating = null,
                coverUrl = card.selectFirst("img")?.imgAttr(),
                tags = emptySet(),
                state = null,
                authors = emptySet(),
                source = source,
            )
        }

    override suspend fun getDetails(manga: Manga): Manga {
        val document = webClient.httpGet(manga.publicUrl).parseHtml()
        val info = document.selectFirst("div.summary_content_wrap, div.tab-summary") ?: document

        val title = document.selectFirst("h1.entry-title")?.text()
            ?: document.selectFirst("div.post-title h1, h1")?.text()
            ?: manga.title

        val thumbnail = document.selectFirst("div.summary_image img")?.imgAttr()
            ?: document.selectFirst("meta[property=og:image]")?.attr("content")
            ?: manga.coverUrl

        val rows = info.select("div.post-content_item").associate { item ->
            val label = item.selectFirst(".summary-heading")?.text()
                .orEmpty().trimEnd(':').trim()
            val value = item.selectFirst(".summary-content")?.text()
                .orEmpty().trim()
            label to value
        }

        val author = info.select("div.author-content a")
            .joinToString(", ") { it.text() }
            .takeIf { it.isNotEmpty() && it != "Updating" }
        val artist = info.select("div.artist-content a")
            .joinToString(", ") { it.text() }
            .takeIf { it.isNotEmpty() && it != "Updating" }

        val genreNames = info.select("div.genres-content a")
            .map { it.text().trim() }
            .filter { it.isNotEmpty() }
        val type = rows["Type"]?.takeIf { it.isNotEmpty() && it != "Updating" }
        val genres = (genreNames + listOfNotNull(type)).distinct()

        val state = parseState(rows["Status"])

        val altNames = rows["Alternative"]
            ?.takeIf { it.isNotEmpty() && it != "Updating" }
            ?.split("/", ",", "|")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.toSet()
            .orEmpty()

        val rating = (
                document.selectFirst(".avgrate")?.text()?.trim()
                    ?: document.selectFirst("#averagerate")?.text()?.trim()
                )?.toFloatOrNull()?.div(5f)?.coerceIn(0f, 1f) ?: RATING_UNKNOWN

        val isAdult = genres.any {
            it.equals("Adult", ignoreCase = true) ||
                    it.equals("Uncensored", ignoreCase = true) ||
                    it.equals("Mature", ignoreCase = true)
        }
        val contentRating = if (isAdult) ContentRating.ADULT else ContentRating.SAFE

        val description = document.selectFirst("meta[property=og:description]")
            ?.attr("content")?.trim()?.takeIf { it.isNotEmpty() }
            ?: document.selectFirst("div.dsct, div.summary__content, div.manga-summary, div.manga-content p")
                ?.text()?.trim()

        val chapters = document.select("ul.row-content-chapter li.a-h").mapNotNull { li ->
            val link = li.selectFirst("a.chapter-name, a") ?: return@mapNotNull null
            val url = link.attr("abs:href")
            val name = link.text()
            val time = li.selectFirst(".chapter-time")
            val raw = time?.attr("title")?.takeIf { it.isNotEmpty() }
                ?: time?.text().orEmpty()

            MangaChapter(
                id = generateUid(url),
                title = name,
                number = extractChapterNumber(name),
                volume = 0,
                url = url,
                scanlator = null,
                uploadDate = parseDate(raw),
                branch = null,
                source = source,
            )
        }.sortedBy { it.number }

        return manga.copy(
            title = title,
            coverUrl = thumbnail,
            largeCoverUrl = thumbnail,
            description = description,
            altTitles = altNames,
            rating = rating,
            contentRating = contentRating,
            authors = (listOfNotNull(author) + listOfNotNull(artist)).toSet(),
            tags = genres.mapTo(mutableSetOf()) {
                MangaTag(key = it.lowercase(), title = it, source = source)
            },
            state = state,
            chapters = chapters,
        )
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val document = webClient.httpGet(chapter.url).parseHtml()
        return document.select("div.read-content img").mapNotNull { img ->
            val url = img.imgAttr() ?: return@mapNotNull null
            MangaPage(
                id = generateUid(url),
                url = url,
                preview = null,
                source = source,
            )
        }
    }

    private fun parseState(raw: String?): MangaState? = when (raw?.lowercase(Locale.ROOT)) {
        "ongoing" -> MangaState.ONGOING
        "completed", "complete", "finished" -> MangaState.FINISHED
        "hiatus", "on hiatus", "on hold" -> MangaState.PAUSED
        "cancelled", "canceled", "dropped" -> MangaState.ABANDONED
        else -> null
    }

    private fun sortByValue(order: SortOrder): String = when (order) {
        SortOrder.UPDATED -> "latest"
        SortOrder.POPULARITY -> "trending"
        SortOrder.RATING -> "rating"
        SortOrder.NEWEST -> "latest"
        SortOrder.ALPHABETICAL -> "alphabet"
        else -> "latest"
    }

    private fun extractChapterNumber(title: String): Float =
        Regex("""(\d+(?:\.\d+)?)""").find(title)
            ?.groupValues?.get(1)?.toFloatOrNull() ?: 0f

    private fun parseDate(raw: String): Long {
        if (raw.isBlank()) return 0L
        return try {
            SimpleDateFormat("dd MMM yy", Locale.ENGLISH).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }.parse(raw.trim())?.time ?: 0L
        } catch (_: Exception) {
            0L
        }
    }

    private fun Element?.imgAttr(): String? = when {
        this == null -> null
        hasAttr("data-src") -> attr("abs:data-src")
        hasAttr("data-lazy-src") -> attr("abs:data-lazy-src")
        else -> attr("abs:src")
    }

    companion object {
        private val GENRES = listOf(
            "Action" to "action",
            "Adult" to "adult",
            "Adventure" to "adventure",
            "Comedy" to "comedy",
            "Cooking" to "cooking",
            "Crime" to "crime",
            "Drama" to "drama",
            "Fantasy" to "fantasy",
            "Harem" to "harem",
            "Historical" to "historical",
            "Isekai" to "isekai",
            "Magic" to "magic",
            "Magical" to "magical",
            "Manhua" to "manhua",
            "Manhwa" to "manhwa",
            "Martial Arts" to "martial-arts",
            "Mature" to "mature",
            "Mystery" to "mystery",
            "Romance" to "romance",
            "School Life" to "school-life",
            "Sci-fi" to "sci-fi",
            "Shounen" to "shounen",
            "Shounen Ai" to "shounen-ai",
            "Slice of Life" to "slice-of-life",
            "Supernatural" to "supernatural",
            "Thriller" to "thriller",
            "Uncensored" to "uncensored",
        )
    }
}
