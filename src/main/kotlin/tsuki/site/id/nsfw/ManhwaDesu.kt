package tsuki.site.id.nsfw

import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.parsers.MangaThemesia

import tsuki.model.ContentType
import tsuki.model.MangaParserSource

import tsuki.util.toAbsoluteUrl

import org.jsoup.nodes.Element

@MangaSourceParser("MANHWADESU", "ManhwaDesu", "id", ContentType.HENTAI)
internal class ManhwaDesu(context: MangaLoaderContext) :
    MangaThemesia(context, MangaParserSource.MANHWADESU, "manhwadesu.wiki") {

    override val mangaDirectory = "komik"

    override fun Element.imgAttr(): String {
        attributes()
            .firstOrNull { it.key.endsWith("original-src") }
            ?.let { attribute ->
                val value = attr(attribute.key).trim()
                if (value.isNotEmpty()) {
                    return value.toAbsoluteUrl(domain).substringBefore("?")
                }
            }
        return imgAttrFallback()
    }
}
