package code.name.monkey.retromusic.util

import code.name.monkey.retromusic.db.ProfileSongDao
import code.name.monkey.retromusic.db.ProfileSongEntity
import code.name.monkey.retromusic.model.Song
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Holds the "profile -> owned song ids" mapping used to isolate the whole
 * library per listener profile.
 *
 * Ownership lives in its own Room table (profile_song) while the library is
 * queried from MediaStore, so the two cannot be joined in one SQL statement.
 * Instead we load the current profile's owned ids into memory and inline them
 * into the MediaStore selection as `_id IN (...)` (see SongRepository).
 *
 * Classification rules, in priority order:
 *  1. Folder convention: `<...>/Music/run/...` belongs to 我,
 *     `<...>/Music/luo/...` belongs to 落 (case-insensitive).
 *  2. Keyword rule: 英文儿歌 / 口语精选 / YouTube in title/artist/album/path -> 落.
 *  3. Everything else -> the default profile (id 0).
 *
 * Classification is incremental: every library (re)load assigns owners to the
 * songs that are not in `profile_song` yet, so freshly imported files show up
 * for the right user without re-touching existing assignments.
 */
object ProfileSongStore {

    private val kidsRegex = Regex("\u82f1\u6587\u513f\u6b4c|\u53e3\u8bed\u7cbe\u9009|YouTube", RegexOption.IGNORE_CASE)

    private var dao: ProfileSongDao? = null
    private val mutex = Mutex()

    @Volatile
    private var cache: MutableMap<Long, Set<Long>> = HashMap()

    @Volatile
    private var classified: Boolean = false

    fun init(dao: ProfileSongDao) {
        this.dao = dao
    }

    fun isReady(): Boolean = classified

    fun invalidate() {
        cache = HashMap()
    }

    fun ownedIdsOrNull(profileId: Long): Set<Long>? {
        if (!classified) return null
        cache[profileId]?.let { return it }
        val ids = dao?.songIdsForProfile(profileId)?.toSet() ?: emptySet()
        cache[profileId] = ids
        return ids
    }

    suspend fun classifyNewSongs(allSongs: suspend () -> List<Song>) {
        val dao = this.dao ?: return
        mutex.withLock {
            val songs = allSongs()
            val owned = dao.allOwnedIds().toSet()
            val unowned = songs.filterNot { owned.contains(it.id) }
            if (unowned.isNotEmpty()) {
                dao.insertAll(unowned.map { ProfileSongEntity(classify(it), it.id) })
                cache = HashMap()
            }
            classified = true
        }
    }

    private fun classify(song: Song): Long {
        val path = song.data.lowercase()
        if (path.contains("/music/run/")) return profileIdByName("\u6211", 0L)
        if (path.contains("/music/luo/")) return profileIdByName("\u843d", 0L)
        val kids = PreferenceUtil.profiles.firstOrNull { it.name == "\u843d" }
        val hay = "${song.title} ${song.artistName} ${song.albumName} ${song.data}"
        return if (kids != null && kidsRegex.containsMatchIn(hay)) kids.id else 0L
    }

    private fun profileIdByName(name: String, fallback: Long): Long {
        return PreferenceUtil.profiles.firstOrNull { it.name == name }?.id ?: fallback
    }
}
