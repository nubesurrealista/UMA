package tsuki.site.en.nsfw

import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.config.ConfigKey
import tsuki.core.PagedMangaParser
import tsuki.network.CommonHeaders

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
import tsuki.util.urlEncoded

import okhttp3.Headers
import org.json.JSONObject
import org.jsoup.nodes.Document
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.EnumSet
import java.util.Locale
import java.util.TimeZone

// TODO: status filter does not work

@MangaSourceParser("TEMPLESCAN", "Temple Scan", "en", ContentType.HENTAI)
internal class TempleScan(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.TEMPLESCAN, pageSize = 20) {

    override val configKeyDomain = ConfigKey.Domain("templetoons.com")

    override fun getRequestHeaders(): Headers =
        super.getRequestHeaders().newBuilder()
            .set(CommonHeaders.REFERER, "https://$domain/")
            .set(CommonHeaders.ORIGIN, "https://$domain")
            .set(CommonHeaders.USER_AGENT, USER_AGENT)
            .set(CommonHeaders.ACCEPT, "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
            .set(CommonHeaders.ACCEPT_LANGUAGE, "en-US,en;q=0.9")
            .set(CommonHeaders.SEC_FETCH_DEST, "document")
            .set(CommonHeaders.SEC_FETCH_MODE, "navigate")
            .set(CommonHeaders.SEC_FETCH_SITE, "none")
            .set(CommonHeaders.UPGRADE_INSECURE_REQUESTS, "1")
            .build()

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.UPDATED,
        SortOrder.POPULARITY,
        SortOrder.NEWEST,
        SortOrder.ALPHABETICAL,
    )

    override val filterCapabilities: MangaListFilterCapabilities
        get() = MangaListFilterCapabilities(
            isSearchSupported = true,
            isSearchWithFiltersSupported = true,
        )

    override suspend fun getFilterOptions() = MangaListFilterOptions(
        availableTags = emptySet(),
        availableStates = EnumSet.of(
            MangaState.ONGOING,
            MangaState.FINISHED,
            MangaState.PAUSED,
            MangaState.ABANDONED,
        ),
        availableContentTypes = emptySet(),
    )
    
    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val url = buildString {
            append("https://$domain/comics")
            append("?page=")
            append(page)

            filter.states.firstOrNull()?.let { state ->
                val statusValue = when (state) {
                    MangaState.ONGOING -> "Ongoing"
                    MangaState.FINISHED -> "Completed"
                    MangaState.PAUSED -> "Hiatus"
                    MangaState.ABANDONED -> "Canceled"
                    else -> null
                }
                if (statusValue != null) {
                    append("&status=")
                    append(statusValue)
                }
            }

            filter.query?.trim()?.takeIf { it.isNotBlank() }?.let { q ->
                append("&q=")
                append(q.urlEncoded())
            }
        }

        val doc = webClient.httpGet(url).parseHtml()
        val cards = parseCards(doc)

        return when (order) {
            SortOrder.ALPHABETICAL -> cards.sortedBy { it.title.lowercase() }
            else -> cards
        }
    }

    private fun parseCards(doc: Document): List<Manga> =
        doc.select("div.grid > div")
            .filter { it.selectFirst("a[href*=/comic/]") != null }
            .mapNotNull { card ->
                val link = card.selectFirst("a[href*=/comic/]") ?: return@mapNotNull null
                val href = link.attr("href").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val title = card.selectFirst("h2")?.text()?.trim().orEmpty()
                if (title.isBlank()) return@mapNotNull null

                val cover = card.selectFirst("a[href*=/comic/] img")
                    ?.let { it.attr("src").ifBlank { it.attr("data-src") } }
                    ?.trim()?.takeIf { it.isNotBlank() }

                Manga(
                    id = generateUid(href),
                    url = href,
                    publicUrl = "https://$domain$href",
                    title = title,
                    altTitles = emptySet(),
                    coverUrl = cover,
                    authors = emptySet(),
                    state = null,
                    contentRating = null,
                    tags = emptySet(),
                    rating = RATING_UNKNOWN,
                    source = source,
                )
            }
            .distinctBy { it.url }
    
    override suspend fun getDetails(manga: Manga): Manga {
        val slug = manga.url.removePrefix("/comic/").trimEnd('/')
        val doc = webClient.httpGet("https://$domain/comic/$slug").parseHtml()

        val seriesLd = doc.extractComicSeriesLd()

        val genres = seriesLd?.optJSONArray("genre")?.let { arr ->
            (0 until arr.length()).map { arr.getString(it) }
        }.orEmpty()
        val adult = genres.any { it.equals("+18", ignoreCase = true) }

        val altName = seriesLd?.optString("alternateName")?.takeIf { it.isNotBlank() }
        val authorName = seriesLd?.optJSONObject("author")?.optString("name")?.takeIf { it.isNotBlank() }

        val rawDescription = doc.synopsis()
            ?: seriesLd?.optString("description")?.takeIf { it.isNotBlank() }

        val description = buildString {
            append(rawDescription.orEmpty())
            if (altName != null) {
                if (isNotEmpty()) append("\n\n")
                append("Alternative Name: ").append(altName)
            }
        }

        val statusText = doc.select("ul[aria-label=\"Series stats\"] li span")
            .map { it.text().trim() }
            .firstOrNull { text ->
                text.equals("Ongoing", ignoreCase = true) ||
                        text.equals("Completed", ignoreCase = true) ||
                        text.equals("Hiatus", ignoreCase = true) ||
                        text.equals("Canceled", ignoreCase = true) ||
                        text.equals("Dropped", ignoreCase = true)
            }

        val state = when (statusText?.lowercase()) {
            "ongoing" -> MangaState.ONGOING
            "completed" -> MangaState.FINISHED
            "hiatus" -> MangaState.PAUSED
            "canceled", "dropped" -> MangaState.ABANDONED
            else -> null
        }

        val chapters = parseChaptersFromHtml(doc)

        return manga.copy(
            title = seriesLd?.optString("name")?.takeIf { it.isNotBlank() } ?: slug,
            altTitles = altName?.let { setOf(it) } ?: emptySet(),
            coverUrl = seriesLd?.optString("image")?.takeIf { it.isNotBlank() } ?: manga.coverUrl,
            description = description,
            authors = authorName?.let { setOf(it) } ?: emptySet(),
            state = state,
            contentRating = if (adult) ContentRating.ADULT else ContentRating.SAFE,
            tags = buildSet {
                if (adult) add(MangaTag("adult", "Adult", source))
                genres.filterNot { it.equals("+18", ignoreCase = true) }.forEach {
                    add(MangaTag(it.lowercase(), it, source))
                }
            },
            chapters = chapters,
        )
    }

    private fun parseChaptersFromHtml(doc: Document): List<MangaChapter> {
        val links = doc.select("ul#chapter-list li a[href*=chapter-]")
            .filterNot { a ->
                a.select("span").any { span ->
                    span.ownText().trim().equals("Premium", ignoreCase = true)
                }
            }
        if (links.isEmpty()) return emptyList()

        return links.mapNotNull { a ->
            val href = a.attr("href").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val numMatch = Regex("""chapter-([\d.]+)(?:/|$)""").find(href) ?: return@mapNotNull null
            val numStr = numMatch.groupValues[1]
            val number = numStr.toFloatOrNull() ?: return@mapNotNull null

            val title = a.selectFirst("span.font-semibold")?.text()?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: "Chapter $numStr"

            val uploadDate = a.selectFirst("time[datetime]")
                ?.attr("datetime")
                ?.takeIf { it.isNotBlank() }
                ?.let { parseDate(it) }
                ?: 0L

            MangaChapter(
                id = generateUid(href),
                title = title,
                number = number,
                volume = 0,
                url = href,
                uploadDate = uploadDate,
                source = source,
                scanlator = null,
                branch = null,
            )
        }.distinctBy { it.url }.sortedBy { it.number }
    }
    
    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val url = "https://$domain${chapter.url}"
        val doc = webClient.httpGet(url).parseHtml()

        val htmlImgs = doc.select("main img, article img, figure img, .reader img, [class*=reader] img")
            .mapNotNull { img ->
                val src = (img.absUrl("src").takeIf { it.isNotBlank() }
                    ?: img.absUrl("data-src").takeIf { it.isNotBlank() }
                    ?: img.absUrl("data-lazy-src").takeIf { it.isNotBlank() })
                    ?.takeIf { isValidReaderImage(it) }
                    ?: return@mapNotNull null
                src
            }
            .distinct()

        if (htmlImgs.isNotEmpty()) {
            return htmlImgs.map { MangaPage(id = generateUid(it), url = it, preview = null, source = source) }
        }

        val html = doc.html().replace("\\/", "/")
        val fromRegex = IMAGE_URL_REGEX.findAll(html)
            .map { it.value.replace("\\/", "/") }
            .filter { isValidReaderImage(it) }
            .distinct()
            .toList()

        return fromRegex.map { MangaPage(id = generateUid(it), url = it, preview = null, source = source) }
    }

    private fun isValidReaderImage(url: String): Boolean {
        val lower = url.lowercase()
        val hasImageExt = lower.contains(".webp") || lower.contains(".jpg") ||
                lower.contains(".jpeg") || lower.contains(".png") || lower.contains(".avif")
        if (!hasImageExt) return false

        val looksLikeContent = lower.contains("media.") || lower.contains("cdn.") ||
                lower.contains("/file/") || lower.contains("/chapter")
        if (!looksLikeContent) return false

        val isChrome = lower.contains("/cover") || lower.contains("logo") ||
                lower.contains("banner") || lower.contains("favicon") || lower.contains("avatar") ||
                lower.contains("thumbnail")
        return !isChrome
    }
    
    private fun parseDate(text: String?): Long {
        if (text.isNullOrBlank()) return 0L
        val lower = text.lowercase().trim()

        if (lower.contains("ago")) {
            val num = Regex("""(\d+)""").find(lower)?.groupValues?.get(1)?.toIntOrNull() ?: return 0L
            val cal = Calendar.getInstance()
            when {
                lower.contains("second") -> cal.add(Calendar.SECOND, -num)
                lower.contains("minute") -> cal.add(Calendar.MINUTE, -num)
                lower.contains("hour") -> cal.add(Calendar.HOUR, -num)
                lower.contains("day") -> cal.add(Calendar.DAY_OF_MONTH, -num)
                lower.contains("week") -> cal.add(Calendar.DAY_OF_MONTH, -num * 7)
                lower.contains("month") -> cal.add(Calendar.MONTH, -num)
                lower.contains("year") -> cal.add(Calendar.YEAR, -num)
                else -> return 0L
            }
            return cal.timeInMillis
        }

        try {
            return java.time.Instant.parse(text).toEpochMilli()
        } catch (_: Exception) {}

        for (pattern in ABSOLUTE_DATE_PATTERNS) {
            try {
                val fmt = SimpleDateFormat(pattern, Locale.ENGLISH).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }
                return fmt.parse(text)?.time ?: continue
            } catch (_: Exception) {}
        }
        return 0L
    }

    private fun Document.extractComicSeriesLd(): JSONObject? =
        select("script[type=application/ld+json]").mapNotNull { script ->
            runCatching { JSONObject(script.data()) }.getOrNull()
        }.firstNotNullOfOrNull { root ->
            when {
                root.optString("@type") == "ComicSeries" -> root
                root.has("@graph") -> root.optJSONArray("@graph")?.let { graph ->
                    (0 until graph.length())
                        .mapNotNull { graph.optJSONObject(it) }
                        .firstOrNull { it.optString("@type") == "ComicSeries" }
                }
                else -> null
            }
        }

    private fun Document.synopsis(): String? =
        selectFirst("#series-synopsis-text")?.let { el ->
            val ps = el.select("p")
            if (ps.isNotEmpty()) ps.joinToString("\n\n") { it.text() } else el.text()
        }?.takeIf { it.isNotBlank() }

    companion object {
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/136.0.0.0 Safari/537.36"

        private val IMAGE_URL_REGEX = Regex(
            """https?://[^"\s]+?\.(?:jpe?g|png|webp|avif|gif)""",
            RegexOption.IGNORE_CASE,
        )

        private val ABSOLUTE_DATE_PATTERNS = listOf(
            "MMM d, yyyy",
            "MMMM d, yyyy",
            "yyyy-MM-dd",
            "d MMM yyyy",
            "MM/dd/yyyy",
        )
    }
}
