package code.name.monkey.retromusic.db

import androidx.room.ColumnInfo
import androidx.room.Entity

/**
 * Ownership link between a listener profile and a MediaStore song id.
 * The whole library (songs / albums / artists / home / search) is isolated
 * per profile by restricting to the song ids owned by the current profile.
 */
@Entity(tableName = "profile_song", primaryKeys = ["profile_id", "song_id"])
data class ProfileSongEntity(
    @ColumnInfo(name = "profile_id") val profileId: Long,
    @ColumnInfo(name = "song_id") val songId: Long
)
