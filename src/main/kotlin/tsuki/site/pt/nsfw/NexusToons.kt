package tsuki.site.pt.nsfw

import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.config.ConfigKey
import tsuki.core.PagedMangaParser

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

import tsuki.util.extractChapterNumber
import tsuki.util.generateUid
import tsuki.util.nullIfEmpty
import tsuki.util.parseJson
import tsuki.util.urlEncoded

import org.json.JSONObject
import tsuki.util.mapNotNullToSet
import java.text.SimpleDateFormat
import java.util.EnumSet
import java.util.Locale
import java.util.TimeZone
import kotlin.random.Random
import java.security.MessageDigest
import java.util.Base64

internal object NexusDecrypt {

    private const val CRYPTO_SECRET = "OrionNexus2025CryptoKey!Secure"
    private const val NUM_KEYS = 5

    private var keys: List<KeyData>? = null
    private var initialized = false

    private data class KeyData(
        val key: ByteArray,
        val sbox: IntArray,
        val rsbox: IntArray,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as KeyData

            if (!key.contentEquals(other.key)) return false
            if (!sbox.contentEquals(other.sbox)) return false
            if (!rsbox.contentEquals(other.rsbox)) return false

            return true
        }

        override fun hashCode(): Int {
            var result = key.contentHashCode()
            result = 31 * result + sbox.contentHashCode()
            result = 31 * result + rsbox.contentHashCode()
            return result
        }
    }

    @Synchronized
    private fun initialize() {
        if (initialized) return
        val derived = mutableListOf<KeyData>()
        for (i in 0 until NUM_KEYS) {
            val pattern = "_orion_key_${i}_v2_$CRYPTO_SECRET"
            val hash = MessageDigest.getInstance("SHA-256").digest(pattern.toByteArray())
            val hex = hash.joinToString("") { "%02x".format(it) }
            val keyBytes = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            val kd = KeyData(keyBytes, IntArray(256), IntArray(256))
            initSBoxForKey(kd)
            derived.add(kd)
        }
        keys = derived
        initialized = true
    }

    private fun initSBoxForKey(kd: KeyData) {
        val key = kd.key
        for (i in 0 until 256) kd.sbox[i] = i
        var j = 0
        for (i in 0 until 256) {
            j = (j + kd.sbox[i] + (key[i % key.size].toInt() and 0xFF)) % 256
            val tmp = kd.sbox[i]
            kd.sbox[i] = kd.sbox[j]
            kd.sbox[j] = tmp
        }
        for (i in 0 until 256) kd.rsbox[kd.sbox[i]] = i
    }

    private fun rotateRight(byte: Int, shift: Int): Int {
        val s = shift % 8
        return ((byte ushr s) or (byte shl (8 - s))) and 0xFF
    }

    fun decrypt(keyIndex: Int, base64Data: String): String {
        initialize()
        val keyList = keys ?: throw IllegalStateException("Not initialized")
        require(keyIndex in 0 until NUM_KEYS) { "Invalid key index: $keyIndex" }

        val kd = keyList[keyIndex]
        val key = kd.key
        val rsbox = kd.rsbox

        val input = Base64.getDecoder().decode(base64Data)
        val output = ByteArray(input.size)
        val keyLen = key.size

        for (i in input.size - 1 downTo 0) {
            var byte = input[i].toInt() and 0xFF

            byte = if (i > 0) {
                byte xor (input[i - 1].toInt() and 0xFF)
            } else {
                byte xor (key[keyLen - 1].toInt() and 0xFF)
            }

            byte = rsbox[byte]

            val rotAmount =
                ((key[(i + 3) % keyLen].toInt() and 0xFF) + (i and 0xFF) and 0xFF) % 7 + 1

            byte = rotateRight(byte, rotAmount)
            byte = byte xor (key[i % keyLen].toInt() and 0xFF)

            output[i] = byte.toByte()
        }

        return String(output, Charsets.UTF_8)
    }
}

@MangaSourceParser("NEXUSTOONS", "Nexus Toons", "pt", ContentType.HENTAI)
internal class NexusToons(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.NEXUSTOONS, pageSize = 50) {

    override val configKeyDomain = ConfigKey.Domain("nx-toons.xyz")

    override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
        super.onCreateConfig(keys)
        keys.add(userAgentKey)
    }

    override fun getRequestHeaders() = super.getRequestHeaders().newBuilder()
        .set("Accept", "application/json")
        .set("Referer", "https://$domain/")
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
            isMultipleTagsSupported = true,
        )

    init {
        paginator.firstPage = 1
        searchPaginator.firstPage = 1
    }

    override suspend fun getFilterOptions(): MangaListFilterOptions = MangaListFilterOptions(
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
        availableTags = NEXUS_GENRES.map { (title, slug) ->
            MangaTag(key = slug, title = title, source = source)
        }.toSet(),
    )


    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val url = buildString {
            append("https://$domain/api/mangas")
            append("?page=")
            append(page)
            append("&limit=50")
            append("&includeNsfw=true")

            append("&sortBy=")
            append(
                when (order) {
                    SortOrder.POPULARITY -> "views"
                    SortOrder.UPDATED -> "lastChapterAt"
                    SortOrder.NEWEST -> "created"
                    SortOrder.ALPHABETICAL -> "title"
                    else -> "updatedAt"
                },
            )
            append("&sortOrder=")
            append(if (order == SortOrder.ALPHABETICAL) "asc" else "desc")

            filter.query?.trim()?.takeIf { it.isNotEmpty() }?.let {
                append("&search=")
                append(it.urlEncoded())
            }

            if (filter.states.isNotEmpty()) {
                append("&status=")
                append(filter.states.joinToString(",") { it.toApiStatus() })
            }

            if (filter.types.isNotEmpty()) {
                append("&type=")
                append(filter.types.joinToString(",") { it.toApiType() })
            }

            if (filter.tags.isNotEmpty()) {
                append("&genres=")
                append(filter.tags.joinToString(",") { it.key })
            }
        }

        val json = fetchApi(url)
        return parseMangaList(json)
    }

    private fun parseMangaList(json: JSONObject): List<Manga> {
        val data = json.optJSONArray("data") ?: return emptyList()
        return (0 until data.length()).mapNotNull { i ->
            val item = data.optJSONObject(i) ?: return@mapNotNull null
            val slug = item.optString("slug").nullIfEmpty() ?: return@mapNotNull null
            Manga(
                id = generateUid("/manga/$slug"),
                url = "/manga/$slug",
                publicUrl = "https://$domain/manga/$slug",
                title = item.optString("title").orEmpty(),
                altTitles = emptySet(),
                coverUrl = item.optString("coverImage").nullIfEmpty(),
                rating = RATING_UNKNOWN,
                tags = emptySet(),
                authors = emptySet(),
                state = null,
                source = source,
                contentRating = ContentRating.ADULT,
            )
        }
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val slug = manga.url.substringAfter("/manga/").trimEnd('/')
        val json = fetchApi("https://$domain/api/manga/$slug")

        val chapters = json.optJSONArray("chapters")?.let { arr ->
            (0 until arr.length()).mapNotNull { i ->
                val ch = arr.optJSONObject(i) ?: return@mapNotNull null
                val id = ch.optInt("id")
                if (id == 0) return@mapNotNull null
                val number = ch.optString("number").orEmpty()
                val title = ch.optString("title").nullIfEmpty()
                val createdAt = ch.optString("createdAt").orEmpty()

                MangaChapter(
                    id = generateUid("/read/$id/$slug"),
                    title = if (!title.isNullOrBlank()) {
                        "$title $number"
                    } else {
                        "Capítulo ${number.removeSuffix(".0")}"
                    },
                    number = number.extractChapterNumber(),
                    volume = 0,
                    url = "/read/$id/$slug",
                    uploadDate = parseIsoDate(createdAt),
                    scanlator = null,
                    branch = null,
                    source = source,
                )
            }
        }.orEmpty().sortedBy { it.number }

        val categories = json.optJSONArray("categories")?.let { arr ->
            (0 until arr.length())
                .mapNotNull { arr.optJSONObject(it)?.optString("name")?.nullIfEmpty() }
        }.orEmpty()

        return manga.copy(
            title = json.optString("title").nullIfEmpty() ?: manga.title,
            coverUrl = json.optString("coverImage").nullIfEmpty() ?: manga.coverUrl,
            description = json.optString("description").nullIfEmpty(),
            authors = setOfNotNull(
                json.optString("author").nullIfEmpty(),
                json.optString("artist").nullIfEmpty(),
            ),
            state = json.optString("status").parseState(),
            tags = categories.mapNotNullToSet { name ->
                MangaTag(
                    key = name.lowercase(Locale.ROOT).replace(' ', '-'),
                    title = name,
                    source = source,
                )
            },
            chapters = chapters,
        )
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val chapterId = chapter.url
            .substringAfter("/read/")
            .substringBefore("/")
        if (chapterId.isBlank()) return emptyList()

        val json = fetchApi("https://$domain/api/read/$chapterId")

        val pageToken = json.optString("pageToken").nullIfEmpty()
        val pagesArr = json.optJSONArray("pages") ?: return emptyList()

        return (0 until pagesArr.length()).mapNotNull { i ->
            val page = pagesArr.optJSONObject(i) ?: return@mapNotNull null
            val imageUrl = page.optString("imageUrl").nullIfEmpty()
                ?: pageToken?.let { "https://$domain/api/p/$it/$i" }
                ?: return@mapNotNull null
            MangaPage(
                id = generateUid(imageUrl),
                url = imageUrl,
                preview = null,
                source = source,
            )
        }
    }

    private suspend fun fetchApi(url: String): JSONObject {
        val raw = webClient.httpGet(url).parseJson()

        val v = raw.optInt("v", 0)
        if (v != 1 && v != 2) return raw

        val d = raw.optString("d").nullIfEmpty() ?: return raw
        val k = if (v == 1) 0 else raw.optInt("k", 0)

        return JSONObject(NexusDecrypt.decrypt(k, d))
    }


    private fun encodeChapterUrl(chapterId: String, mangaSlug: String): String {
        val timestamp = System.currentTimeMillis().toString(36)
        val padding = randomString(20 + Random.nextInt(11))
        val data = "$chapterId|$mangaSlug|$timestamp|$padding"

        val xored = xorCipher(data, CHAPTER_ENCRYPTION_KEY)
        val firstEncode = base64UrlEncode(xored)
        val secondEncode = base64UrlEncode("$firstEncode|${randomString(10)}")

        return if (secondEncode.length >= 64) {
            secondEncode
        } else {
            secondEncode + randomString(64 - secondEncode.length)
        }
    }

    @Suppress("SameParameterValue")
    private fun xorCipher(input: String, key: String): String = input.mapIndexed { i, char ->
        (char.code xor key[i % key.length].code).toChar()
    }.joinToString("")

    private fun base64UrlEncode(input: String): String {
        val bytes = input.map { it.code.toByte() }.toByteArray()
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun randomString(length: Int): String {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        return buildString(length) {
            repeat(length) { append(chars.random()) }
        }
    }

    private fun MangaState.toApiStatus(): String = when (this) {
        MangaState.ONGOING -> "ongoing"
        MangaState.FINISHED -> "completed"
        MangaState.PAUSED -> "hiatus"
        MangaState.ABANDONED -> "cancelled"
        else -> ""
    }

    private fun ContentType.toApiType(): String = when (this) {
        ContentType.MANGA -> "manga"
        ContentType.MANHWA -> "manhwa"
        ContentType.MANHUA -> "manhua"
        ContentType.COMICS -> "comic"
        else -> ""
    }

    private fun String?.parseState(): MangaState? = when (this?.lowercase(Locale.ROOT)) {
        "ongoing" -> MangaState.ONGOING
        "completed" -> MangaState.FINISHED
        "hiatus" -> MangaState.PAUSED
        "cancelled", "canceled" -> MangaState.ABANDONED
        else -> null
    }

    private fun parseIsoDate(text: String): Long {
        if (text.isBlank()) return 0L
        return try {
            ISO_FORMAT.parse(text.substringBefore("."))?.time ?: 0L
        } catch (_: Exception) {
            0L
        }
    }

    private companion object {
        private const val CHAPTER_ENCRYPTION_KEY =
            "NexusToons2026SecretKeyForChapterEncryption!@#$"

        private val ISO_FORMAT = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.ROOT).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }

        private val NEXUS_GENRES: List<Pair<String, String>> = listOf(
            "Academia de Magia" to "academia-de-magia",
            "Acadêmica" to "academica",
            "Ação" to "acao",
            "Adaptação" to "adaptacao",
            "Adaptação de Novel" to "adaptacao-de-novel",
            "Adultério" to "adulterio",
            "Adulto" to "adulto",
            "Ahegao" to "ahegao",
            "Apocalipse" to "apocalipse",
            "App" to "app",
            "Artes Marciais" to "artes-marciais",
            "Aventura" to "aventura",
            "BDSM" to "bdsm",
            "Bondage" to "bondage",
            "Bullying" to "bullying",
            "Bunda Grande" to "bunda-grande",
            "Campus" to "campus",
            "Cartoon" to "cartoon",
            "Casada" to "casada",
            "Casamento" to "casamento",
            "Club" to "club",
            "Comédia" to "comedia",
            "Comédia Romântica" to "comedia-romantica",
            "Comida" to "comida",
            "Cotidiano" to "cotidiano",
            "Crime" to "crime",
            "Culinária" to "culinaria",
            "Cultivo" to "cultivo",
            "Curta" to "curta",
            "Cyberpunk" to "cyberpunk",
            "Dark Romance" to "dark-romance",
            "Demônio" to "demonio",
            "Demônios" to "demonios",
            "Domingão" to "dominacao",
            "Doujinshi" to "doujinshi",
            "Dragões" to "dragoes",
            "Drama" to "drama",
            "Ecchi" to "ecchi",
            "Escolar" to "escolar",
            "Escritório" to "escritorio",
            "Esporte" to "esporte",
            "Esportes" to "esportes",
            "Estratégia" to "estrategia",
            "Estudante" to "estudante",
            "Exibicionismo" to "exibicionismo",
            "Família" to "familia",
            "Família Real" to "familia-real",
            "Fantasia" to "fantasia",
            "Fantasmas" to "fantasmas",
            "Fetiche" to "fetiche",
            "Ficção Científica" to "ficcao-cientifica",
            "Futanari" to "futanari",
            "Game" to "game",
            "Game System" to "game-system",
            "Gangster" to "gangster",
            "Gender Bender" to "gender-bender",
            "Gênio" to "genio",
            "Gore" to "gore",
            "Guerra" to "guerra",
            "Hardcore" to "hardcore",
            "Harem" to "harem",
            "Harém Reverso" to "harem-reverso",
            "Hentai" to "hentai",
            "Híbrido" to "hibrido",
            "História" to "historia",
            "Histórico" to "historico",
            "Horror" to "horror",
            "Idols" to "idols",
            "Incesto" to "incesto",
            "Isekai" to "isekai",
            "Jogo" to "jogo",
            "Jogos" to "jogos",
            "Josei" to "josei",
            "Luta" to "luta",
            "Máfia" to "mafia",
            "Magia" to "magia",
            "Magia Escolar" to "magia-escolar",
            "Mature" to "mature",
            "Mecha" to "mecha",
            "Médico" to "medico",
            "Metaverso" to "metaverso",
            "Milf" to "milf",
            "Militar" to "militar",
            "Mistério" to "misterio",
            "Mitologia" to "mitologia",
            "MMORPG" to "mmorpg",
            "Monstros" to "monstros",
            "Murim" to "murim",
            "Música" to "musica",
            "Musical" to "musical",
            "Nerd" to "nerd",
            "Netori" to "netori",
            "Obsessão" to "obsessao",
            "One Shot" to "one-shot",
            "Oneshot" to "oneshot",
            "Peitões" to "peitoes",
            "Perda de Memória" to "perda-de-memoria",
            "Polícia" to "policia",
            "Policial" to "policial",
            "Portais" to "portais",
            "Pós-apocalíptico" to "pos-apocaliptico",
            "Profecias" to "profecias",
            "Prostituição" to "prostituicao",
            "Psicológico" to "psicologico",
            "Psicopata" to "psicopata",
            "Realidade Virtual" to "realidade-virtual",
            "Realismo" to "realismo",
            "Reencarnação" to "reencarnacao",
            "Reencontro" to "reencontro",
            "Regressão" to "regressao",
            "Retornado" to "retornado",
            "Retorno" to "retorno",
            "Revenge" to "revenge",
            "Romance" to "romance",
            "RPG" to "rpg",
            "Sangue" to "sangue",
            "School Life" to "school-life",
            "Sci-Fi" to "sci-fi",
            "Seinen" to "seinen",
            "Sem Censura" to "sem-censura",
            "Shoujo" to "shoujo",
            "Shounen" to "shounen",
            "Sistema" to "sistema",
            "Sistema de Níveis" to "sistema-de-niveis",
            "Slice of Life" to "slice-of-life",
            "Smut" to "smut",
            "Sobrenatural" to "sobrenatural",
            "Sobrevivência" to "sobrevivencia",
            "Sombrio" to "sombrio",
            "Sports" to "sports",
            "Submissão" to "submissao",
            "Sugestivo" to "sugestivo",
            "Super Poderes" to "super-poderes",
            "Supernatural" to "supernatural",
            "Superpoderes" to "superpoderes",
            "Suspense" to "suspense",
            "Tecnológico" to "tecnologico",
            "Tela de Sistema" to "tela-de-sistema",
            "Terror" to "terror",
            "Thriller" to "thriller",
            "Tomboy" to "tomboy",
            "Torre" to "torre",
            "Tóxico" to "toxico",
            "Tragédia" to "tragedia",
            "Traição" to "traicao",
            "Triângulo" to "triangulo",
            "Tsundere" to "tsundere",
            "Universitários" to "universitarios",
            "Vampiro" to "vampiro",
            "Vampiros" to "vampiros",
            "Viagem no Tempo" to "viagem-no-tempo",
            "Vida Adulta" to "vida-adulta",
            "Vida Cotidiana" to "vida-cotidiana",
            "Vida Escolar" to "vida-escolar",
            "Video Game" to "video-game",
            "Video Games" to "video-games",
            "Vídeo Game" to "vídeo-game-1",
            "Vingança" to "vinganca",
            "Violência" to "violencia",
            "Volta no Tempo" to "volta-no-tempo",
            "VRMMO" to "vrmmo",
            "Web Comic" to "web-comic",
            "Webcomic" to "webcomic",
            "Wuxia" to "wuxia",
            "Xianxia" to "xianxia",
            "Xuanhuan" to "xuanhuan",
            "Yuri" to "yuri",
            "Zumbis" to "zumbis",
        )
    }
}
