package org.koitharu.kotatsu.parsers.site.tachiyomi.es.lmtoonline

import org.jsoup.nodes.Document
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.ContentType
import org.koitharu.kotatsu.parsers.model.Demographic
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaListFilter
import org.koitharu.kotatsu.parsers.model.MangaListFilterCapabilities
import org.koitharu.kotatsu.parsers.model.MangaListFilterOptions
import org.koitharu.kotatsu.parsers.model.MangaPage
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.MangaState
import org.koitharu.kotatsu.parsers.model.MangaTag
import org.koitharu.kotatsu.parsers.model.RATING_UNKNOWN
import org.koitharu.kotatsu.parsers.model.SortOrder
import org.koitharu.kotatsu.parsers.util.generateUid
import org.koitharu.kotatsu.parsers.util.json
import java.util.EnumSet

@MangaSourceParser("LMTO_ONLINE", "LMTO.online", "es")
internal class LmtoOnline(
    context: MangaLoaderContext,
) : PagedMangaParser(context, MangaParserSource.LMTO_ONLINE, pageSize = 20) {

    override val configKeyDomain = ConfigKey.Domain("lmto.online")

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.ALPHABETICAL,
        SortOrder.UPDATED,
        SortOrder.POPULARITY,
    )

    override val filterCapabilities = MangaListFilterCapabilities(
        isMultipleTagsSupported = true,
        isTagsExclusionSupported = false,
        isSearchSupported = true,
        isSearchWithFiltersSupported = true,
    )

    @Volatile
    private var mangaCache = emptyList<MangaDto>()

    @Volatile
    private var cacheTimestamp = 0L

    private val cacheDuration = 10 * 60 * 1000L

    @Synchronized
    private suspend fun fetchMangas() {
        val now = System.currentTimeMillis()
        if (mangaCache.isNotEmpty() && now - cacheTimestamp < cacheDuration) return

        val html = webClient.httpGet("https://$domain/series").parseHtml()
        val payload = extractNextJsData<MangaList>(html)
        if (payload != null) {
            mangaCache = payload.mangas
            cacheTimestamp = now
        }
    }

    override suspend fun getFilterOptions(): MangaListFilterOptions {
        fetchMangas()
        val allGenres = mangaCache
            .flatMap { it.genres.orEmpty() }
            .distinct()
            .sorted()
            .map { MangaTag(it, it, source) }
            .toSet()

        return MangaListFilterOptions(
            availableTags = allGenres,
            availableStates = EnumSet.of(
                MangaState.ONGOING,
                MangaState.FINISHED,
                MangaState.ABANDONED,
                MangaState.PAUSED,
            ),
            availableContentTypes = EnumSet.of(
                ContentType.MANGA,
                ContentType.MANHWA,
                ContentType.MANHUA,
            ),
            availableDemographics = EnumSet.of(
                Demographic.SHOUNEN,
                Demographic.SHOUJO,
                Demographic.SEINEN,
                Demographic.JOSEI,
            ),
        )
    }

    override suspend fun getListPage(
        page: Int,
        order: SortOrder,
        filter: MangaListFilter,
    ): List<Manga> {
        fetchMangas()

        val query = filter.query.orEmpty().trim()
        val selectedGenres = filter.tags.map { it.key }.toSet()

        val filtered = mangaCache
            .asSequence()
            .filter { manga ->
                query.isEmpty() ||
                    manga.title.contains(query, ignoreCase = true) ||
                    manga.alternativeTitles?.any { it.contains(query, ignoreCase = true) } == true
            }
            .filter { manga ->
                if (filter.types.isEmpty()) true
                else filter.types.any { type ->
                    val typeStr = when (type) {
                        ContentType.MANGA -> "manga"
                        ContentType.MANHWA -> "manhwa"
                        ContentType.MANHUA -> "manhua"
                        else -> ""
                    }
                    manga.type.equals(typeStr, ignoreCase = true)
                }
            }
            .filter { manga ->
                if (filter.states.isEmpty()) true
                else filter.states.any { state ->
                    val stateStr = when (state) {
                        MangaState.ONGOING -> "ongoing"
                        MangaState.FINISHED -> "completed"
                        MangaState.ABANDONED -> "cancelled"
                        MangaState.PAUSED -> "hiatus"
                        else -> ""
                    }
                    manga.status.equals(stateStr, ignoreCase = true)
                }
            }
            .filter { manga ->
                if (filter.demographics.isEmpty()) true
                else filter.demographics.any { demo ->
                    val demoStr = when (demo) {
                        Demographic.SHOUNEN -> "shounen"
                        Demographic.SHOUJO -> "shoujo"
                        Demographic.SEINEN -> "seinen"
                        Demographic.JOSEI -> "josei"
                    }
                    manga.demographic.equals(demoStr, ignoreCase = true)
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
                    SortOrder.UPDATED -> sequence.sortedByDescending { it.latestChapterCreatedAt ?: 0L }
                    SortOrder.POPULARITY -> sequence.sortedByDescending { it.totalViews ?: 0L }
                    else -> sequence
                }
            }
            .toList()

        val startIndex = (page - 1) * pageSize
        if (startIndex >= filtered.size) return emptyList()

        return filtered
            .drop(startIndex)
            .take(pageSize)
            .map { it.toManga() }
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val slug = manga.url.removePrefix("/manga/").removeSuffix("/")
        val html = webClient.httpGet("https://$domain/manga/$slug").parseHtml()

        val detailsPayload = extractNextJsData<MangaDetails>(html)
        val chapterPayload = extractNextJsData<ChapterList>(html)

        val dto = detailsPayload?.manga
        val chaptersDto = chapterPayload?.chapters.orEmpty()

        val authors = mutableSetOf<String>()
        dto?.author?.takeIf { it.isNotBlank() }?.let { authors.add(it) }
        dto?.artist?.takeIf { it.isNotBlank() }?.let { authors.add(it) }

        val tags = dto?.genres.orEmpty().map { MangaTag(it, it, source) }.toSet()

        val chapters = chaptersDto.map { ch ->
            val number = ch.number ?: -1f
            val chapterUrl = "/manga/$slug/${ch.id}"
            val titleStr = buildString {
                append("Capítulo ")
                append(if (number % 1f == 0f) number.toInt().toString() else number.toString())
                if (!ch.title.isNullOrBlank()) {
                    append(", ")
                    append(ch.title)
                }
            }

            MangaChapter(
                id = generateUid(chapterUrl),
                title = titleStr,
                number = number,
                volume = 0,
                url = chapterUrl,
                scanlator = null,
                uploadDate = ch.createdAt ?: 0L,
                branch = null,
                source = source,
            )
        }.sortedBy { it.number }

        return manga.copy(
            title = dto?.title ?: manga.title,
            coverUrl = dto?.cover ?: manga.coverUrl,
            description = dto?.synopsis?.trim().orEmpty(),
            authors = authors,
            tags = tags,
            altTitles = dto?.alternativeTitles.orEmpty().toSet(),
            state = when (dto?.status?.lowercase()) {
                "ongoing" -> MangaState.ONGOING
                "completed" -> MangaState.FINISHED
                "cancelled" -> MangaState.ABANDONED
                "hiatus" -> MangaState.PAUSED
                else -> null
            },
            chapters = chapters,
        )
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val html = webClient.httpGet("https://$domain${chapter.url}").parseHtml()
        val payload = extractNextJsData<ChapterPages>(html) ?: return emptyList()

        return payload.chapter.pages.orEmpty().mapIndexed { index, imageUrl ->
            MangaPage(
                id = generateUid(imageUrl),
                url = imageUrl,
                preview = null,
                source = source,
            )
        }
    }

    override suspend fun getRelatedManga(seed: Manga): List<Manga> = emptyList()

    private inline fun <reified T> extractNextJsData(html: Document): T? {
        val script = html.selectFirst("script#__NEXT_DATA__")?.data() ?: return null
        return try {
            val wrapper = json.decodeFromString<NextJsWrapper<T>>(script)
            wrapper.props.pageProps
        } catch (_: Exception) {
            null
        }
    }

    private fun MangaDto.toManga(): Manga {
        val urlPath = "/manga/$slug"
        return Manga(
            id = generateUid(urlPath),
            url = urlPath,
            publicUrl = "https://$domain$urlPath",
            title = title,
            coverUrl = cover.orEmpty(),
            source = source,
            altTitles = alternativeTitles.orEmpty().toSet(),
            largeCoverUrl = null,
            authors = emptySet(),
            contentRating = null,
            rating = RATING_UNKNOWN,
            state = null,
            tags = emptySet(),
        )
    }
}
