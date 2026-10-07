package code.name.monkey.retromusic.repository

import android.content.Context
import androidx.annotation.WorkerThread
import androidx.lifecycle.LiveData
import code.name.monkey.retromusic.R
import code.name.monkey.retromusic.db.*
import code.name.monkey.retromusic.helper.SortOrder.PlaylistSortOrder.Companion.PLAYLIST_A_Z
import code.name.monkey.retromusic.helper.SortOrder.PlaylistSortOrder.Companion.PLAYLIST_SONG_COUNT
import code.name.monkey.retromusic.helper.SortOrder.PlaylistSortOrder.Companion.PLAYLIST_SONG_COUNT_DESC
import code.name.monkey.retromusic.helper.SortOrder.PlaylistSortOrder.Companion.PLAYLIST_Z_A
import code.name.monkey.retromusic.model.Song
import code.name.monkey.retromusic.util.PreferenceUtil
import java.text.Collator


interface RoomRepository {
    fun historySongs(): List<HistoryEntity>
    fun favoritePlaylistLiveData(favorite: String): LiveData<List<SongEntity>>
    fun observableHistorySongs(): LiveData<List<HistoryEntity>>
    fun getSongs(playListId: Long): LiveData<List<SongEntity>>
    suspend fun createPlaylist(playlistEntity: PlaylistEntity): Long
    suspend fun checkPlaylistExists(playlistName: String): List<PlaylistEntity>
    suspend fun playlists(): List<PlaylistEntity>
    suspend fun playlistWithSongs(): List<PlaylistWithSongs>
    suspend fun insertSongs(songs: List<SongEntity>)
    suspend fun deletePlaylistEntities(playlistEntities: List<PlaylistEntity>)
    suspend fun renamePlaylistEntity(playlistId: Long, name: String)
    suspend fun deleteSongsInPlaylist(songs: List<SongEntity>)
    suspend fun deletePlaylistSongs(playlists: List<PlaylistEntity>)
    suspend fun favoritePlaylist(favorite: String): PlaylistEntity
    suspend fun isFavoriteSong(songEntity: SongEntity): List<SongEntity>
    suspend fun removeSongFromPlaylist(songEntity: SongEntity)
    suspend fun upsertSongInHistory(currentSong: Song)
    suspend fun favoritePlaylistSongs(favorite: String): List<SongEntity>
    suspend fun upsertSongInPlayCount(playCountEntity: PlayCountEntity)
    suspend fun deleteSongInPlayCount(playCountEntity: PlayCountEntity)
    suspend fun deleteSongInHistory(songId: Long)
    suspend fun clearSongHistory()
    suspend fun findSongExistInPlayCount(songId: Long): PlayCountEntity?
    suspend fun playCountSongs(): List<PlayCountEntity>
    suspend fun deleteSongs(songs: List<Song>)
    suspend fun isSongFavorite(context: Context, songId: Long): Boolean
    fun checkPlaylistExists(playListId: Long): LiveData<Boolean>
    fun getPlaylist(playlistId: Long): LiveData<PlaylistWithSongs>
    suspend fun deletePlaylistsForProfile(profileId: Long)
}

class RealRoomRepository(
    private val playlistDao: PlaylistDao,
    private val playCountDao: PlayCountDao,
    private val historyDao: HistoryDao
) : RoomRepository {
    @WorkerThread
    override suspend fun createPlaylist(playlistEntity: PlaylistEntity): Long =
        playlistDao.createPlaylist(
            PlaylistEntity(
                playListId = playlistEntity.playListId,
                playlistName = playlistEntity.playlistName,
                playlistCreator = PreferenceUtil.currentProfileId
            )
        )

    @WorkerThread
    override suspend fun checkPlaylistExists(playlistName: String): List<PlaylistEntity> =
        playlistDao.playlist(playlistName, PreferenceUtil.currentProfileId)

    @WorkerThread
    override suspend fun playlists(): List<PlaylistEntity> =
        playlistDao.playlistsForProfile(PreferenceUtil.currentProfileId)

    @WorkerThread
    override suspend fun playlistWithSongs(): List<PlaylistWithSongs> {
        val collator = Collator.getInstance()
        return when (PreferenceUtil.playlistSortOrder) {
            PLAYLIST_A_Z ->
                playlistDao.playlistsWithSongsForProfile(PreferenceUtil.currentProfileId).sortedWith { p1, p2 ->
                    collator.compare(p1.playlistEntity.playlistName, p2.playlistEntity.playlistName)
                }
            PLAYLIST_Z_A -> playlistDao.playlistsWithSongsForProfile(PreferenceUtil.currentProfileId)
                .sortedWith { p1, p2 ->
                    collator.compare(p2.playlistEntity.playlistName, p1.playlistEntity.playlistName)
                }
            PLAYLIST_SONG_COUNT -> playlistDao.playlistsWithSongsForProfile(PreferenceUtil.currentProfileId).sortedBy { it.songs.size }
            PLAYLIST_SONG_COUNT_DESC -> playlistDao.playlistsWithSongsForProfile(PreferenceUtil.currentProfileId)
                .sortedByDescending { it.songs.size }
            else -> playlistDao.playlistsWithSongsForProfile(PreferenceUtil.currentProfileId).sortedWith { p1, p2 ->
                collator.compare(p1.playlistEntity.playlistName, p2.playlistEntity.playlistName)
            }
        }
    }

    @WorkerThread
    override fun getPlaylist(playlistId: Long): LiveData<PlaylistWithSongs> = playlistDao.getPlaylist(playlistId)

    @WorkerThread
    override suspend fun insertSongs(songs: List<SongEntity>) {

        playlistDao.insertSongsToPlaylist(songs)
    }

    override fun getSongs(playListId: Long): LiveData<List<SongEntity>> =
        playlistDao.songsFromPlaylist(playListId)

    override fun checkPlaylistExists(playListId: Long): LiveData<Boolean> =
        playlistDao.checkPlaylistExists(playListId)

    override suspend fun deletePlaylistEntities(playlistEntities: List<PlaylistEntity>) =
        playlistDao.deletePlaylists(playlistEntities)

    override suspend fun renamePlaylistEntity(playlistId: Long, name: String) =
        playlistDao.renamePlaylist(playlistId, name)

    override suspend fun deleteSongsInPlaylist(songs: List<SongEntity>) {
        songs.forEach {
            playlistDao.deleteSongFromPlaylist(it.playlistCreatorId, it.id)
        }
    }

    override suspend fun deletePlaylistSongs(playlists: List<PlaylistEntity>) =
        playlists.forEach {
            playlistDao.deletePlaylistSongs(it.playListId)
        }

    override suspend fun favoritePlaylist(favorite: String): PlaylistEntity {
        val playlist: PlaylistEntity? = playlistDao.playlist(favorite, PreferenceUtil.currentProfileId).firstOrNull()
        return if (playlist != null) {
            playlist
        } else {
            createPlaylist(PlaylistEntity(playlistName = favorite))
            playlistDao.playlist(favorite, PreferenceUtil.currentProfileId).first()
        }
    }

    override suspend fun isFavoriteSong(songEntity: SongEntity): List<SongEntity> =
        playlistDao.isSongExistsInPlaylist(
            songEntity.playlistCreatorId,
            songEntity.id
        )

    override suspend fun removeSongFromPlaylist(songEntity: SongEntity) =
        playlistDao.deleteSongFromPlaylist(songEntity.playlistCreatorId, songEntity.id)

    override suspend fun upsertSongInHistory(currentSong: Song) =
        historyDao.upsertSongInHistory(currentSong.toHistoryEntity(System.currentTimeMillis()))

    override fun observableHistorySongs(): LiveData<List<HistoryEntity>> =
        historyDao.observableHistorySongs()

    override fun historySongs(): List<HistoryEntity> = historyDao.historySongs()

    override fun favoritePlaylistLiveData(favorite: String): LiveData<List<SongEntity>> =
        playlistDao.favoritesSongsLiveData(favorite, PreferenceUtil.currentProfileId)

    override suspend fun favoritePlaylistSongs(favorite: String): List<SongEntity> =
        if (playlistDao.playlist(favorite, PreferenceUtil.currentProfileId).isNotEmpty()) playlistDao.favoritesSongs(
            playlistDao.playlist(favorite, PreferenceUtil.currentProfileId).first().playListId
        ) else emptyList()

    override suspend fun upsertSongInPlayCount(playCountEntity: PlayCountEntity) =
        playCountDao.upsertSongInPlayCount(playCountEntity)

    override suspend fun deleteSongInPlayCount(playCountEntity: PlayCountEntity) =
        playCountDao.deleteSongInPlayCount(playCountEntity)

    override suspend fun deleteSongInHistory(songId: Long) {
        historyDao.deleteSongInHistory(songId)
    }

    override suspend fun clearSongHistory() {
        historyDao.clearHistory()
    }

    override suspend fun findSongExistInPlayCount(songId: Long): PlayCountEntity? =
        playCountDao.findSongExistInPlayCount(songId)

    override suspend fun playCountSongs(): List<PlayCountEntity> =
        playCountDao.playCountSongs()

    override suspend fun deleteSongs(songs: List<Song>) = songs.forEach {
        playCountDao.deleteSong(it.id)
    }

    override suspend fun isSongFavorite(context: Context, songId: Long): Boolean {
        return playlistDao.isSongExistsInPlaylist(
            playlistDao.playlist(context.getString(R.string.favorites), PreferenceUtil.currentProfileId).firstOrNull()?.playListId
                ?: -1,
            songId
        ).isNotEmpty()
    }

    override suspend fun deletePlaylistsForProfile(profileId: Long) {
        playlistDao.deletePlaylistSongsForProfile(profileId)
        playlistDao.deletePlaylistEntitiesForProfile(profileId)
    }
}