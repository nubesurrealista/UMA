package tsuki.site.all

import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.config.ConfigKey
import tsuki.core.PagedMangaParser

import tsuki.model.ContentRating
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
import tsuki.util.parseRaw

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import okhttp3.Headers.Companion.toHeaders
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.parser.Parser
import java.util.EnumSet
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * BETA
 * idk
 * its slow but works, need to rework graph queries
 */

private object XComicQueries {
    const val TITLE_BROWSE = $$"""
        query get_title_browse($select: Title_Browse_Select) {
            get_title_browse_items(select: $select) {
                id
                data {
                    title native_title romanized_title original_language
                    translated_languages type cover_local_url cover_url
                    comic_ids chap_last_public_at
                }
            }
        }
    """

    const val TITLE_NODE = $$"""
        query get_title_titleNode($id: ID!) {
            get_title_titleNode(id: $id) {
                id
                data {
                    title alt_titles native_title romanized_title original_language
                    translated_languages authors artists year type status description
                    cover_local_url cover_url urlPath total_comics total_chapters
                    total_follows total_reviews total_comments vote_avg vote_users
                    chap_last_public_at is_merged merged_to comic_ids
                    content_rating_id type_id demographic_ids genre_ids format_ids
                    tracking_sites {
                        anilist myanimelist mangaupdates kitsu animeplanet shikimori mangabaka
                    }
                }
            }
        }
    """

    const val COMIC_NODE = $$"""
        query get_comicNode($id: ID!) {
            get_comicNode(id: $id) {
                id
                data {
                    id name subName altNames authors artists
                    originalLanguage translatedLanguage originalStatus uploadStatus
                    type demographics contentRating genres tags publishers dbStatus isPublic
                    follows reviews comments_total score_val is_hot is_new
                    chaps_normal dateUpload
                    chapterNode_up_to { id data { dname datePublic } }
                    summary { text }
                    extraInfo { text }
                    urlPath urlCover
                }
            }
        }
    """

    const val COMIC_PROBE = $$"""
        query get_comicNode($id: ID!) {
            get_comicNode(id: $id) {
                id
                data {
                    name subName dbStatus isPublic translatedLanguage chaps_normal urlPath urlCover
                }
            }
        }
    """

    const val CHAPTER_LIST = $$"""
        query get_comic_chapterList_fullList($select: Select_Comic_ChapterList) {
            get_comic_chapterList_fullList(select: $select) {
                paging { next total }
                items {
                    id
                    data {
                        id comicId dbStatus isFinal volume serial dname title urlPath
                        dateCreate datePublic dateModify chaNum volNum count_images is_new
                        srcName profileNodes { data { name } }
                    }
                }
            }
        }
    """

    const val CHAPTER_PAGES = $$"""
        query($id: ID!) {
            get_chapterNode(id: $id) { id data { imageUrls } }
        }
    """
}

private fun JSONObject.strOrNull(k: String): String? =
    if (has(k) && !isNull(k)) optString(k).takeIf { it.isNotEmpty() } else null

private fun JSONObject.longOrNull(k: String): Long? =
    if (has(k) && !isNull(k)) optLong(k) else null

private fun JSONObject.intOrNull(k: String): Int? =
    if (has(k) && !isNull(k)) optInt(k) else null

private fun JSONObject.floatOrNull(k: String): Float? =
    if (has(k) && !isNull(k)) optDouble(k).toFloat() else null

private fun JSONObject.boolOrNull(k: String): Boolean? =
    if (has(k) && !isNull(k)) optBoolean(k) else null

private fun JSONObject.objOrNull(k: String): JSONObject? =
    if (has(k) && !isNull(k)) optJSONObject(k) else null

private fun JSONObject.arrOrNull(k: String): JSONArray? =
    if (has(k) && !isNull(k)) optJSONArray(k) else null

private fun JSONObject.stringList(k: String): List<String> {
    val arr = arrOrNull(k) ?: return emptyList()
    return (0 until arr.length()).mapNotNull { i ->
        if (arr.isNull(i)) null else arr.optString(i).takeIf { it.isNotEmpty() }
    }
}

private fun JSONArray.objects(): List<JSONObject> =
    (0 until length()).mapNotNull { optJSONObject(it) }

private fun JSONArray.strings(): List<String> =
    (0 until length()).mapNotNull { i ->
        if (isNull(i)) null else optString(i).takeIf { it.isNotEmpty() }
    }

// =====================================================================
// Locale → XCOMIC language code
// =====================================================================

private fun Locale.toXComicLangCode(): String? {
    if (this == Locale.ROOT) return null
    return when {
        language == "pt" && country.equals("BR", true) -> "pt_br"
        language == "es" && country == "419" -> "es_419"
        language == "zh" && country.equals("TW", true) -> "zh_hk"
        language == "zh" -> "zh"
        language == "other" -> "_t"
        language.isBlank() -> null
        else -> language
    }
}

private fun xComicLangDisplayName(code: String): String =
    XCOMIC_LANGS.firstOrNull { it.second == code }?.first ?: code.uppercase(Locale.ROOT)

// =====================================================================
// Parser
// =====================================================================

@MangaSourceParser("XCOMIC", "XCOMIC")
internal class XComic(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.XCOMIC, pageSize = BROWSE_PAGE_SIZE) {

    override val configKeyDomain = ConfigKey.Domain("xcomic.me")

    init {
        paginator.firstPage = 0
        searchPaginator.firstPage = 0
    }

    private val probeCache = ConcurrentHashMap<String, ComicProbe>()
    private val titleFreshness = ConcurrentHashMap<String, Long>()


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
            isTagsExclusionSupported = true,
        )

    override suspend fun getFilterOptions() = MangaListFilterOptions(
        availableTags = GENRE_TAGS.mapTo(mutableSetOf()) { (name, slug) ->
            MangaTag(key = slug, title = name, source = source)
        },
        availableStates = EnumSet.of(
            MangaState.ONGOING,
            MangaState.FINISHED,
            MangaState.PAUSED,
            MangaState.ABANDONED,
        ),
        availableLocales = XCOMIC_LOCALES,
    )

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val apiPage = page + 1

        val filterApiLang = filter.locale?.toXComicLangCode()
        val allLanguages = filterApiLang == null

        val idMatch = ID_QUERY.matchEntire(filter.query?.trim().orEmpty())
        if (idMatch != null) {
            val id = idMatch.groupValues[1].substringBefore("-")
            val node = fetchTitleNode(id) ?: return emptyList()
            val browseNode: TitleBrowseNode = node.toBrowseNode()
            val rows = flattenTitle(browseNode, filterApiLang, forceFresh = true)
            return rows.map { (tid, cid, p) ->
                p.toBrowseManga(domain, tid, cid, browseNode, allLanguages)
            }
        }

        val variables = JSONObject().apply {
            put("word", filter.query.orEmpty())
            put("page", apiPage)
            put("size", BROWSE_PAGE_SIZE)
            put("init", (apiPage - 1) * BROWSE_PAGE_SIZE)
            put("sortby", sortFor(order))
            put("where", "browse")

            if (filter.year > 0) {
                put("releaseYearMin", filter.year)
                put("releaseYearMax", filter.year)
            } else {
                put("releaseYearMin", JSONObject.NULL)
                put("releaseYearMax", JSONObject.NULL)
            }

            put("incTypes", JSONArray())
            put("incDemographics", JSONArray())
            put("incContentRatings", JSONArray(filter.contentRating.mapNotNull { it.toApiRating() }))
            put("incOLangs", JSONArray())
            put("incTLangs", if (allLanguages) JSONArray() else JSONArray(listOf(filterApiLang)))
            put("incGenres", JSONArray(filter.tags.map { it.key }))
            put("excGenres", JSONArray(filter.tagsExclude.map { it.key }))
            put("incGenresMode", JSONObject.NULL)
            put("excGenresMode", JSONObject.NULL)
            put("origStatus", JSONArray(filter.states.mapNotNull { it.toApiStatus() }))
            put("chapCount", JSONObject.NULL)
            put("ignoreGlobalGenres", false)
            put("ignoreGlobalULangs", false)
            put("ignoreGlobalBlocks", false)
        }

        val data = postGraphQL(
            XComicQueries.TITLE_BROWSE,
            JSONObject().apply { put("select", variables) },
        )

        val root = data.objOrNull("data") ?: return emptyList()
        val titleItems = root.arrOrNull("get_title_browse_items") ?: return emptyList()

        val titles: List<TitleBrowseNode> =
            titleItems.objects().mapNotNull { it.toTitleBrowseNode() }
        if (titles.isEmpty()) return emptyList()

        val flattened: List<Triple<String, String, ComicProbe>> = coroutineScope {
            titles.chunked(TITLES_IN_FLIGHT).flatMap { batch ->
                batch.map { t -> async { flattenTitle(t, filterApiLang) } }.awaitAll()
            }
        }.flatten()

        return flattened.mapNotNull { (tid, cid, p) ->
            val t = titles.firstOrNull { it.id == tid } ?: return@mapNotNull null
            p.toBrowseManga(domain, tid, cid, t, allLanguages)
        }
    }

    private suspend fun flattenTitle(
        t: TitleBrowseNode,
        filterApiLang: String?,
        forceFresh: Boolean = false,
    ): List<Triple<String, String, ComicProbe>> {
        val titleId = t.id.takeIf { it.isNotBlank() } ?: return emptyList()
        val ids = t.comicIds.filter { it.isNotBlank() }
        if (ids.isEmpty()) return emptyList()

        val nowPublic = t.chapLastPublicAt ?: 0L
        val unchanged = !forceFresh && nowPublic in 1..(titleFreshness[titleId] ?: 0L)

        if (unchanged && ids.all { probeCache.containsKey(it) }) {
            return ids.mapNotNull { cid ->
                probeCache[cid]?.takeIf {
                    it.isLive() && (filterApiLang == null || it.translatedLanguage == filterApiLang)
                }?.let { Triple(titleId, cid, it) }
            }.sortedByDescending { it.third.chapsNormal ?: 0 }
        }

        val probes = mutableMapOf<String, ComicProbe>()
        coroutineScope {
            ids.chunked(COMIC_PROBES_PER_TITLE).flatMap { chunk ->
                chunk.map { cid -> async { fetchComicProbe(cid)?.let { probes[cid] = it } } }
                    .awaitAll()
            }
        }
        if (nowPublic > 0) titleFreshness[titleId] = nowPublic
        return probes.entries
            .filter { it.value.isLive() && (filterApiLang == null || it.value.translatedLanguage == filterApiLang) }
            .map { (cid, p) -> Triple(titleId, cid, p) }
            .sortedByDescending { it.third.chapsNormal ?: 0 }
    }

    private fun ComicProbe.toBrowseManga(
        domain: String,
        titleId: String,
        comicId: String,
        t: TitleBrowseNode,
        allLanguages: Boolean,
    ): Manga {
        val url = "$titleId:$comicId"
        val displayTitle = cleanTitle(t.title.orEmpty()).ifBlank { titleId }
        val titleText = buildString {
            append(displayTitle)
            subName?.takeIf { it.isNotBlank() }?.let { append(" · ", it.unescapeHtml()) }
            if (allLanguages) translatedLanguage?.let { append(" [", xComicLangDisplayName(it), "]") }
        }
        val cover = (t.coverLocalUrl ?: t.coverUrl ?: urlCover)
            ?.let { if (it.startsWith("http")) it else "https://$domain$it" }

        return Manga(
            id = generateUid(url),
            url = url,
            publicUrl = "https://$domain/title/$titleId",
            title = titleText,
            altTitles = emptySet(),
            rating = RATING_UNKNOWN,
            contentRating = null,
            coverUrl = cover,
            tags = emptySet(),
            state = null,
            authors = emptySet(),
            source = source,
        )
    }

    override suspend fun getDetails(manga: Manga): Manga = coroutineScope {
        val (titleId, pinned) = splitMangaUrl(manga.url)
        val title = fetchTitleNode(titleId)
            ?: return@coroutineScope manga.copy(chapters = emptyList())

        val resolved = if (title.isMerged == true &&
            !title.mergedTo.isNullOrBlank() &&
            title.mergedTo != titleId
        ) {
            fetchTitleNode(title.mergedTo) ?: title
        } else title

        val pair = pinned?.let { pid ->
            fetchComicNode(pid)?.takeIf { it.isLive() }?.let { pid to it }
        } ?: pickComic(resolved.comicIds.filter { it.isNotBlank() }, null)
        ?: return@coroutineScope manga.copy(chapters = emptyList())

        val (comicId, comic) = pair
        val chapters = fetchChapters(comicId)

        val base = comic.toManga(domain, comicId)

        val isAdult = listOfNotNull(
            resolved.contentRating,
            comic.contentRating,
        ).any { it.contains("explicit", true) || it.contains("adult", true) }

        base.copy(
            title = resolved.title?.let { cleanTitle(it) } ?: base.title,
            altTitles = resolved.altTitles.toSet(),
            description = buildDescription(resolved, comic),
            authors = (resolved.authors + comic.authorNames).toSet(),
            tags = (resolved.genreIds + comic.genres).mapTo(mutableSetOf()) {
                MangaTag(key = it, title = it.toTagCase(), source = source)
            },
            state = comic.status().toMangaState(),
            rating = resolved.voteAvg?.div(5f)?.coerceIn(0f, 1f) ?: RATING_UNKNOWN,
            contentRating = if (isAdult) ContentRating.ADULT else ContentRating.SAFE,
            coverUrl = resolved.coverLocalUrl?.let {
                if (it.startsWith("http")) it else "https://$domain$it"
            } ?: base.coverUrl,
            chapters = chapters,
        )
    }

    private fun ComicNode.toManga(domain: String, comicId: String): Manga = Manga(
        id = generateUid(comicId),
        url = comicId,
        publicUrl = "https://$domain/source/$comicId",
        title = cleanTitle(name),
        altTitles = emptySet(),
        rating = scoreVal?.div(5f)?.coerceIn(0f, 1f) ?: RATING_UNKNOWN,
        contentRating = if (contentRating?.contains("explicit", true) == true ||
            contentRating?.contains("adult", true) == true
        ) ContentRating.ADULT else ContentRating.SAFE,
        coverUrl = urlCover?.let { if (it.startsWith("http")) it else "https://$domain$it" },
        tags = emptySet(),
        state = status().toMangaState(),
        authors = authorNames.toSet(),
        source = source,
    )

    private fun buildDescription(t: TitleNodeData, c: ComicNode): String = buildString {
        if (c.isHot == true) append("🔥 HOT ")
        if (c.isNew == true) append("✨ NEW")
        if (c.isHot == true || c.isNew == true) append("\n\n")

        val meta = buildList {
            t.originalLanguage?.let { add("**Original**: ${xComicLangDisplayName(it)}") }
            t.translatedLanguages.takeIf { it.isNotEmpty() }?.let {
                add("**Translated**: ${it.joinToString { l -> xComicLangDisplayName(l) }}")
            }
            t.year?.takeIf { it > 0 }?.let { add("**Released**: $it") }
            c.type?.let { add("**Type**: ${it.toTagCase()}") }
        }
        if (meta.isNotEmpty()) {
            append(meta.joinToString("\n"))
            append("\n\n")
        }

        c.summary?.takeIf { it.isNotBlank() }?.let { append(it.toMarkdownUrls()) }

        val stats = buildList {
            t.voteAvg?.takeIf { it > 0 }?.let { add("**Score**: %.1f".format(it)) }
            t.totalFollows?.takeIf { it > 0 }?.let { add("**Follows**: $it") }
            t.totalReviews?.takeIf { it > 0 }?.let { add("**Reviews**: $it") }
            t.totalComments?.takeIf { it > 0 }?.let { add("**Comments**: $it") }
        }
        if (stats.isNotEmpty()) {
            append("\n\n**Statistics**\n${stats.joinToString(" · ")}")
        }

        t.altTitles.filter { it.isNotBlank() && it != t.title }.takeIf { it.isNotEmpty() }?.let {
            append("\n\n**Alternative Titles**:\n")
            append(it.joinToString("\n") { a -> "- $a" })
        }
    }

    private suspend fun fetchChapters(comicId: String): List<MangaChapter> = coroutineScope {
        val pageSize = 100
        val first = fetchChapterPage(comicId, 1, pageSize)
        val all = first.chapters.toMutableList()
        val total = first.total ?: 0
        if (total > pageSize && first.hasNext) {
            val totalPages = (total + pageSize - 1) / pageSize
            (2..totalPages).chunked(3).forEach { batch ->
                val pages = batch.map { p -> async { fetchChapterPage(comicId, p, pageSize).chapters } }
                all.addAll(pages.awaitAll().flatten())
            }
        }
        all.sortedBy { it.number }
    }

    private data class ChapterPage(val chapters: List<MangaChapter>, val total: Int?, val hasNext: Boolean)

    private suspend fun fetchChapterPage(comicId: String, page: Int, size: Int): ChapterPage {
        val variables = JSONObject().apply {
            put(
                "select",
                JSONObject().apply {
                    put("comic_id", comicId)
                    put("page", page)
                    put("size", size)
                    put("sortby", "chapter_desc")
                },
            )
        }

        val data = postGraphQL(XComicQueries.CHAPTER_LIST, variables)
        val root = data.objOrNull("data")?.objOrNull("get_comic_chapterList_fullList")
            ?: return ChapterPage(emptyList(), 0, false)
        val paging = root.objOrNull("paging")
        val items = root.arrOrNull("items") ?: return ChapterPage(emptyList(), 0, false)

        return ChapterPage(
            chapters = items.objects().mapNotNull { it.toChapter() },
            total = paging?.intOrNull("total"),
            hasNext = (paging?.intOrNull("next") ?: 0) != 0,
        )
    }

    private fun JSONObject.toChapter(): MangaChapter? {
        val wrapperData = objOrNull("data") ?: return null
        val chapterId = wrapperData.strOrNull("id") ?: return null
        val number = wrapperData.floatOrNull("chaNum") ?: wrapperData.floatOrNull("serial") ?: 0f
        val dname = wrapperData.optString("dname", "")
        val title = wrapperData.strOrNull("title")
        val srcName = wrapperData.strOrNull("srcName")
        val profileNames = wrapperData.arrOrNull("profileNodes")
            ?.objects()
            ?.mapNotNull { it.objOrNull("data")?.strOrNull("name") }
            ?.joinToString()
            ?.takeIf { it.isNotEmpty() }
        val date = wrapperData.longOrNull("dateModify")
            ?: wrapperData.longOrNull("dateCreate")
            ?: wrapperData.longOrNull("datePublic")
            ?: 0L

        val name = buildString {
            val n = number.toString().removeSuffix(".0")
            if (!dname.contains(n)) append("Chapter ", n)
            if (dname.isNotEmpty()) {
                if (isNotEmpty()) append(": ")
                append(dname)
            }
            if (!title.isNullOrEmpty()) {
                if (isNotEmpty()) append(": ")
                append(title)
            }
        }

        return MangaChapter(
            id = generateUid(chapterId),
            title = name,
            number = number,
            volume = 0,
            url = chapterId,
            scanlator = srcName ?: profileNames,
            uploadDate = date,
            branch = null,
            source = source,
        )
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val chapterId = chapter.url.substringBefore(":")
        val data = postGraphQL(
            XComicQueries.CHAPTER_PAGES,
            JSONObject().apply { put("id", chapterId) },
        )
        val urls = data.objOrNull("data")
            ?.objOrNull("get_chapterNode")
            ?.objOrNull("data")
            ?.arrOrNull("imageUrls")
            ?.strings()
            ?: return emptyList()

        return urls.map { url ->
            val abs = if (url.startsWith("http")) url else "https://$domain$url"
            MangaPage(id = generateUid(abs), url = abs, preview = null, source = source)
        }
    }

    private suspend fun postGraphQL(query: String, variables: JSONObject): JSONObject {
        val payload = JSONObject().apply {
            put("query", query)
            put("variables", variables)
        }

        val headers = mapOf(
            "Origin" to "https://$domain",
            "Referer" to "https://$domain/",
        ).toHeaders()

        val response = webClient.httpPost(
            "https://$domain/query/".toHttpUrl(),
            payload,
            headers,
        )

        val text = response.parseRaw()
        return runCatching { JSONObject(text) }.getOrElse { JSONObject() }
    }

    private suspend fun fetchTitleNode(id: String): TitleNodeData? {
        return try {
            val data = postGraphQL(
                XComicQueries.TITLE_NODE,
                JSONObject().apply { put("id", id) },
            )
            data.objOrNull("data")
                ?.objOrNull("get_title_titleNode")
                ?.objOrNull("data")
                ?.toTitleNodeData()
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun fetchComicNode(id: String): ComicNode? {
        return try {
            val data = postGraphQL(
                XComicQueries.COMIC_NODE,
                JSONObject().apply { put("id", id) },
            )
            data.objOrNull("data")
                ?.objOrNull("get_comicNode")
                ?.objOrNull("data")
                ?.toComicNode(id)
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun fetchComicProbe(id: String): ComicProbe? {
        probeCache[id]?.let { return it }
        val fetched = try {
            val data = postGraphQL(
                XComicQueries.COMIC_PROBE,
                JSONObject().apply { put("id", id) },
            )
            data.objOrNull("data")
                ?.objOrNull("get_comicNode")
                ?.objOrNull("data")
                ?.toComicProbe()
        } catch (_: Exception) {
            null
        }
        if (fetched != null) probeCache[id] = fetched
        return fetched
    }

    private suspend fun pickComic(ids: List<String>, filterApiLang: String?): Pair<String, ComicNode>? {
        if (ids.isEmpty()) return null
        val nodes = coroutineScope {
            ids.chunked(COMIC_PROBES_PER_TITLE).flatMap { chunk ->
                chunk.map { cid -> async { fetchComicNode(cid)?.let { cid to it } } }
                    .awaitAll().filterNotNull()
            }
        }.filter { it.second.isLive() }

        return nodes.filter { filterApiLang == null || it.second.translatedLanguage == filterApiLang }
            .maxByOrNull { it.second.chapsNormal ?: 0 }
            ?: nodes.firstOrNull()?.takeIf { filterApiLang == null }
    }

    private fun JSONObject.toTitleBrowseNode(): TitleBrowseNode? {
        val id = strOrNull("id") ?: return null
        val d = objOrNull("data") ?: return null
        return TitleBrowseNode(
            id = id,
            title = d.strOrNull("title"),
            coverLocalUrl = d.strOrNull("cover_local_url"),
            coverUrl = d.strOrNull("cover_url"),
            chapLastPublicAt = d.longOrNull("chap_last_public_at"),
            comicIds = d.stringList("comic_ids"),
        )
    }

    private fun JSONObject.toTitleNodeData(): TitleNodeData = TitleNodeData(
        id = strOrNull("id"),
        title = strOrNull("title"),
        altTitles = stringList("alt_titles"),
        originalLanguage = strOrNull("original_language"),
        translatedLanguages = stringList("translated_languages"),
        authors = stringList("authors"),
        artists = stringList("artists"),
        contentRating = strOrNull("content_rating_id"),
        genreIds = stringList("genre_ids"),
        year = intOrNull("year"),
        type = strOrNull("type"),
        status = strOrNull("status"),
        description = strOrNull("description"),
        coverLocalUrl = strOrNull("cover_local_url"),
        coverUrl = strOrNull("cover_url"),
        voteAvg = floatOrNull("vote_avg"),
        totalFollows = intOrNull("total_follows"),
        totalReviews = intOrNull("total_reviews"),
        totalComments = intOrNull("total_comments"),
        chapLastPublicAt = longOrNull("chap_last_public_at"),
        isMerged = boolOrNull("is_merged"),
        mergedTo = strOrNull("merged_to"),
        comicIds = stringList("comic_ids"),
    )

    private fun JSONObject.toComicNode(fallbackId: String): ComicNode = ComicNode(
        id = strOrNull("id") ?: fallbackId,
        name = strOrNull("name").orEmpty(),
        subName = strOrNull("subName"),
        translatedLanguage = strOrNull("translatedLanguage"),
        originalStatus = strOrNull("originalStatus"),
        uploadStatus = strOrNull("uploadStatus"),
        type = strOrNull("type"),
        contentRating = strOrNull("contentRating"),
        genres = stringList("genres"),
        authorNames = arrOrNull("authorNodes")
            ?.objects()
            ?.mapNotNull { it.objOrNull("data")?.strOrNull("name") }
            ?: stringList("authors"),
        summary = objOrNull("summary")?.strOrNull("text"),
        dbStatus = strOrNull("dbStatus"),
        isPublic = boolOrNull("isPublic"),
        isHot = boolOrNull("is_hot"),
        isNew = boolOrNull("is_new"),
        scoreVal = floatOrNull("score_val"),
        chapsNormal = intOrNull("chaps_normal"),
        urlCover = strOrNull("urlCover"),
    )

    private fun JSONObject.toComicProbe(): ComicProbe = ComicProbe(
        name = strOrNull("name"),
        subName = strOrNull("subName"),
        dbStatus = strOrNull("dbStatus"),
        isPublic = boolOrNull("isPublic"),
        translatedLanguage = strOrNull("translatedLanguage"),
        chapsNormal = intOrNull("chaps_normal"),
        urlCover = strOrNull("urlCover"),
    )

    private data class TitleBrowseNode(
        val id: String,
        val title: String?,
        val coverLocalUrl: String?,
        val coverUrl: String?,
        val chapLastPublicAt: Long?,
        val comicIds: List<String>,
    )

    private data class TitleNodeData(
        val id: String?,
        val title: String?,
        val altTitles: List<String>,
        val originalLanguage: String?,
        val translatedLanguages: List<String>,
        val authors: List<String>,
        val artists: List<String>,
        val contentRating: String?,
        val genreIds: List<String>,
        val year: Int?,
        val type: String?,
        val status: String?,
        val description: String?,
        val coverLocalUrl: String?,
        val coverUrl: String?,
        val voteAvg: Float?,
        val totalFollows: Int?,
        val totalReviews: Int?,
        val totalComments: Int?,
        val chapLastPublicAt: Long?,
        val isMerged: Boolean?,
        val mergedTo: String?,
        val comicIds: List<String>,
    ) {
        fun toBrowseNode() = TitleBrowseNode(
            id = id.orEmpty(),
            title = title,
            coverLocalUrl = coverLocalUrl,
            coverUrl = coverUrl,
            chapLastPublicAt = chapLastPublicAt,
            comicIds = comicIds,
        )
    }

    private data class ComicNode(
        val id: String,
        val name: String,
        val subName: String?,
        val translatedLanguage: String?,
        val originalStatus: String?,
        val uploadStatus: String?,
        val type: String?,
        val contentRating: String?,
        val genres: List<String>,
        val authorNames: List<String>,
        val summary: String?,
        val dbStatus: String?,
        val isPublic: Boolean?,
        val isHot: Boolean?,
        val isNew: Boolean?,
        val scoreVal: Float?,
        val chapsNormal: Int?,
        val urlCover: String?,
    ) {
        fun isLive() = isPublic != false && (dbStatus == null || dbStatus == "normal")
        fun status(): String? = originalStatus ?: uploadStatus
    }

    private data class ComicProbe(
        val name: String?,
        val subName: String?,
        val dbStatus: String?,
        val isPublic: Boolean?,
        val translatedLanguage: String?,
        val chapsNormal: Int?,
        val urlCover: String?,
    ) {
        fun isLive() = isPublic != false && (dbStatus == null || dbStatus == "normal")
    }

    private fun splitMangaUrl(url: String): Pair<String, String?> {
        val i = url.indexOf(':')
        return if (i < 0) url to null else url.substring(0, i) to url.substring(i + 1)
    }

    private fun sortFor(order: SortOrder): String = when (order) {
        SortOrder.POPULARITY -> "field_score"
        SortOrder.UPDATED, SortOrder.NEWEST -> "field_update"
        SortOrder.RATING -> "field_score"
        SortOrder.ALPHABETICAL -> "field_name_asc"
        else -> "field_update"
    }

    private fun MangaState.toApiStatus(): String? = when (this) {
        MangaState.ONGOING -> "releasing"
        MangaState.FINISHED -> "completed"
        MangaState.PAUSED -> "hiatus"
        MangaState.ABANDONED -> "cancelled"
        else -> null
    }

    private fun ContentRating.toApiRating(): String? = when (this) {
        ContentRating.ADULT -> "explicit"
        ContentRating.SAFE -> "safe"
        else -> null
    }

    private fun String?.toMangaState(): MangaState? = when {
        this == null -> null
        contains("pending") -> null
        contains("ongoing") || contains("releasing") -> MangaState.ONGOING
        contains("cancelled") -> MangaState.ABANDONED
        contains("hiatus") -> MangaState.PAUSED
        contains("completed") -> MangaState.FINISHED
        else -> null
    }

    private fun String.toTagCase(): String = replace("_", " ")
        .split(" ").joinToString(" ") { w ->
            w.lowercase(Locale.ROOT)
                .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
        }

    private fun String.unescapeHtml(): String = Parser.unescapeEntities(this, false)

    private fun cleanTitle(title: String): String = title
        .replace(TITLE_REGEX, "")
        .trim()

    private fun String.toMarkdownUrls(): String =
        replace(URL_REGEX) { "[${it.value}](${it.value})" }
}

private const val BROWSE_PAGE_SIZE = 12
private const val TITLES_IN_FLIGHT = 3
private const val COMIC_PROBES_PER_TITLE = 5

private val ID_QUERY = Regex("^id\\s*:?\\s*([a-zA-Z0-9-_]+)\\s*$", RegexOption.IGNORE_CASE)

private val URL_REGEX = Regex("""(?<![\[(])(https?://[^\s<"]+)""")

private val TITLE_REGEX = Regex(
    "\\([^()]*\\)|\\{[^{}]*}|\\[(?:(?!]).)*]|«[^»]*»|〘[^〙]*〙|「[^」]*」|『[^』]*』" +
            "|≪[^≫]*≫|﹛[^﹜]*﹜|〖[^〖〗]*〗|《[^》]*》|/Official|/ Official",
    RegexOption.IGNORE_CASE,
)

private val XCOMIC_LANGS: List<Pair<String, String>> = listOf(
    "English" to "en",
    "French" to "fr",
    "Portuguese" to "pt",
    "Portuguese (BR)" to "pt_br",
    "Spanish" to "es",
    "Spanish (LA)" to "es_419",
    "Korean" to "ko",
    "Japanese" to "ja",
    "Indonesian" to "id",
    "Chinese" to "zh",
    "Chinese (Traditional)" to "zh_hk",
    "Russian" to "ru",
    "German" to "de",
    "Italian" to "it",
    "Arabic" to "ar",
    "Thai" to "th",
    "Vietnamese" to "vi",
    "Turkish" to "tr",
    "Polish" to "pl",
    "Ukrainian" to "uk",
    "Filipino" to "fil",
    "Abkhazian" to "ab",
    "Afrikaans" to "af",
    "Albanian" to "sq",
    "Amharic" to "am",
    "Armenian" to "hy",
    "Azerbaijani" to "az",
    "Belarusian" to "be",
    "Bengali" to "bn",
    "Bosnian" to "bs",
    "Bulgarian" to "bg",
    "Burmese" to "my",
    "Cambodian" to "km",
    "Catalan" to "ca",
    "Cebuano" to "ceb",
    "Croatian" to "hr",
    "Czech" to "cs",
    "Chuvash" to "cv",
    "Danish" to "da",
    "Dutch" to "nl",
    "Estonian" to "et",
    "Esperanto" to "eo",
    "Basque" to "eu",
    "Faroese" to "fo",
    "Finnish" to "fi",
    "Georgian" to "ka",
    "Greek" to "el",
    "Guarani" to "gn",
    "Gujarati" to "gu",
    "Haitian Creole" to "ht",
    "Hausa" to "ha",
    "Hebrew" to "he",
    "Hindi" to "hi",
    "Hungarian" to "hu",
    "Icelandic" to "is",
    "Igbo" to "ig",
    "Irish" to "ga",
    "Galician" to "gl",
    "Javanese" to "jv",
    "Kannada" to "kn",
    "Kazakh" to "kk",
    "Kurdish" to "ku",
    "Kyrgyz" to "ky",
    "Latin" to "la",
    "Laothian" to "lo",
    "Latvian" to "lv",
    "Lithuanian" to "lt",
    "Luxembourgish" to "lb",
    "Macedonian" to "mk",
    "Malagasy" to "mg",
    "Malay" to "ms",
    "Malayalam" to "ml",
    "Maltese" to "mt",
    "Maori" to "mi",
    "Marathi" to "mr",
    "Moldavian" to "mo",
    "Mongolian" to "mn",
    "Nepali" to "ne",
    "Norwegian" to "no",
    "Nyanja" to "ny",
    "Pashto" to "ps",
    "Persian" to "fa",
    "Romanian" to "ro",
    "Romansh" to "rm",
    "Samoan" to "sm",
    "Serbian" to "sr",
    "Serbo-Croatian" to "sh",
    "Siswati" to "ss",
    "Sesotho" to "st",
    "Shona" to "sn",
    "Sindhi" to "sd",
    "Sinhalese" to "si",
    "Slovak" to "sk",
    "Slovenian" to "sl",
    "Somali" to "so",
    "Swahili" to "sw",
    "Swedish" to "sv",
    "Tajik" to "tg",
    "Tamil" to "ta",
    "Telugu" to "te",
    "Tigrinya" to "ti",
    "Tonga" to "to",
    "Turkmen" to "tk",
    "Urdu" to "ur",
    "Uzbek" to "uz",
    "Yoruba" to "yo",
    "Zulu" to "zu",
    "Other" to "_t",
)

private val XCOMIC_LOCALES: Set<Locale> = mutableSetOf<Locale>().apply {
    add(Locale.ENGLISH)
    add(Locale.FRENCH)
    add(Locale.GERMAN)
    add(Locale.ITALIAN)
    add(Locale.JAPANESE)
    add(Locale.KOREAN)
    add(Locale("es"))
    add(Locale("es", "419"))
    add(Locale("pt"))
    add(Locale("pt", "BR"))
    add(Locale.SIMPLIFIED_CHINESE)
    add(Locale.TRADITIONAL_CHINESE)
    add(Locale("ru"))
    add(Locale("id"))
    add(Locale("ar"))
    add(Locale("th"))
    add(Locale("vi"))
    add(Locale("tr"))
    add(Locale("pl"))
    add(Locale("uk"))
    add(Locale("fil"))
    add(Locale("other"))

    val alreadyAdded = setOf(
        "en", "fr", "de", "it", "ja", "ko",
        "es", "pt", "zh", "ru", "id", "ar", "th",
        "vi", "tr", "pl", "uk", "fil",
    )
    XCOMIC_LANGS
        .map { it.second }
        .filter { it.length == 2 && it !in alreadyAdded }
        .forEach { add(Locale(it)) }
}.toSet()

private val GENRE_TAGS: List<Pair<String, String>> = listOf(
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
    "Mature" to "mature",
    "Mystery" to "mystery",
    "Romance" to "romance",
    "School Life" to "school_life",
    "Sci-fi" to "sci_fi",
    "Shounen" to "shounen",
    "Shounen Ai" to "shounen_ai",
    "Slice of Life" to "slice_of_life",
    "Supernatural" to "supernatural",
    "Thriller" to "thriller",
    "Uncensored" to "uncensored",
)
