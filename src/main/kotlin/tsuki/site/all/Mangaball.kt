package tsuki.site.all

import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.config.ConfigKey
import tsuki.core.PagedMangaParser
import tsuki.network.CommonHeaders

import tsuki.model.ContentRating
import tsuki.model.ContentType
import tsuki.model.Demographic
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

import tsuki.util.LinkResolver
import tsuki.util.generateUid
import tsuki.util.nullIfEmpty
import tsuki.util.parseJson

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.EnumSet

private const val DOMAIN = "mangaball.com"
private const val LEGACY_HOST = "mangaball.net"
private const val BASE_URL = "https://$DOMAIN"
private const val COVER_BASE_URL = "https://bulbasaur.poke-black-and-white.net/covers/"
private const val PAGE_SIZE = 24

private val ADULT_TAG_NAMES = setOf(
    "adult", "ecchi", "mature", "smut", "hentai", "manhwa 18+", "sexual violence",
)

internal abstract class MangaBallParser(
    context: MangaLoaderContext,
    source: MangaParserSource,
    private val siteLanguages: Set<String>,
): PagedMangaParser(context, source, pageSize = PAGE_SIZE) {

    override val configKeyDomain = ConfigKey.Domain(DOMAIN)

    private val showSuspiciousContentKey = ConfigKey.ShowSuspiciousContent(false)
    private val dateFormat = DateTimeFormatter.ISO_LOCAL_DATE_TIME

    override fun getRequestHeaders(): Headers = super.getRequestHeaders().newBuilder()
        .add(CommonHeaders.REFERER, "$BASE_URL/")
        .add(CommonHeaders.ORIGIN, BASE_URL)
        .add(CommonHeaders.ACCEPT, "application/json, text/plain, */*")
        .build()

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

    override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
        super.onCreateConfig(keys)
        keys.add(userAgentKey)
        keys.add(showSuspiciousContentKey)
    }

    override suspend fun getFilterOptions() = MangaListFilterOptions(
        availableTags = TAG_DEFINITIONS.mapTo(LinkedHashSet(TAG_DEFINITIONS.size)) {
            MangaTag(key = it.id, title = it.title, source = source)
        },
        availableStates = EnumSet.of(
            MangaState.ONGOING,
            MangaState.FINISHED,
            MangaState.PAUSED,
            MangaState.ABANDONED,
        ),
        availableContentTypes = EnumSet.of(
            ContentType.MANGA,
            ContentType.MANHWA,
            ContentType.MANHUA,
            ContentType.COMICS,
        ),
        availableDemographics = EnumSet.of(
            Demographic.SHOUNEN,
            Demographic.SHOUJO,
            Demographic.SEINEN,
            Demographic.JOSEI,
        ),
    )
    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val url = "$BASE_URL/api/v1/title/search-advanced".toHttpUrl().newBuilder().apply {
            addQueryParameter("page", page.toString())
            addQueryParameter("limit", PAGE_SIZE.toString())
            addQueryParameter("sort_by", sortByValue(order))
            addQueryParameter("sort_order", "desc")
            addQueryParameter("tag_mode", "AND")
            addQueryParameter("adult_mode", if (config[showSuspiciousContentKey]) "all" else "no_18")

            filter.query?.trim()?.takeIf { it.isNotEmpty() }?.let {
                addQueryParameter("keyword", it)
            }
            filter.types.firstOrNull()?.let {
                addQueryParameter("type", contentTypeValue(it))
            }
            filter.demographics.firstOrNull()?.let {
                addQueryParameter("publicationDemographic", demographicValue(it))
            }
            filter.states.firstOrNull()?.let {
                addQueryParameter("status", stateValue(it))
            }

            filter.tags.map { it.key }.takeIf { it.isNotEmpty() }?.let {
                addQueryParameter("included_tags", it.joinToString(","))
            }
            filter.tagsExclude.map { it.key }.takeIf { it.isNotEmpty() }?.let {
                addQueryParameter("excluded_tags", it.joinToString(","))
            }
        }.build()

        val data = webClient.httpGet(url).parseJson().optJSONArray("data")
            ?: return emptyList()

        return (0 until data.length()).mapNotNull { i ->
            data.getJSONObject(i).toListManga()
        }
    }

    private fun JSONObject.toListManga(): Manga? {
        val id = optString("id").nullIfEmpty()
            ?: optString("slug").nullIfEmpty()
            ?: return null
        val slug = optString("slug").nullIfEmpty() ?: id
        return Manga(
            id = generateUid(id),
            title = getString("name"),
            altTitles = emptySet(),
            url = id,
            publicUrl = getMangaUrl(slug),
            rating = RATING_UNKNOWN,
            contentRating = null,
            coverUrl = parseCoverUrl(optJSONObject("image")),
            tags = emptySet(),
            state = null,
            authors = emptySet(),
            source = source,
        )
    }

    override suspend fun getDetails(manga: Manga): Manga = coroutineScope {
        val detailDeferred = async {
            webClient.httpGet("$BASE_URL/api/v1/title/detail/${manga.url}")
                .parseJson()
                .getJSONObject("data")
        }
        val chaptersDeferred = async {
            runCatching { getChapterList(manga.url) }.getOrDefault(emptyList())
        }

        val data = detailDeferred.await()

        val titleId = data.optString("id").nullIfEmpty() ?: manga.url
        val slug    = data.optString("slug").nullIfEmpty() ?: titleId
        val name    = data.optString("name").nullIfEmpty() ?: manga.title

        val description = data.optJSONArray("description")
            ?.let { arr -> (0 until arr.length()).joinToString("\n\n") { arr.optString(it) } }
            .orEmpty()
        val altNames = data.optJSONArray("alternateName").toStringList()
        val tags     = data.optJSONArray("tags").toNameList()
        val authors  = data.optJSONArray("author").toNameList()
        val artists  = data.optJSONArray("artist").toNameList()

        val rating = data.optJSONObject("ratings")
            ?.optDouble("rating_average", -1.0)
            ?.takeIf { it in 0.0..10.0 }
            ?.let { (it / 10.0).toFloat() }
            ?: RATING_UNKNOWN

        val coverUrl  = parseCoverUrl(data.optJSONObject("image"))

        val contentRating = if (tags.any { it.lowercase() in ADULT_TAG_NAMES }) {
            ContentRating.ADULT
        } else {
            null
        }

        var chapters = chaptersDeferred.await()
        if (chapters.isEmpty() && titleId != manga.url) {
            chapters = runCatching { getChapterList(titleId) }.getOrDefault(emptyList())
        }
        manga.copy(
            title = name,
            altTitles = altNames.toSet(),
            publicUrl = getMangaUrl(slug),
            coverUrl = coverUrl ?: manga.coverUrl,
            largeCoverUrl = coverUrl ?: manga.largeCoverUrl,
            description = description,
            tags = tags.mapTo(mutableSetOf()) { MangaTag(key = it, title = it, source = source) },
            authors = (authors + artists).toSet(),
            state = parseState(data.optString("status").nullIfEmpty()),
            chapters = chapters,
            source = source,
            rating = rating,
            contentRating = contentRating,
        )
    }

    private suspend fun getChapterList(titleId: String): List<MangaChapter> {
        val releases = fetchAll(titleId)
        val branchLabels = preBranchLabels(releases)

        val buckets = releases.groupBy { (lang, item) ->
            ChapterBucket(
                number = item.optDouble("number", 0.0).toFloat(),
                lang = lang,
            )
        }

        return buckets.values
            .flatMap { group ->
                group.map { (_, item) ->
                    buildChapter(item, branchLabels[item.getString("id")])
                }
            }
            .sortedWith(
                compareBy<MangaChapter> { it.number }
                    .thenBy { it.volume }
                    .thenByDescending { it.uploadDate }
                    .thenBy { it.branch.orEmpty() },
            )
    }

    private fun getBase(item: JSONObject, lang: String): String {
        val scanlator = item.optString("group_name").trim().nullIfEmpty()
        val site = item.optString("site").trim().nullIfEmpty()
        return scanlator ?: site ?: lang
    }

    private fun preBranchLabels(releases: List<Pair<String, JSONObject>>): Map<String, String?> {
        val byBase = releases.groupBy { (lang, item) -> getBase(item, lang) }

        val result = mutableMapOf<String, String?>()

        for ((base, group) in byBase) {
            val volumes = group.map { it.second.optDouble("volume", 0.0).toInt() }.distinct()
            val hasNormal = volumes.contains(0)
            val hasVolumes = volumes.any { it > 0 }

            if (hasNormal && hasVolumes) {
                for ((_, item) in group) {
                    val vol = item.optDouble("volume", 0.0).toInt()
                    val label = if (vol == 0) "$base (Chapters)" else "$base (Volumes)"
                    result[item.getString("id")] = label
                }
            } else {
                for ((_, item) in group) {
                    result[item.getString("id")] = base
                }
            }
        }
        return result
    }

    private suspend fun fetchAll(titleId: String): List<Pair<String, JSONObject>> {
        val langParam = siteLanguages.joinToString(",")
        val pageSize = 200

        suspend fun fetchPage(page: Int): JSONArray? {
            val url = "$BASE_URL/api/v1/title/chapter-listing".toHttpUrl().newBuilder()
                .addQueryParameter("title_id", titleId)
                .addQueryParameter("page", page.toString())
                .addQueryParameter("limit", pageSize.toString())
                .addQueryParameter("group_by", "chapter_number")
                .addQueryParameter("sort_order", "desc")
                .addQueryParameter("language", langParam)
                .addQueryParameter("search", "")
                .addQueryParameter("user_id", "")
                .build()
            return webClient.httpGet(url).parseJson().optJSONArray("data")
        }

        val firstJson = webClient.httpGet(
            "$BASE_URL/api/v1/title/chapter-listing".toHttpUrl().newBuilder()
                .addQueryParameter("title_id", titleId)
                .addQueryParameter("page", "1")
                .addQueryParameter("limit", pageSize.toString())
                .addQueryParameter("group_by", "chapter_number")
                .addQueryParameter("sort_order", "desc")
                .addQueryParameter("language", langParam)
                .addQueryParameter("search", "")
                .addQueryParameter("user_id", "")
                .build()
        ).parseJson()

        val firstPage = firstJson.optJSONArray("data") ?: return emptyList()
        if (firstPage.length() == 0) return emptyList()

        val totalPages = firstJson.optJSONObject("pagination")
            ?.optInt("total_pages", 1)
            ?.coerceAtLeast(1)
            ?: 1

        val seenIds = HashSet<String>()
        val releases = ArrayList<Pair<String, JSONObject>>()

        fun consume(data: JSONArray?) {
            if (data == null) return
            for (i in 0 until data.length()) {
                val item = data.optJSONObject(i) ?: continue
                val lang = item.optString("lang")
                if (lang !in siteLanguages) continue
                val id = item.optString("id").nullIfEmpty() ?: continue
                if (!seenIds.add(id)) continue
                releases += lang to item
            }
        }

        consume(firstPage)

        if (totalPages > 1) {
            val rest = coroutineScope {
                (2..totalPages).map { page -> async { fetchPage(page) } }.awaitAll()
            }
            rest.forEach { consume(it) }
        }
        return releases
    }
    private fun buildChapter(item: JSONObject, branch: String?): MangaChapter {
        val number = item.optDouble("number", 0.0).toFloat()
        val volume = item.optDouble("volume", 0.0).toFloat()
        val id = item.getString("id")
        val rawName = item.optString("name").trim()
        val groupNm = item.optString("group_name").trim().nullIfEmpty()
        val created = item.optString("created_at").nullIfEmpty()

        val title = buildChapterTitle(rawName, number, volume)

        return MangaChapter(
            generateUid(id),
            title,
            number,
            volume.toInt(),
            id,
            groupNm,
            parseDate(created),
            branch,
            source,
        )
    }

    private fun buildChapterTitle(rawName: String, number: Float, volume: Float): String {
        val numberStr = number.toString().removeSuffix(".0")
        return buildString {
            if (volume > 0f) {
                append("Vol. ")
                append(volume.toString().removeSuffix(".0"))
                append(' ')
            }
            if (rawName.contains(numberStr)) {
                append(rawName)
            } else {
                append("Ch. ")
                append(numberStr)
                if (rawName.isNotEmpty()) {
                    append(' ')
                    append(rawName)
                }
            }
        }
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val json = webClient.httpGet(
            "$BASE_URL/api/v1/chapter-detail?chapter_id=${chapter.url}",
        ).parseJson()

        val pages = json.getJSONObject("data")
            .getJSONObject("chapter")
            .optJSONArray("pages")
            ?: return emptyList()

        runCatching {
            webClient.httpPost(
                "$BASE_URL/api/v1/views/update".toHttpUrl(),
                JSONObject().apply {
                    put("object_id", chapter.url)
                    put("object_type", "chapter")
                },
            )
        }

        return (0 until pages.length()).map { i ->
            val imageUrl = pages.getString(i)
            MangaPage(
                id = generateUid(imageUrl),
                url = imageUrl,
                preview = null,
                source = source,
            )
        }
    }

    override suspend fun resolveLink(resolver: LinkResolver, link: HttpUrl): Manga? {
        if (link.host != DOMAIN && link.host != LEGACY_HOST) return null

        val handle = when (link.pathSegments.firstOrNull()) {
            "title-detail" -> link.pathSegments.getOrNull(1)
            "chapter-detail" -> link.pathSegments.getOrNull(1)?.let { resolveTitleIdFromChapter(it) }
            else -> null
        } ?: return null

        return getDetails(seedManga(handle))
    }

    private suspend fun resolveTitleIdFromChapter(chapterId: String): String? {
        val json = webClient.httpGet(
            "$BASE_URL/api/v1/chapter-detail?chapter_id=$chapterId",
        ).parseJson()
        return json.getJSONObject("data")
            .getJSONObject("chapter")
            .optString("title_id")
            .nullIfEmpty()
    }

    private fun seedManga(handle: String) = Manga(
        id = generateUid(handle),
        title = handle.replace('-', ' ').replaceFirstChar { it.uppercase() },
        altTitles = emptySet(),
        url = handle,
        publicUrl = getMangaUrl(handle),
        rating = RATING_UNKNOWN,
        contentRating = null,
        coverUrl = null,
        tags = emptySet(),
        state = null,
        authors = emptySet(),
        source = source,
    )

    private fun getMangaUrl(slug: String) = "$BASE_URL/title-detail/$slug"

    private fun parseCoverUrl(image: JSONObject?): String? {
        if (image == null) return null
        val coverPath = image.optJSONObject("cover")?.optString("path")?.nullIfEmpty()
        if (coverPath != null) {
            val normalized = coverPath.replace('\\', '/')
            return if (normalized.startsWith("http")) normalized else COVER_BASE_URL + normalized
        }
        return listOf(
            image.optString("file"),
            image.optString("cdn_mangadex"),
            image.optString("cdn_mangaupdate"),
            image.optString("cdn_mangaupdates"),
        ).firstOrNull { !it.isNullOrBlank() }
    }

    private fun parseDate(raw: String?): Long {
        if (raw.isNullOrBlank()) return 0L
        return runCatching {
            LocalDateTime.parse(raw, dateFormat).toInstant(ZoneOffset.UTC).toEpochMilli()
        }.getOrDefault(0L)
    }

    private fun parseState(status: String?): MangaState? = when (status) {
        "ongoing" -> MangaState.ONGOING
        "completed" -> MangaState.FINISHED
        "hiatus" -> MangaState.PAUSED
        "cancelled" -> MangaState.ABANDONED
        else -> null
    }

    /** Extracts a list of plain strings from an array of primitives. */
    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        return (0 until length()).mapNotNull { optString(it).nullIfEmpty() }
    }

    /** Extracts `.name` from an array of objects (`[{"name": "..."}]`). */
    private fun JSONArray?.toNameList(): List<String> {
        if (this == null) return emptyList()
        return (0 until length())
            .mapNotNull { optJSONObject(it)?.optString("name")?.nullIfEmpty() }
    }

    private fun sortByValue(order: SortOrder): String = when (order) {
        SortOrder.UPDATED -> "lastupdate"
        SortOrder.POPULARITY -> "views"
        SortOrder.RATING -> "rating"
        SortOrder.NEWEST -> "created_at"
        SortOrder.ALPHABETICAL -> "name"
        else -> "lastupdate"
    }

    private fun contentTypeValue(type: ContentType): String = when (type) {
        ContentType.MANGA -> "manga"
        ContentType.MANHWA -> "manhwa"
        ContentType.MANHUA -> "manhua"
        ContentType.COMICS -> "comics"
        else -> ""
    }

    private fun demographicValue(demographic: Demographic): String = when (demographic) {
        Demographic.SHOUNEN -> "shounen"
        Demographic.SHOUJO -> "shoujo"
        Demographic.SEINEN -> "seinen"
        Demographic.JOSEI -> "josei"
        else -> ""
    }

    private fun stateValue(state: MangaState): String = when (state) {
        MangaState.ONGOING -> "ongoing"
        MangaState.FINISHED -> "completed"
        MangaState.PAUSED -> "hiatus"
        MangaState.ABANDONED -> "cancelled"
        else -> ""
    }

    private data class ChapterBucket(
        val number: Float,
        val lang: String,
    )

    private data class TagDef(val id: String, val title: String)

    companion object {
        private val TAG_DEFINITIONS = listOf(
            TagDef("685148d115e8b86aae68e4f3", "Gore"),
            TagDef("685146c5f3ed681c80f257e7", "Sexual Violence"),
            TagDef("685148d115e8b86aae68e4ec", "4-Koma"),
            TagDef("685148cf15e8b86aae68e4de", "Adaptation"),
            TagDef("685148e915e8b86aae68e558", "Anthology"),
            TagDef("685148fe15e8b86aae68e5a7", "Award Winning"),
            TagDef("6851490e15e8b86aae68e5da", "Doujinshi"),
            TagDef("6851498215e8b86aae68e704", "Fan Colored"),
            TagDef("685148d615e8b86aae68e502", "Full Color"),
            TagDef("685148d915e8b86aae68e517", "Long Strip"),
            TagDef("6851493515e8b86aae68e64a", "Official Colored"),
            TagDef("685148eb15e8b86aae68e56c", "Oneshot"),
            TagDef("6851492e15e8b86aae68e633", "Self-Published"),
            TagDef("685148d715e8b86aae68e50d", "Web Comic"),
            TagDef("685146c5f3ed681c80f257e3", "Action"),
            TagDef("689371f0a943baf927094f03", "Adult"),
            TagDef("685146c5f3ed681c80f257e6", "Adventure"),
            TagDef("685148ef15e8b86aae68e573", "Boys' Love"),
            TagDef("68ecab8507ec62d87e62780f", "Comic"),
            TagDef("685146c5f3ed681c80f257e5", "Comedy"),
            TagDef("685148da15e8b86aae68e51f", "Crime"),
            TagDef("685148cf15e8b86aae68e4dd", "Drama"),
            TagDef("6892a73ba943baf927094e37", "Ecchi"),
            TagDef("685146c5f3ed681c80f257ea", "Fantasy"),
            TagDef("685148da15e8b86aae68e524", "Girls' Love"),
            TagDef("685148db15e8b86aae68e527", "Historical"),
            TagDef("685148da15e8b86aae68e520", "Horror"),
            TagDef("685146c5f3ed681c80f257e9", "Isekai"),
            TagDef("694cc2d9f8014f5e0a63ac73", "Josei(W)"),
            TagDef("6851490d15e8b86aae68e5d4", "Magical Girls"),
            TagDef("68ecab1e07ec62d87e627806", "Manga"),
            TagDef("68ecab4807ec62d87e62780b", "Manhua"),
            TagDef("68ecab3b07ec62d87e627809", "Manhwa"),
            TagDef("68932d11a943baf927094e7b", "Mature"),
            TagDef("6851490c15e8b86aae68e5d2", "Mecha"),
            TagDef("6851494e15e8b86aae68e66e", "Medical"),
            TagDef("685148d215e8b86aae68e4f4", "Mystery"),
            TagDef("685148e215e8b86aae68e544", "Philosophical"),
            TagDef("685148d715e8b86aae68e507", "Psychological"),
            TagDef("694cc2d9f8014f5e0a63ac75", "Revenge"),
            TagDef("685148cf15e8b86aae68e4db", "Romance"),
            TagDef("685148cf15e8b86aae68e4da", "Sci-Fi"),
            TagDef("694cc2d9f8014f5e0a63ac74", "Shoujo(G)"),
            TagDef("689f0ab1f2e66744c6091524", "Shounen Ai"),
            TagDef("685148d015e8b86aae68e4e3", "Slice of Life"),
            TagDef("689371f2a943baf927094f04", "Smut"),
            TagDef("685148f515e8b86aae68e588", "Sports"),
            TagDef("6851492915e8b86aae68e61c", "Superhero"),
            TagDef("685148d915e8b86aae68e51e", "Thriller"),
            TagDef("685148db15e8b86aae68e529", "Tragedy"),
            TagDef("68932c3ea943baf927094e77", "User Created"),
            TagDef("6851490715e8b86aae68e5c3", "Wuxia"),
            TagDef("68932f68a943baf927094eaa", "Yaoi"),
            TagDef("6896a885a943baf927094f66", "Yuri"),
            TagDef("6a0026ba63a8d384c0a4be13", "3D"),
            TagDef("6851490d15e8b86aae68e5d5", "Aliens"),
            TagDef("685148e715e8b86aae68e54b", "Animals"),
            TagDef("68bf09ff8fdeab0b6a9bc2b7", "Comics"),
            TagDef("685148d215e8b86aae68e4f8", "Cooking"),
            TagDef("685148df15e8b86aae68e534", "Crossdressing"),
            TagDef("685148d915e8b86aae68e519", "Delinquents"),
            TagDef("685146c5f3ed681c80f257e4", "Demons"),
            TagDef("685148d715e8b86aae68e505", "Genderswap"),
            TagDef("685148d615e8b86aae68e501", "Ghosts"),
            TagDef("685148d015e8b86aae68e4e8", "Gyaru"),
            TagDef("685146c5f3ed681c80f257e8", "Harem"),
            TagDef("68bfceaf4dbc442a26519889", "Hentai"),
            TagDef("685148f215e8b86aae68e584", "Incest"),
            TagDef("685148d715e8b86aae68e506", "Loli"),
            TagDef("685148d915e8b86aae68e518", "Mafia"),
            TagDef("685148d715e8b86aae68e509", "Magic"),
            TagDef("68f5f5ce5f29d3c1863dec3a", "Manhwa 18+"),
            TagDef("6851490615e8b86aae68e5c2", "Martial Arts"),
            TagDef("685148e215e8b86aae68e541", "Military"),
            TagDef("685148db15e8b86aae68e52c", "Monster Girls"),
            TagDef("685146c5f3ed681c80f257e2", "Monsters"),
            TagDef("685148d015e8b86aae68e4e4", "Music"),
            TagDef("685148d715e8b86aae68e508", "Ninja"),
            TagDef("685148d315e8b86aae68e4fd", "Office Workers"),
            TagDef("6851498815e8b86aae68e714", "Police"),
            TagDef("685148e215e8b86aae68e540", "Post-Apocalyptic"),
            TagDef("685146c5f3ed681c80f257e1", "Reincarnation"),
            TagDef("685148df15e8b86aae68e533", "Reverse Harem"),
            TagDef("6851490415e8b86aae68e5b9", "Samurai"),
            TagDef("685148d015e8b86aae68e4e7", "School Life"),
            TagDef("6a0025c263a8d384c0a4be07", "Seinen"),
            TagDef("685148d115e8b86aae68e4ed", "Shota"),
            TagDef("685148db15e8b86aae68e528", "Supernatural"),
            TagDef("685148cf15e8b86aae68e4dc", "Survival"),
            TagDef("6851490c15e8b86aae68e5d1", "Time Travel"),
            TagDef("6851493515e8b86aae68e645", "Traditional Games"),
            TagDef("685148f915e8b86aae68e597", "Vampires"),
            TagDef("685148e115e8b86aae68e53c", "Video Games"),
            TagDef("6851492115e8b86aae68e602", "Villainess"),
            TagDef("68514a1115e8b86aae68e83e", "Virtual Reality"),
            TagDef("6851490c15e8b86aae68e5d3", "Zombies"),
        )
    }


    @MangaSourceParser("MANGABALL_AR", "Manga Ball (Arabic)", "ar")
    class Arabic(context: MangaLoaderContext) : MangaBallParser(context, MangaParserSource.MANGABALL_AR, setOf("ar"))

    @MangaSourceParser("MANGABALL_BG", "Manga Ball (Bulgarian)", "bg")
    class Bulgarian(context: MangaLoaderContext) : MangaBallParser(context, MangaParserSource.MANGABALL_BG, setOf("bg"))

    @MangaSourceParser("MANGABALL_BN", "Manga Ball (Bengali)", "bn")
    class Bengali(context: MangaLoaderContext) : MangaBallParser(context, MangaParserSource.MANGABALL_BN, setOf("bn"))

    @MangaSourceParser("MANGABALL_CA", "Manga Ball (Catalan)", "ca")
    class Catalan(context: MangaLoaderContext) : MangaBallParser(
        context,
        MangaParserSource.MANGABALL_CA,
        setOf("ca", "ca-ad", "ca-es", "ca-fr", "ca-it", "ca-pt"),
    )

    @MangaSourceParser("MANGABALL_CS", "Manga Ball (Czech)", "cs")
    class Czech(context: MangaLoaderContext) : MangaBallParser(context, MangaParserSource.MANGABALL_CS, setOf("cs"))

    @MangaSourceParser("MANGABALL_DA", "Manga Ball (Danish)", "da")
    class Danish(context: MangaLoaderContext) : MangaBallParser(context, MangaParserSource.MANGABALL_DA, setOf("da"))

    @MangaSourceParser("MANGABALL_DE", "Manga Ball (German)", "de")
    class German(context: MangaLoaderContext) : MangaBallParser(context, MangaParserSource.MANGABALL_DE, setOf("de"))

    @MangaSourceParser("MANGABALL_EL", "Manga Ball (Greek)", "el")
    class Greek(context: MangaLoaderContext) : MangaBallParser(context, MangaParserSource.MANGABALL_EL, setOf("el"))

    @MangaSourceParser("MANGABALL_EN", "Manga Ball (English)", "en")
    class English(context: MangaLoaderContext) : MangaBallParser(context, MangaParserSource.MANGABALL_EN, setOf("en"))

    @MangaSourceParser("MANGABALL_ES", "Manga Ball (Spanish)", "es")
    class Spanish(context: MangaLoaderContext) : MangaBallParser(
        context,
        MangaParserSource.MANGABALL_ES,
        setOf("es", "es-ar", "es-mx", "es-es", "es-la", "es-419"),
    )

    @MangaSourceParser("MANGABALL_FA", "Manga Ball (Persian)", "fa")
    class Persian(context: MangaLoaderContext) : MangaBallParser(context, MangaParserSource.MANGABALL_FA, setOf("fa"))

    @MangaSourceParser("MANGABALL_FI", "Manga Ball (Finnish)", "fi")
    class Finnish(context: MangaLoaderContext) : MangaBallParser(context, MangaParserSource.MANGABALL_FI, setOf("fi"))

    @MangaSourceParser("MANGABALL_FR", "Manga Ball (French)", "fr")
    class French(context: MangaLoaderContext) : MangaBallParser(context, MangaParserSource.MANGABALL_FR, setOf("fr"))

    @MangaSourceParser("MANGABALL_HE", "Manga Ball (Hebrew)", "he")
    class Hebrew(context: MangaLoaderContext) : MangaBallParser(context, MangaParserSource.MANGABALL_HE, setOf("he"))

    @MangaSourceParser("MANGABALL_HI", "Manga Ball (Hindi)", "hi")
    class Hindi(context: MangaLoaderContext) : MangaBallParser(context, MangaParserSource.MANGABALL_HI, setOf("hi"))

    @MangaSourceParser("MANGABALL_HU", "Manga Ball (Hungarian)", "hu")
    class Hungarian(context: MangaLoaderContext) : MangaBallParser(context, MangaParserSource.MANGABALL_HU, setOf("hu"))

    @MangaSourceParser("MANGABALL_ID", "Manga Ball (Indonesian)", "id")
    class Indonesian(context: MangaLoaderContext) : MangaBallParser(context, MangaParserSource.MANGABALL_ID, setOf("id"))

    @MangaSourceParser("MANGABALL_IT", "Manga Ball (Italian)", "it")
    class Italian(context: MangaLoaderContext) : MangaBallParser(
        context,
        MangaParserSource.MANGABALL_IT,
        setOf("it", "it-it"),
    )

    @MangaSourceParser("MANGABALL_IS", "Manga Ball (Icelandic)", "is")
    class Icelandic(context: MangaLoaderContext) : MangaBallParser(
        context,
        MangaParserSource.MANGABALL_IS,
        setOf("ib", "ib-is", "is"),
    )

    @MangaSourceParser("MANGABALL_JA", "Manga Ball (Japanese)", "ja")
    class Japanese(context: MangaLoaderContext) : MangaBallParser(context, MangaParserSource.MANGABALL_JA, setOf("jp", "ja"))

    @MangaSourceParser("MANGABALL_KO", "Manga Ball (Korean)", "ko")
    class Korean(context: MangaLoaderContext) : MangaBallParser(context, MangaParserSource.MANGABALL_KO, setOf("kr"))

    @MangaSourceParser("MANGABALL_KN", "Manga Ball (Kannada)", "kn")
    class Kannada(context: MangaLoaderContext) : MangaBallParser(
        context,
        MangaParserSource.MANGABALL_KN,
        setOf("kn", "kn-in", "kn-my", "kn-sg", "kn-tw"),
    )

    @MangaSourceParser("MANGABALL_ML", "Manga Ball (Malayalam)", "ml")
    class Malayalam(context: MangaLoaderContext) : MangaBallParser(
        context,
        MangaParserSource.MANGABALL_ML,
        setOf("ml", "ml-in", "ml-my", "ml-sg", "ml-tw"),
    )

    @MangaSourceParser("MANGABALL_MS", "Manga Ball (Malay)", "ms")
    class Malay(context: MangaLoaderContext) : MangaBallParser(context, MangaParserSource.MANGABALL_MS, setOf("ms"))

    @MangaSourceParser("MANGABALL_NE", "Manga Ball (Nepali)", "ne")
    class Nepali(context: MangaLoaderContext) : MangaBallParser(context, MangaParserSource.MANGABALL_NE, setOf("ne"))

    @MangaSourceParser("MANGABALL_NL", "Manga Ball (Dutch)", "nl")
    class Dutch(context: MangaLoaderContext) : MangaBallParser(
        context,
        MangaParserSource.MANGABALL_NL,
        setOf("nl", "nl-be"),
    )

    @MangaSourceParser("MANGABALL_NO", "Manga Ball (Norwegian)", "no")
    class Norwegian(context: MangaLoaderContext) : MangaBallParser(context, MangaParserSource.MANGABALL_NO, setOf("no"))

    @MangaSourceParser("MANGABALL_PL", "Manga Ball (Polish)", "pl")
    class Polish(context: MangaLoaderContext) : MangaBallParser(context, MangaParserSource.MANGABALL_PL, setOf("pl"))

    @MangaSourceParser("MANGABALL_PTBR", "Manga Ball (Portuguese)", "pt")
    class PortugueseBrazil(context: MangaLoaderContext) : MangaBallParser(
        context,
        MangaParserSource.MANGABALL_PTBR,
        setOf("pt-br", "pt-pt", "pt"),
    )

    @MangaSourceParser("MANGABALL_RO", "Manga Ball (Romanian)", "ro")
    class Romanian(context: MangaLoaderContext) : MangaBallParser(context, MangaParserSource.MANGABALL_RO, setOf("ro"))

    @MangaSourceParser("MANGABALL_RU", "Manga Ball (Russian)", "ru")
    class Russian(context: MangaLoaderContext) : MangaBallParser(context, MangaParserSource.MANGABALL_RU, setOf("ru"))

    @MangaSourceParser("MANGABALL_SK", "Manga Ball (Slovak)", "sk")
    class Slovak(context: MangaLoaderContext) : MangaBallParser(context, MangaParserSource.MANGABALL_SK, setOf("sk"))

    @MangaSourceParser("MANGABALL_SL", "Manga Ball (Slovenian)", "sl")
    class Slovenian(context: MangaLoaderContext) : MangaBallParser(context, MangaParserSource.MANGABALL_SL, setOf("sl"))

    @MangaSourceParser("MANGABALL_SQ", "Manga Ball (Albanian)", "sq")
    class Albanian(context: MangaLoaderContext) : MangaBallParser(context, MangaParserSource.MANGABALL_SQ, setOf("sq"))

    @MangaSourceParser("MANGABALL_SR", "Manga Ball (Serbian)", "sr")
    class Serbian(context: MangaLoaderContext) : MangaBallParser(
        context,
        MangaParserSource.MANGABALL_SR,
        setOf("sr", "sr-cyrl"),
    )

    @MangaSourceParser("MANGABALL_SV", "Manga Ball (Swedish)", "sv")
    class Swedish(context: MangaLoaderContext) : MangaBallParser(context, MangaParserSource.MANGABALL_SV, setOf("sv"))

    @MangaSourceParser("MANGABALL_TA", "Manga Ball (Tamil)", "ta")
    class Tamil(context: MangaLoaderContext) : MangaBallParser(context, MangaParserSource.MANGABALL_TA, setOf("ta"))

    @MangaSourceParser("MANGABALL_TH", "Manga Ball (Thai)", "th")
    class Thai(context: MangaLoaderContext) : MangaBallParser(
        context,
        MangaParserSource.MANGABALL_TH,
        setOf("th", "th-hk", "th-kh", "th-la", "th-my", "th-sg"),
    )

    @MangaSourceParser("MANGABALL_TR", "Manga Ball (Turkish)", "tr")
    class Turkish(context: MangaLoaderContext) : MangaBallParser(context, MangaParserSource.MANGABALL_TR, setOf("tr"))

    @MangaSourceParser("MANGABALL_UK", "Manga Ball (Ukrainian)", "uk")
    class Ukrainian(context: MangaLoaderContext) : MangaBallParser(context, MangaParserSource.MANGABALL_UK, setOf("uk"))

    @MangaSourceParser("MANGABALL_VI", "Manga Ball (Vietnamese)", "vi")
    class Vietnamese(context: MangaLoaderContext) : MangaBallParser(context, MangaParserSource.MANGABALL_VI, setOf("vi"))

    @MangaSourceParser("MANGABALL_ZH", "Manga Ball (Chinese)", "zh")
    class Chinese(context: MangaLoaderContext) : MangaBallParser(
        context,
        MangaParserSource.MANGABALL_ZH,
        setOf("zh", "zh-cn", "zh-hk", "zh-mo", "zh-sg", "zh-tw", "cn"),
    )

    @MangaSourceParser("MANGABALL_KA", "Manga Ball (Georgian)", "ka")
    class Georgian(context: MangaLoaderContext) : MangaBallParser(
        context,
        MangaParserSource.MANGABALL_KA,
        setOf("ka"),
    )

    @MangaSourceParser("MANGABALL_LT", "Manga Ball (Lithuanian)", "lt")
    class Lithuanian(context: MangaLoaderContext) : MangaBallParser(
        context,
        MangaParserSource.MANGABALL_LT,
        setOf("lt"),
    )

    @MangaSourceParser("MANGABALL_MN", "Manga Ball (Mongolian)", "mn")
    class Mongolian(context: MangaLoaderContext) : MangaBallParser(
        context,
        MangaParserSource.MANGABALL_MN,
        setOf("mn"),
    )

    @MangaSourceParser("MANGABALL_MY", "Manga Ball (Burmese)", "my")
    class Burmese(context: MangaLoaderContext) : MangaBallParser(
        context,
        MangaParserSource.MANGABALL_MY,
        setOf("my"),
    )
    /** Latin and Tagalog language missing
     * getDetails rating not working?
     */
}
