package io.nurunuru.app.data

import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Parsed representation of a search query with operator tokens extracted.
 */
data class ParsedSearchQuery(
    val textQuery: String,           // キーワード → searchnosへ
    val hashtags: List<String>,      // #tag → #t フィルタ
    val fromPubkeys: List<String>,   // from:npub / from:hex → authors フィルタ（要解決）
    val fromNip05: List<String>,     // from:user@domain → NIP-05解決後 authors フィルタ
    val since: Long?,                // since:YYYY-MM-DD → Unixタイムスタンプ
    val until: Long?,                // until:YYYY-MM-DD → Unixタイムスタンプ
    val excludeWords: List<String>,  // -word → クライアント側除外フィルタ
    val exactPhrases: List<String>,  // "phrase" → クライアント側完全一致フィルタ
    val mediaFilter: MediaFilter?,   // filter:image/video/link → クライアント側メディアフィルタ
) {
    enum class MediaFilter { IMAGE, VIDEO, LINK }

    /** オペレーターが1つでも含まれる場合 true */
    val hasOperators: Boolean get() =
        hashtags.isNotEmpty() || fromPubkeys.isNotEmpty() || fromNip05.isNotEmpty() ||
        since != null || until != null || excludeWords.isNotEmpty() ||
        exactPhrases.isNotEmpty() || mediaFilter != null
}

/**
 * 検索クエリ文字列をパースしてオペレータトークンを抽出する。
 *
 * サポートするオペレータ:
 *   #タグ            → #t タグフィルタ
 *   from:npub1...   → authors フィルタ (npub / 64桁hex)
 *   from:user@domain → NIP-05解決 → authors フィルタ
 *   since:YYYY-MM-DD → since タイムスタンプ
 *   until:YYYY-MM-DD → until タイムスタンプ
 *   -除外語          → 結果からクライアント側除外
 *   "完全一致"       → クライアント側完全一致フィルタ
 *   filter:image     → 画像URLを含む投稿のみ
 *   filter:video     → 動画URLを含む投稿のみ
 *   filter:link      → リンクを含む投稿のみ
 */
object SearchQueryParser {

    // 各オペレータの正規表現
    private val exactRegex   = Regex(""""([^"]+)"""")
    private val hashtagRegex = Regex("""(?<!\S)#(\w+)""")
    private val fromRegex    = Regex("""from:(\S+)""")
    private val sinceRegex   = Regex("""since:(\d{4}-\d{2}-\d{2})""")
    private val untilRegex   = Regex("""until:(\d{4}-\d{2}-\d{2})""")
    private val mediaRegex   = Regex("""filter:(image|video|link)""")
    private val excludeRegex = Regex("""(?<!\S)-([^\s]+)""")
    private val hexRegex     = Regex("""^[0-9a-f]{64}$""")

    fun parse(raw: String): ParsedSearchQuery {
        var s = raw.trim()

        // 1. 完全一致フレーズ ("...") を先に抽出（他パターンと干渉しないよう）
        val exactPhrases = exactRegex.findAll(s).map { it.groupValues[1] }.toList()
        s = exactRegex.replace(s, " ")

        // 2. ハッシュタグ
        val hashtags = hashtagRegex.findAll(s).map { it.groupValues[1].lowercase() }.toList()
        s = hashtagRegex.replace(s, " ")

        // 3. from:
        val fromTargets = fromRegex.findAll(s).map { it.groupValues[1] }.toList()
        s = fromRegex.replace(s, " ")

        // 4. since:
        val since = sinceRegex.find(s)?.groupValues?.get(1)?.toEpoch()
        s = sinceRegex.replace(s, " ")

        // 5. until:
        val until = untilRegex.find(s)?.groupValues?.get(1)?.toEpoch()
        s = untilRegex.replace(s, " ")

        // 6. filter:image/video/link
        val mediaFilter = mediaRegex.find(s)?.groupValues?.get(1)?.let {
            when (it) {
                "image" -> ParsedSearchQuery.MediaFilter.IMAGE
                "video" -> ParsedSearchQuery.MediaFilter.VIDEO
                "link"  -> ParsedSearchQuery.MediaFilter.LINK
                else    -> null
            }
        }
        s = mediaRegex.replace(s, " ")

        // 7. -除外語（URLのhttps://などと衝突しないよう単語境界で判定）
        val excludeWords = excludeRegex.findAll(s).map { it.groupValues[1] }.toList()
        s = excludeRegex.replace(s, " ")

        // 8. from: ターゲットを種別分類
        val fromPubkeys = fromTargets.filter { it.startsWith("npub") || hexRegex.matches(it) }
        val fromNip05   = fromTargets.filter { it.contains("@") }

        // 9. 残りがテキストクエリ
        val textQuery = s.replace(Regex("""\s+"""), " ").trim()

        return ParsedSearchQuery(
            textQuery    = textQuery,
            hashtags     = hashtags,
            fromPubkeys  = fromPubkeys,
            fromNip05    = fromNip05,
            since        = since,
            until        = until,
            excludeWords = excludeWords,
            exactPhrases = exactPhrases,
            mediaFilter  = mediaFilter,
        )
    }

    private fun String.toEpoch(): Long? = try {
        LocalDate.parse(this).atStartOfDay(ZoneOffset.UTC).toEpochSecond()
    } catch (_: Exception) { null }
}
