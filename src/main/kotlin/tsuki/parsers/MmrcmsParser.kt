package tsuki.parsers

import tsuki.MangaLoaderContext
import tsuki.config.ConfigKey
import tsuki.core.PagedMangaParser
import tsuki.network.CommonHeaders

import tsuki.model.RATING_UNKNOWN
import tsuki.model.ContentRating
import tsuki.model.Manga
import tsuki.model.MangaChapter
import tsuki.model.MangaListFilter
import tsuki.model.MangaListFilterCapabilities
import tsuki.model.MangaListFilterOptions
import tsuki.model.MangaPage
import tsuki.model.MangaParserSource
import tsuki.model.MangaTag
import tsuki.model.MangaState
import tsuki.model.SortOrder

import tsuki.util.generateUid
import tsuki.util.toAbsoluteUrl
import tsuki.util.attrAsRelativeUrl
import tsuki.util.parseHtml
import tsuki.util.urlEncoded
import tsuki.util.mapChapters
import tsuki.util.mapNotNullToSet
import tsuki.util.parseSafe
import tsuki.util.src
import tsuki.util.requireSrc
import tsuki.util.textOrNull
import tsuki.util.toRelativeUrl
import tsuki.util.removeSuffix

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.select.Elements
import java.text.SimpleDateFormat
import java.util.EnumSet
import java.util.Locale

abstract class MmrcmsParser(
    context: MangaLoaderContext,
    source: MangaParserSource,
    domain: String,
    pageSize: Int = 20,
) : PagedMangaParser(context, source, pageSize) {

    override val configKeyDomain = ConfigKey.Domain(domain)

    override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
        super.onCreateConfig(keys)
        keys.add(userAgentKey)
    }

    override fun getRequestHeaders() = super.getRequestHeaders().newBuilder()
        .set(CommonHeaders.REFERER, "https://$domain/")
        .build()

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.POPULARITY,
        SortOrder.POPULARITY_ASC,
        SortOrder.UPDATED,
        SortOrder.ALPHABETICAL,
        SortOrder.ALPHABETICAL_DESC,
    )

    override val filterCapabilities: MangaListFilterCapabilities
        get() = MangaListFilterCapabilities(
            isSearchSupported = true,
            isSearchWithFiltersSupported = true,
        )

    init {
        paginator.firstPage = 1
        searchPaginator.firstPage = 1
    }

    protected open val itemPath = "manga"
    protected open val dateFormat = SimpleDateFormat("d MMM. yyyy", Locale.US)
    protected open val fetchFilterOptions = true
    protected open val supportsAdvancedSearch = true
    protected open val detailsTitleSelector = ".listmanga-header, .widget-title"
    protected open val chapterNamePrefix = ""
    protected open val chapterString = "Chapter"

    protected open val imgUpdated = "/cover/cover_250x350.jpg"
    protected open val listUrl = "filterList"
    protected open val tagUrl = "manga-list"
    protected open val datePattern = "dd MMM. yyyy"

    protected open val selectDesc = "div.well"
    protected open val selectState = "dt:contains(Statut)"
    protected open val selectAlt = "dt:contains(Autres noms)"
    protected open val selectAut = "dt:contains(Auteur(s))"
    protected open val selectTag = "dt:contains(Catégories)"
    protected open val selectDate = "div.date-chapter-title-rtl"
    protected open val selectChapter = "ul.chapters > li:not(.btn)"
    protected open val selectPage = "div#all img"

    @Volatile
    private var tagsCache: Set<MangaTag>? = null
    private val tagsMutex = Mutex()

    override suspend fun getFilterOptions(): MangaListFilterOptions {
        val tags = if (fetchFilterOptions) {
            tagsCache ?: fetchAvailableTags().also { tagsCache = it }
        } else {
            emptySet()
        }
        return MangaListFilterOptions(
            availableTags = tags,
            availableStates = EnumSet.of(
                MangaState.ONGOING,
                MangaState.FINISHED,
                MangaState.PAUSED,
                MangaState.ABANDONED,
            ),
        )
    }

    protected open suspend fun fetchAvailableTags(): Set<MangaTag> {
        val doc = webClient.httpGet("https://$domain/$tagUrl/").parseHtml()
        return doc.select("ul.list-category li").mapNotNullToSet { li ->
            val a = li.selectFirst("a") ?: return@mapNotNullToSet null
            val href = a.attr("href").substringAfterLast("cat=")
            if (href.isBlank()) return@mapNotNullToSet null
            MangaTag(key = href, title = a.text(), source = source)
        }
    }

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        if (order == SortOrder.UPDATED && (filter.query != null || filter.tags.isNotEmpty())) {
            throw IllegalArgumentException(
                "Sorting by update with filters is not supported by this source.",
            )
        }

        if (order == SortOrder.UPDATED) {
            val url = "https://$domain/latest-release?page=$page"
            return parseMangaListUpdated(webClient.httpGet(url).parseHtml())
        }

        val url = buildString {
            append("https://")
            append(domain)
            append('/')
            append(listUrl)
            append("/?page=")
            append(page)

            append("&author=&tag=&alpha=")
            filter.query?.let { append(it.urlEncoded()) }

            append("&cat=")
            filter.tags.firstOrNull()?.let { append(it.key) }

            append("&sortBy=")
            when (order) {
                SortOrder.POPULARITY -> append("views&asc=false")
                SortOrder.POPULARITY_ASC -> append("views&asc=true")
                SortOrder.ALPHABETICAL -> append("name&asc=true")
                SortOrder.ALPHABETICAL_DESC -> append("name&asc=false")
                else -> append("name&asc=true")
            }
        }
        return parseMangaList(webClient.httpGet(url).parseHtml())
    }

    protected open fun parseMangaList(doc: Document): List<Manga> =
        doc.select("div.media").mapNotNull { div ->
            val href = div.selectFirst("a")?.attrAsRelativeUrl("href") ?: return@mapNotNull null
            Manga(
                id = generateUid(href),
                url = href,
                publicUrl = href.toAbsoluteUrl(domain),
                coverUrl = div.selectFirst("img")?.src(),
                title = div.selectFirst("div.media-body h5")?.text().orEmpty(),
                altTitles = emptySet(),
                rating = div.selectFirst("span")?.ownText()?.toFloatOrNull()?.div(5f) ?: RATING_UNKNOWN,
                tags = emptySet(),
                authors = emptySet(),
                state = null,
                source = source,
                contentRating = if (isNsfwSource) ContentRating.ADULT else null,
            )
        }

    protected open fun parseMangaListUpdated(doc: Document): List<Manga> =
        doc.select("div.manga-item").mapNotNull { div ->
            val href = div.selectFirst("a")?.attrAsRelativeUrl("href") ?: return@mapNotNull null
            val deeplink = href.substringAfterLast("/")
            Manga(
                id = generateUid(href),
                url = href,
                publicUrl = href.toAbsoluteUrl(domain),
                coverUrl = "https://$domain/uploads/manga/$deeplink$imgUpdated",
                title = div.selectFirst("h3 a")?.text().orEmpty(),
                altTitles = emptySet(),
                rating = RATING_UNKNOWN,
                tags = emptySet(),
                authors = emptySet(),
                state = null,
                source = source,
                contentRating = if (isNsfwSource) ContentRating.ADULT else null,
            )
        }

    override suspend fun getDetails(manga: Manga): Manga = coroutineScope {
        val fullUrl = manga.url.toAbsoluteUrl(domain)
        val doc = webClient.httpGet(fullUrl).parseHtml()
        val body = doc.body().selectFirst("dl.dl-horizontal")

        val chaptersDeferred = async { loadChapters(doc) }

        val desc = doc.selectFirst(selectDesc)?.text().orEmpty()
        val stateDiv = body?.selectFirst(selectState)?.nextElementSibling()
        val state = stateDiv?.let {
            when (it.text()) {
                in ongoing -> MangaState.ONGOING
                in finished -> MangaState.FINISHED
                else -> null
            }
        }
        val alt = doc.body().selectFirst(selectAlt)?.nextElementSibling()?.textOrNull()
        val author = doc.body().selectFirst(selectAut)?.nextElementSibling()?.textOrNull()
        val tags = doc.body().selectFirst(selectTag)?.nextElementSibling()?.select("a").orEmpty()

        manga.copy(
            tags = tags.mapNotNullToSet { a ->
                MangaTag(
                    key = a.attr("href").removeSuffix('/').substringAfterLast('/'),
                    title = a.text(),
                    source = source,
                )
            },
            authors = setOfNotNull(author),
            description = desc,
            altTitles = setOfNotNull(alt),
            state = state,
            chapters = chaptersDeferred.await(),
        )
    }

    protected open suspend fun loadChapters(doc: Document): List<MangaChapter> {
        val fmt = SimpleDateFormat(datePattern, Locale.US)
        return doc.body().select(selectChapter)
            .mapChapters(reversed = true) { i, li ->
                val a = li.selectFirst("a") ?: return@mapChapters null
                val href = a.attrAsRelativeUrl("href")
                val dateText = li.selectFirst(selectDate)?.text()
                MangaChapter(
                    id = generateUid(href),
                    title = li.selectFirst("h5")?.textOrNull(),
                    number = i + 1f,
                    volume = 0,
                    url = href,
                    uploadDate = fmt.parseSafe(dateText),
                    source = source,
                    scanlator = null,
                    branch = null,
                )
            }
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val doc = webClient.httpGet(chapter.url.toAbsoluteUrl(domain)).parseHtml()
        return doc.select(selectPage).mapNotNull { img ->
            val url = img.requireSrc().toRelativeUrl(domain)
            MangaPage(id = generateUid(url), url = url, preview = null, source = source)
        }
    }

    protected fun String?.toMangaState(): MangaState? = when (this?.lowercase(Locale.US)) {
        "complete", "completed" -> MangaState.FINISHED
        "ongoing", "on going" -> MangaState.ONGOING
        "dropped", "cancelled", "canceled" -> MangaState.ABANDONED
        else -> null
    }

    @JvmField
    protected val ongoing: Set<String> = hashSetOf(
        "On Going",
        "Ongoing",
        "En cours",
        "En curso",
        "DEVAM EDİYOR",
        "مستمرة",
    )

    @JvmField
    protected val finished: Set<String> = hashSetOf(
        "Completed",
        "Completo",
        "Complete",
        "Terminé",
        "TAMAMLANDI",
        "مكتملة",
    )

    protected open fun guessCover(mangaUrl: String, url: String?): String? =
        if (url == null || url.endsWith("no-image.png")) {
            "https://$domain/uploads/manga/${mangaUrl.substringAfterLast('/')}/cover/cover_250x350.jpg"
        } else {
            url
        }

    protected fun Element.imgAttr(): String = when {
        hasAttr("data-background-image") -> absUrl("data-background-image")
        hasAttr("data-cfsrc") -> absUrl("data-cfsrc")
        hasAttr("data-lazy-src") -> absUrl("data-lazy-src")
        hasAttr("data-src") -> absUrl("data-src")
        else -> absUrl("src")
    }

    protected fun Elements.textWithNewlines() = run {
        select("p, br").prepend("\\n")
        text().replace("\\n", "\n").replace("\n ", "\n")
    }
}
