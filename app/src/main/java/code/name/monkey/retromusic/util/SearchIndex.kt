package code.name.monkey.retromusic.util

import code.name.monkey.retromusic.model.Song

/**
 * 内存搜索索引：随（已按用户过滤的）歌曲列表重载，为搜索提供
 * 多字段（标题/歌手/专辑）+ 拼音首字母匹配与相关度排序。
 *
 * 数据来源是 [LibraryViewModel] 里 profile 过滤后的快照，因此天然继承多用户隔离，
 * 不再走 MediaStore 的 `TITLE LIKE` 单字段查询。
 */
object SearchIndex {

    private class Entry(
        val song: Song,
        val titleLower: String,
        val artistLower: String,
        val albumLower: String,
        val titlePy: String,
        val artistPy: String,
        val albumPy: String,
    )

    @Volatile
    private var entries: List<Entry> = emptyList()

    fun isEmpty(): Boolean = entries.isEmpty()

    /** 用当前用户可见的歌曲列表重建索引（在 IO 线程调用）。 */
    fun rebuild(songs: List<Song>) {
        entries = songs.map {
            Entry(
                it,
                it.title.lowercase(),
                it.artistName.lowercase(),
                it.albumName.lowercase(),
                PinyinUtil.initials(it.title),
                PinyinUtil.initials(it.artistName),
                PinyinUtil.initials(it.albumName),
            )
        }
    }

    /** 相关度降序返回命中歌曲；同分按标题字典序。 */
    fun search(query: String): List<Song> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val qLower = q.lowercase()
        val qPy = PinyinUtil.initials(q)
        val latinQuery = q.all { it.code < 0x80 } && qPy.isNotEmpty()

        val scored = ArrayList<Pair<Int, Song>>(entries.size)
        for (e in entries) {
            var score = 0

            // 标题
            when {
                e.titleLower == qLower -> score = maxOf(score, 100)
                e.titleLower.startsWith(qLower) -> score = maxOf(score, 80)
                e.titleLower.contains(qLower) -> score = maxOf(score, 60)
            }
            // 歌手
            when {
                e.artistLower == qLower -> score = maxOf(score, 55)
                e.artistLower.startsWith(qLower) -> score = maxOf(score, 45)
                e.artistLower.contains(qLower) -> score = maxOf(score, 35)
            }
            // 专辑
            when {
                e.albumLower == qLower -> score = maxOf(score, 32)
                e.albumLower.startsWith(qLower) -> score = maxOf(score, 28)
                e.albumLower.contains(qLower) -> score = maxOf(score, 20)
            }
            // 拼音首字母（仅纯 ASCII 查询有意义）
            if (latinQuery) {
                when {
                    e.titlePy.startsWith(qPy) -> score = maxOf(score, 70)
                    e.titlePy.contains(qPy) -> score = maxOf(score, 50)
                }
                when {
                    e.artistPy.startsWith(qPy) -> score = maxOf(score, 42)
                    e.artistPy.contains(qPy) -> score = maxOf(score, 30)
                }
                if (e.albumPy.startsWith(qPy)) score = maxOf(score, 26)
            }

            if (score > 0) scored.add(score to e.song)
        }

        return scored
            .sortedWith(compareByDescending<Pair<Int, Song>> { it.first }.thenBy { it.second.title })
            .map { it.second }
    }
}
