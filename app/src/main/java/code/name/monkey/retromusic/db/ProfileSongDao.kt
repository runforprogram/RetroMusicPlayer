package code.name.monkey.retromusic.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface ProfileSongDao {
    @Query("SELECT song_id FROM profile_song WHERE profile_id = :profileId")
    fun songIdsForProfile(profileId: Long): List<Long>

    @Query("SELECT song_id FROM profile_song")
    fun allOwnedIds(): List<Long>

    @Query("SELECT COUNT(*) FROM profile_song")
    fun count(): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertAll(songs: List<ProfileSongEntity>)

    @Query("DELETE FROM profile_song WHERE profile_id = :profileId")
    fun deleteForProfile(profileId: Long)

    @Query("DELETE FROM profile_song")
    fun clearAll()
}
