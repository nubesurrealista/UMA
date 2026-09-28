package tsuki.site.pt.nsfw

import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.parsers.MadaraParser

import tsuki.model.ContentType
import tsuki.model.MangaParserSource

import java.util.Locale

@MangaSourceParser("HANAMIHEAVEN", "HanamiHeaven", "pt", ContentType.HENTAI)
internal class HanamiHeaven(context: MangaLoaderContext) :
    MadaraParser(context, MangaParserSource.HANAMIHEAVEN, "hanamiheaven.org") {
    override val sourceLocale: Locale = Locale.forLanguageTag("pt-BR")
    override val datePattern = "dd/MM/yyyy"
    override val postReq = false
}
