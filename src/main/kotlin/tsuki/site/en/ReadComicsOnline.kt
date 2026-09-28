package tsuki.site.en

import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.parsers.MmrcmsParser

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
import tsuki.util.attrAsRelativeUrl
import tsuki.util.mapChapters
import tsuki.util.mapNotNullToSet
import tsuki.util.nullIfEmpty
import tsuki.util.parseHtml
import tsuki.util.parseSafe
import tsuki.util.requireSrc
import tsuki.util.src
import tsuki.util.textOrNull
import tsuki.util.toAbsoluteUrl
import tsuki.util.toRelativeUrl
import tsuki.util.urlEncoded

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.jsoup.nodes.Document
import java.text.SimpleDateFormat
import java.util.EnumSet
import java.util.Locale

@MangaSourceParser("READCOMICSONLINE", "ReadComicsOnline.ru", "en", ContentType.COMICS)
internal class ReadComicsOnline(context: MangaLoaderContext) :
    MmrcmsParser(context, MangaParserSource.READCOMICSONLINE, "readcomicsonline.ru") {

    private val chapterDateFormat = SimpleDateFormat("d MMM yyyy", Locale.US)

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.POPULARITY,
        SortOrder.UPDATED,
    )

    override val filterCapabilities: MangaListFilterCapabilities
        get() = MangaListFilterCapabilities(
            isSearchSupported = true,
            isSearchWithFiltersSupported = true,
            isMultipleTagsSupported = false,
        )

    override suspend fun getFilterOptions(): MangaListFilterOptions {
        val doc = webClient.httpGet("https://$domain/advanced-search").parseHtml()

        val categories = doc.select("select[name=category] option")
            .mapNotNullToSet { option ->
                val id = option.attr("value").trim()
                val name = option.text().trim()
                if (id.isEmpty() || name.isEmpty()) null
                else MangaTag(key = id, title = name, source = source)
            }

        return MangaListFilterOptions(
            availableStates = EnumSet.of(MangaState.ONGOING, MangaState.FINISHED),
            availableTags = categories,
            availableContentTypes = EnumSet.of(
                ContentType.MANGA, // DC Comics
                ContentType.MANHWA, // Marvel Comics
                ContentType.MANHUA, // Other Comics
            ),
        )
    }

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val hasFilters = !filter.query.isNullOrEmpty()
                || filter.states.isNotEmpty()
                || filter.types.isNotEmpty()
                || filter.tags.isNotEmpty()

        val url = if (hasFilters) {
            buildAdvancedSearchUrl(page, filter)
        } else {
            val sort = when (order) {
                SortOrder.UPDATED -> "latest"
                else -> "views"
            }
            "https://$domain/comic-list?sort=$sort&page=$page"
        }

        val doc = webClient.httpGet(url).parseHtml()
        return parseComicListCards(doc).ifEmpty { parseSearchResultCards(doc) }
    }

    private fun buildAdvancedSearchUrl(page: Int, filter: MangaListFilter): String {
        val params = buildList {
            filter.query?.takeIf { it.isNotBlank() }?.let {
                add("name=${it.urlEncoded()}")
            }

            filter.states.firstOrNull()?.let { state ->
                val id = when (state) {
                    MangaState.ONGOING -> "1"
                    MangaState.FINISHED -> "2"
                    else -> null
                }
                if (id != null) add("status_id=$id")
            }

            filter.types.firstOrNull()?.let { type ->
                val id = when (type) {
                    ContentType.MANGA -> "1"
                    ContentType.MANHWA -> "2"
                    ContentType.MANHUA -> "3"
                    else -> null
                }
                if (id != null) add("type_id=$id")
            }

            filter.tags.firstOrNull()?.let { tag ->
                add("category=${tag.key}")
            }

            add("page=$page")
        }
        return "https://$domain/advanced-search?" + params.joinToString("&")
    }

    private fun parseComicListCards(doc: Document): List<Manga> =
        doc.select("div.comic-list-layout .grid > .group").mapNotNull { element ->
            val anchor = element.selectFirst("a.block.text-sm.font-semibold")
                ?: return@mapNotNull null
            val href = anchor.attrAsRelativeUrl("href")
            Manga(
                id = generateUid(href),
                title = anchor.text(),
                altTitles = emptySet(),
                url = href,
                publicUrl = href.toAbsoluteUrl(domain),
                rating = RATING_UNKNOWN,
                contentRating = if (isNsfwSource) ContentRating.ADULT else null,
                coverUrl = guessCover(href, element.selectFirst("img")?.src()),
                tags = emptySet(),
                state = null,
                authors = emptySet(),
                source = source,
            )
        }

    private fun parseSearchResultCards(doc: Document): List<Manga> =
        doc.select("a.group[href*=/comic/]").mapNotNull { anchor ->
            val href = anchor.attrAsRelativeUrl("href")
            if (href.isBlank()) return@mapNotNull null

            val title = anchor.selectFirst("p")?.text()?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: anchor.selectFirst("img")?.attr("alt")?.trim()
                    ?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null

            val cover = anchor.selectFirst(".rc-cover img, img")
                ?.let { it.attr("src").ifBlank { it.attr("data-src") } }
                ?.trim()?.takeIf { it.isNotBlank() }

            Manga(
                id = generateUid(href),
                title = title,
                altTitles = emptySet(),
                url = href,
                publicUrl = href.toAbsoluteUrl(domain),
                rating = RATING_UNKNOWN,
                contentRating = if (isNsfwSource) ContentRating.ADULT else null,
                coverUrl = cover ?: guessCover(href, null),
                tags = emptySet(),
                state = null,
                authors = emptySet(),
                source = source,
            )
        }

    override suspend fun getDetails(manga: Manga): Manga = coroutineScope {
        val fullUrl = manga.url.toAbsoluteUrl(domain)
        val doc = webClient.httpGet(fullUrl).parseHtml()
        val chaptersDeferred = async { getChapters(doc, manga.title) }

        val title = doc.selectFirst("h1.text-2xl")?.textOrNull() ?: manga.title

        val coverFromPage = doc.selectFirst("img.w-full.rounded-xl")
            ?.attr("src")
            ?.nullIfEmpty()
            ?.takeUnless { it.contains("cover_missing", ignoreCase = true) }
        val coverUrl = coverFromPage
            ?: guessCover(manga.url, null)
            ?: manga.coverUrl

        val description = doc.selectFirst("div.bg-ink-900 > p.text-sm")?.textOrNull()

        val statusText = doc.select("div.mt-4.flex.flex-wrap.gap-2 > span")
            .mapNotNull { it.textOrNull() }
            .firstOrNull { it.lowercase(Locale.US) in STATUS_KEYWORDS }

        val publisher = doc.selectFirst("span.rc-chip")?.textOrNull()

        val tags = doc.select("dl div:contains(Genres:) a").mapNotNullToSet { a ->
            val key = a.attr("href").removeSuffix("/").substringAfterLast('/')
            if (key.isBlank()) return@mapNotNullToSet null
            MangaTag(key = key, title = a.text(), source = source)
        }

        manga.copy(
            title = title,
            coverUrl = coverUrl,
            description = description,
            state = parseState(statusText),
            tags = tags,
            authors = setOfNotNull(publisher),
            chapters = chaptersDeferred.await(),
        )
    }

    private fun parseState(value: String?): MangaState? =
        when (value?.lowercase(Locale.US)) {
            "complete", "completed" -> MangaState.FINISHED
            "ongoing", "on going" -> MangaState.ONGOING
            "dropped", "cancelled", "canceled" -> MangaState.ABANDONED
            else -> null
        }

    private fun getChapters(doc: Document, mangaTitle: String): List<MangaChapter> {
        return doc.select(".overflow-hidden.border-ink-600 > a")
            .mapChapters(reversed = true) { i, element ->
                val href = element.attrAsRelativeUrl("href")
                val chapterName = element.selectFirst(".text-brand-400")?.textOrNull()
                    ?: element.text()
                MangaChapter(
                    id = generateUid(href),
                    title = cleanChapterName(mangaTitle, chapterName),
                    number = i + 1f,
                    volume = 0,
                    url = href,
                    uploadDate = chapterDateFormat.parseSafe(
                        element.selectFirst(".text-slate-500")?.text(),
                    ),
                    source = source,
                    scanlator = null,
                    branch = null,
                )
            }
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val doc = webClient.httpGet(chapter.url.toAbsoluteUrl(domain)).parseHtml()
        return doc.select("#reader-all img").mapNotNull { img ->
            val url = img.requireSrc().toRelativeUrl(domain)
            MangaPage(id = generateUid(url), url = url, preview = null, source = source)
        }
    }

    private fun cleanChapterName(mangaTitle: String, chapterName: String): String {
        return chapterName
            .removePrefix(mangaTitle)
            .trimStart(' ', '-', ':')
            .nullIfEmpty()
            ?: chapterName
    }

    override fun guessCover(mangaUrl: String, url: String?): String? {
        url?.takeUnless { it.contains("/cover/cover_missing.", ignoreCase = true) }?.let {
            return it
        }
        val slug = mangaUrl.removeSuffix("/").substringAfterLast('/').nullIfEmpty() ?: return null
        return "https://$domain/uploads/manga/$slug/cover/cover_250x350.jpg"
    }

    companion object {
        private val STATUS_KEYWORDS = setOf(
            "ongoing", "on going", "completed", "complete",
            "dropped", "cancelled", "canceled", "hiatus",
        )
    }
}
