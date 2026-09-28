package tsuki.site.en

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
import tsuki.model.SortOrder

import tsuki.util.generateUid
import tsuki.util.mapNotNullToSet
import tsuki.util.oneOrThrowIfMany
import tsuki.util.parseHtml
import tsuki.util.toAbsoluteUrl
import tsuki.util.toTitleCase
import tsuki.util.urlEncoded

import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.time.Instant
import java.util.EnumSet

@MangaSourceParser("MANHWAZ", "ManhwaZ", "en", ContentType.MANHWA)
internal class Manhwaz(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.MANHWAZ, 40) {

    override val configKeyDomain = ConfigKey.Domain("manhwaz.cc")

    override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
        super.onCreateConfig(keys)
        keys.add(userAgentKey)
    }

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.UPDATED,
        SortOrder.POPULARITY,
    )

    override val filterCapabilities: MangaListFilterCapabilities
        get() = MangaListFilterCapabilities(
            isSearchSupported = true,
            isSearchWithFiltersSupported = false,
            isMultipleTagsSupported = false,
            isTagsExclusionSupported = false,
            isYearSupported = false,
            isAuthorSearchSupported = false,
        )

    init {
        paginator.firstPage = 1
        searchPaginator.firstPage = 1
    }

    @Volatile
    private var tagsCache: Set<MangaTag>? = null

    override suspend fun getFilterOptions(): MangaListFilterOptions {
        val tags = tagsCache ?: fetchTags().also { tagsCache = it }
        return MangaListFilterOptions(
            availableTags = tags,
            availableStates = emptySet(),
            availableContentRating = emptySet(),
        )
    }

    private suspend fun fetchTags(): Set<MangaTag> {
        val doc = webClient.httpGet("https://$domain/genre").parseHtml()
        return doc.select("div.genre-directory a[href^=/genre/]").mapNotNullToSet { a ->
            val key = a.attr("href").removeSuffix("/").substringAfterLast("/")
            if (key.isEmpty()) return@mapNotNullToSet null
            val title = a.ownText().trim().toTitleCase()
            if (title.isEmpty()) return@mapNotNullToSet null
            MangaTag(key = key, title = title, source = source)
        }
    }

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        if (!filter.query.isNullOrEmpty()) {
            val doc = webClient.httpGet(
                "https://$domain/search?s=${filter.query.urlEncoded()}&page=$page",
            ).parseHtml()
            return parseGenreList(doc)
        }

        if (filter.tags.isNotEmpty()) {
            val tag = filter.tags.oneOrThrowIfMany() ?: return emptyList()
            val doc = webClient.httpGet(
                "https://$domain/genre/${tag.key}?page=$page",
            ).parseHtml()
            return parseGenreList(doc)
        }

        return when (order) {
            SortOrder.POPULARITY -> {
                val doc = webClient.httpGet("https://$domain/").parseHtml()
                parsePopularList(doc)
            }
            else -> {
                val doc = webClient.httpGet("https://$domain/?page=$page").parseHtml()
                parseLatestList(doc)
            }
        }
    }

    private fun parseCardList(doc: Document, containerClass: String, cardClass: String, coverClass: String): List<Manga> {
        return doc.select("div.$containerClass article.$cardClass").mapNotNull { article ->
            val cover = article.selectFirst("a.$coverClass") ?: return@mapNotNull null
            val href = cover.attr("href").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val title = article.selectFirst("h3 a")?.text()?.trim()
                ?.takeIf { it.isNotBlank() } ?: return@mapNotNull null

            Manga(
                id = generateUid(href),
                url = href,
                publicUrl = href.toAbsoluteUrl(domain),
                coverUrl = cover.selectFirst("img")?.imgAttr(),
                title = title,
                altTitles = emptySet(),
                rating = -1f,
                tags = emptySet(),
                authors = emptySet(),
                state = null,
                source = source,
                contentRating = null,
            )
        }
    }

    private fun parsePopularList(doc: Document) =
        parseCardList(doc, "popular-grid", "popular-card", "popular-card__cover")

    private fun parseLatestList(doc: Document) =
        parseCardList(doc, "latest-grid", "latest-card", "latest-card__cover")

    private fun parseGenreList(doc: Document) =
        parseCardList(doc, "listing-grid", "popular-card", "popular-card__cover")

    override suspend fun getDetails(manga: Manga): Manga {
        val baseUrl = manga.url.toAbsoluteUrl(domain)
        val doc = webClient.httpGet(baseUrl).parseHtml()
        val article = doc.selectFirst("article.series-page") ?: return manga

        val title = article.selectFirst("h1")?.text()?.trim() ?: manga.title
        val cover = article.selectFirst(".series-cover-frame img")?.imgAttr() ?: manga.coverUrl
        val description = article.selectFirst("p.series-summary")?.text()?.trim()

        val author = article.fact("Author(s)")
        val state = when (article.fact("status")?.lowercase()) {
            null -> null
            "ongoing" -> MangaState.ONGOING
            "completed" -> MangaState.FINISHED
            "hiatus" -> MangaState.PAUSED
            else -> null
        }

        val tags = article.select("dd.fact-genres a").mapNotNull { a ->
            val key = a.attr("href").removeSuffix("/").substringAfterLast("/")
            if (key.isBlank()) return@mapNotNull null
            MangaTag(key = key, title = a.text().trim().toTitleCase(), source = source)
        }.toSet()

        val chapters = loadAllChapters(doc, baseUrl)

        return manga.copy(
            title = title,
            coverUrl = cover,
            description = description,
            tags = tags,
            authors = setOfNotNull(author),
            state = state,
            chapters = chapters,
        )
    }

    private suspend fun loadAllChapters(firstPage: Document, firstUrl: String): List<MangaChapter> {
        val chapters = mutableListOf<MangaChapter>()
        val seenHrefs = mutableSetOf<String>()

        var currentDoc = firstPage
        var currentUrl = firstUrl

        while (true) {
            currentDoc.select("a.release-row").forEach { a ->
                val href = a.attr("href").takeIf { it.isNotBlank() } ?: return@forEach
                if (!seenHrefs.add(href)) return@forEach

                val name = a.selectFirst("strong")?.text()?.trim() ?: a.text().trim()
                val uploadDate = a.selectFirst("time")
                    ?.attr("datetime")
                    ?.takeIf { it.isNotBlank() }
                    ?.let(::parseIsoDate)
                    ?: 0L

                chapters.add(
                    MangaChapter(
                        id = generateUid(href),
                        title = name,
                        number = extractChapterNumber(name),
                        volume = 0,
                        url = href,
                        uploadDate = uploadDate,
                        scanlator = null,
                        branch = null,
                        source = source,
                    ),
                )
            }

            val nextHref = currentDoc
                .select(".pagination a.page-arrow")
                .firstOrNull { it.text().trim() == "»" }
                ?.attr("href")
                ?.takeIf { it.isNotBlank() && !it.startsWith("#") }
                ?: break

            val nextAbs = nextHref.toAbsoluteUrl(domain)
            if (nextAbs == currentUrl) break
            currentUrl = nextAbs
            currentDoc = webClient.httpGet(currentUrl).parseHtml()
        }
        return chapters.reversed()
    }

    private fun Element.fact(label: String): String? =
        select(".series-facts dl > div").firstOrNull { div ->
            div.selectFirst("dt")?.text()?.trim()?.equals(label, ignoreCase = true) == true
        }?.selectFirst("dd")?.text()?.trim()?.takeIf { it.isNotBlank() && it != "-" }

    private fun parseIsoDate(iso: String): Long =
        try { Instant.parse(iso).toEpochMilli() } catch (_: Exception) { 0L }

    private fun extractChapterNumber(name: String): Float {
        val m = Regex("""(\d+(?:\.\d+)?)""").find(name) ?: return 0f
        return m.groupValues[1].toFloatOrNull() ?: 0f
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val doc = webClient.httpGet(chapter.url.toAbsoluteUrl(domain)).parseHtml()
        val images = doc.select("figure.reader-page-image img")
        if (images.isEmpty()) return emptyList()

        return images.mapIndexedNotNull { index, img ->
            val src = img.attr("src").trim().takeIf { it.isNotBlank() }
                ?: img.attr("data-src").trim().takeIf { it.isNotBlank() }
                ?: return@mapIndexedNotNull null

            MangaPage(
                id = generateUid("${chapter.url}#$index"),
                url = src.toAbsoluteUrl(domain),
                preview = null,
                source = source,
            )
        }
    }

    private fun Element.imgAttr(): String = when {
        hasAttr("data-src") -> attr("abs:data-src")
        hasAttr("data-lazy-src") -> attr("abs:data-lazy-src")
        hasAttr("srcset") -> attr("abs:srcset").substringBefore(" ")
        hasAttr("data-cfsrc") -> attr("abs:data-cfsrc")
        else -> attr("abs:src")
    }
}
