package code.name.monkey.retromusic.util

/**
 * 零依赖的汉字拼音首字母工具。
 *
 * 依据 GB2312/GBK 的性质：一级汉字（0xB0A1–0xD7F9）本身**按拼音排列**，因此为 23 个
 * 声母各取一个"组首字"，其 GBK 码即该声母的下界；对任意一级字用 GBK 码二分即得声母。
 * 结果完全确定、跨设备一致（不依赖各系统 Collator 的实现差异）。
 *
 * 局限与兜底：
 * - 二级字（0xD8A1+）在 GBK 里按部首排列，无法由码值推拼音 → 返回 null（跳过），
 *   宁可漏拼音命中也不产生错误匹配（错误字母会带来"假命中"，危害更大）。
 * - 对少数落在二级区间但常用的专有字（如"窦"），用 [overrides] 手工补。
 * - 多音字取常规读音，搜索场景足够。
 */
object PinyinUtil {

    private const val LEVEL1_START = 0xB0A1
    private const val LEVEL1_END = 0xD7F9

    // 每个声母组的"组首字"（一级、拼音序），其 GBK 码作为该声母下界
    private val anchors = charArrayOf(
        '啊', '芭', '擦', '搭', '蛾', '发', '噶', '哈', '击', '喀', '垃', '妈',
        '拿', '哦', '啪', '期', '然', '撒', '塌', '蛙', '昔', '压', '匝'
    )

    // 与 anchors 一一对应的声母（中文无 I/U/V，共 23 个）
    private val letters = charArrayOf(
        'A', 'B', 'C', 'D', 'E', 'F', 'G', 'H', 'J', 'K', 'L', 'M',
        'N', 'O', 'P', 'Q', 'R', 'S', 'T', 'W', 'X', 'Y', 'Z'
    )

    // 预计算锚点 GBK 码（一级字拼音有序 → 单调递增）
    private val bounds: IntArray = anchors.map { gbkCode(it) ?: LEVEL1_START }.toIntArray()

    // 落在二级区间、但曲库常用的专有字，手工兜底
    private val overrides: Map<Char, Char> = hashMapOf('窦' to 'D')

    private fun gbkCode(c: Char): Int? = try {
        val b = c.toString().toByteArray(charset("GBK"))
        if (b.size < 2) null else ((b[0].toInt() and 0xFF) shl 8) or (b[1].toInt() and 0xFF)
    } catch (e: Exception) {
        null
    }

    /** 单字声母；ASCII 字母数字返回大写，无法判定的字符返回 null。 */
    fun firstLetter(c: Char): Char? {
        if (c.code < 0x80) return if (c.isLetterOrDigit()) c.uppercaseChar() else null
        overrides[c]?.let { return it }
        val code = gbkCode(c) ?: return null
        if (code < LEVEL1_START || code > LEVEL1_END) return null
        var lo = 0
        var hi = bounds.size - 1
        var res = 0
        while (lo <= hi) {
            val m = (lo + hi) ushr 1
            if (bounds[m] <= code) {
                res = m
                lo = m + 1
            } else {
                hi = m - 1
            }
        }
        return letters[res]
    }

    /**
     * 整串声母缩写，仅保留汉字首字母与字母数字（跳过标点/空格/无法判定的字）。
     * 例："黑梦" -> "HM"；"窦唯" -> "DW"；"告别2018" -> "GB2018"。
     */
    fun initials(s: String?): String {
        if (s.isNullOrEmpty()) return ""
        val sb = StringBuilder(s.length)
        for (c in s) {
            val r = firstLetter(c) ?: continue
            sb.append(r)
        }
        return sb.toString()
    }
}
