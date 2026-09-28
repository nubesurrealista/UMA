package tsuki.site.all.nsfw

import tsuki.ErrorMessages
import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.parsers.GalleryAdultsParser

import tsuki.model.ContentType
import tsuki.model.Manga
import tsuki.model.MangaChapter
import tsuki.model.MangaListFilter
import tsuki.model.MangaPage
import tsuki.model.MangaParserSource
import tsuki.model.MangaTag
import tsuki.model.SortOrder

import tsuki.util.generateUid
import tsuki.util.oneOrThrowIfMany
import tsuki.util.parseHtml
import tsuki.util.toAbsoluteUrl
import tsuki.util.urlEncoded

import org.json.JSONArray
import org.jsoup.nodes.Element
import java.util.EnumSet
import java.util.Locale

@MangaSourceParser("HENTAIENVY", "HentaiEnvy", type = ContentType.HENTAI)
internal class HentaiEnvy(context: MangaLoaderContext) :
    GalleryAdultsParser(context, MangaParserSource.HENTAIENVY, "hentaienvy.com", pageSize = 24) {

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.UPDATED,
        SortOrder.POPULARITY,
        SortOrder.RATING,
        SortOrder.RELEVANCE
    )

    override val selectGallery = ".hnv-gallery-card"
    override val selectGalleryLink = "a.hnv-gallery-card__cover"
    override val selectGalleryImg = "a.hnv-gallery-card__cover img"
    override val selectGalleryTitle = ".hnv-gallery-card__title"

    override val selectTags = "ul.hnv-legacy-taxonomy__items"

    override fun Element.parseTags(): Set<MangaTag> = select("a").mapNotNull { a ->
        val key = a.attr("href").removeSuffix("/").substringAfterLast('/')
        if (key.isBlank()) return@mapNotNull null
        val title = a.attr("title").takeIf { it.isNotBlank() }
            ?: a.selectFirst(".hnv-gallery-tag__name, .hnv-legacy-taxonomy__name")
                ?.text()?.takeIf { it.isNotBlank() }
            ?: a.text().takeIf { it.isNotBlank() }
            ?: return@mapNotNull null
        MangaTag(key = key, title = title, source = source)
    }.toSet()

    override val selectTitle = ".hnv-gallery-details h1"

    override suspend fun getDetails(manga: Manga): Manga {
        val doc = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseHtml()

        val tags = doc.select(
            "div.hnv-gallery-entity-group:has(:contains(Tags:)) a.hnv-gallery-tag",
        ).mapNotNull { a ->
            val name = a.selectFirst(".hnv-gallery-tag__name")?.text()?.trim()
                ?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            MangaTag(
                key = name.lowercase(Locale.ROOT).replace(' ', '-'),
                title = name,
                source = source,
            )
        }.toSet()

        val authors = doc.select(
            "div.hnv-gallery-entity-group:has(:contains(Artists:)) a.hnv-gallery-tag",
        ).mapNotNull { it ->
            it.selectFirst(".hnv-gallery-tag__name")?.text()?.trim()?.takeIf { it.isNotBlank() }
        }.toSet().ifEmpty {
            doc.select(
                "div.hnv-gallery-entity-group:has(:contains(Groups:)) a.hnv-gallery-tag",
            ).mapNotNull { it ->
                it.selectFirst(".hnv-gallery-tag__name")?.text()?.trim()?.takeIf { it.isNotBlank() }
            }.toSet()
        }

        val branch = doc.select(
            "div.hnv-gallery-entity-group:has(:contains(Languages:)) a.hnv-gallery-tag",
        ).mapNotNull { it ->
            it.selectFirst(".hnv-gallery-tag__name")?.text()?.trim()?.takeIf { it.isNotBlank() }
        }.joinToString(" / ").ifBlank { null }

        val cover = doc.selectFirst(".hnv-gallery-cover img")
            ?.attr("src")
            ?.trim()
            ?.takeIf { it.isNotBlank() }

        val title = doc.selectFirst(selectTitle)?.text()?.trim()?.cleanupTitle()
            ?: manga.title

        return manga.copy(
            title = title,
            tags = tags,
            authors = authors,
            coverUrl = cover ?: manga.coverUrl,
            chapters = listOf(
                MangaChapter(
                    id = manga.id,
                    title = manga.title,
                    number = 1f,
                    volume = 0,
                    url = manga.url,
                    scanlator = null,
                    uploadDate = 0,
                    branch = branch,
                    source = source,
                ),
            ),
        )
    }

    override suspend fun getFilterOptions() = super.getFilterOptions().copy(
        availableLocales = setOf(
            Locale.ENGLISH,
            Locale.FRENCH,
            Locale.JAPANESE,
            Locale.CHINESE,
            Locale("es"),
            Locale("ru"),
            Locale("ko"),
            Locale.GERMAN,
            Locale("pt"),
        ),
    )

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val query = filter.query
        val orderPath = when (order) {
            SortOrder.POPULARITY -> "/popular"
            SortOrder.RATING -> "/top-rated"
            SortOrder.UPDATED -> ""
            SortOrder.RELEVANCE -> "/downloaded"
            else -> ""
        }

        val url = buildString {
            append("https://")
            append(domain)

            when {
                !query.isNullOrEmpty() -> {
                    append("/search/?key=")
                    append(query.urlEncoded())
                    when (order) {
                        SortOrder.POPULARITY -> append("&sort=popular")
                        SortOrder.RATING -> append("&sort=top-rated")
                        else -> Unit
                    }
                    append("&")
                }

                filter.tags.isNotEmpty() -> {
                    if (filter.locale != null) {
                        throw IllegalArgumentException(
                            ErrorMessages.FILTER_BOTH_LOCALE_GENRES_NOT_SUPPORTED,
                        )
                    }
                    val tag = filter.tags.oneOrThrowIfMany()
                        ?: throw IllegalArgumentException("Only one tag is supported")
                    append("/tag/")
                    append(tag.key)
                    append(orderPath)
                    append("/?")
                }

                filter.locale != null -> {
                    append("/language/")
                    append(filter.locale?.toLanguagePath())
                    append(orderPath)
                    append("/?")
                }

                else -> {
                    append(orderPath)
                    append("/?")
                }
            }

            append("page=")
            append(page)
        }

        return parseMangaList(webClient.httpGet(url).parseHtml())
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val galleryId = chapter.url.trimEnd('/')
            .substringAfterLast('/')
            .toIntOrNull() ?: return emptyList()

        val readerDoc = webClient.httpGet("https://$domain/g/$galleryId/1/").parseHtml()
        val reader = readerDoc.selectFirst("section#readerApp") ?: return emptyList()

        val base = reader.attr("data-reader-image-base").trim().trimEnd('/')
        if (base.isEmpty()) return emptyList()

        val json = readerDoc.selectFirst("script#readerPagesJson")?.data() ?: return emptyList()
        val arr = JSONArray(json)

        return (0 until arr.length()).mapNotNull { i ->
            val obj = arr.optJSONObject(i) ?: return@mapNotNull null
            val page = obj.optInt("page", 0)
            val ext = obj.optString("ext", "").trim().removePrefix(".")
            if (page <= 0 || ext.isBlank()) return@mapNotNull null

            MangaPage(
                id = generateUid("$chapter.url#$page"),
                url = "$base/$page.$ext",
                preview = null,
                source = source,
            )
        }
    }

    override suspend fun getPageUrl(page: MangaPage): String = page.url
}
