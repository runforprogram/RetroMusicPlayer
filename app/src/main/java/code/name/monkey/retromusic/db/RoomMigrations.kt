package code.name.monkey.retromusic.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val MIGRATION_23_24 = object : Migration(23, 24) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL("DROP TABLE LyricsEntity")
        database.execSQL("DROP TABLE BlackListStoreEntity")
    }
}

val MIGRATION_24_25 = object : Migration(24, 25) {
    override fun migrate(database: SupportSQLiteDatabase) {
        // Associate every playlist with an owner (profile). Existing playlists
        // default to profile 0 (the original user) so old data is preserved.
        database.execSQL(
            "ALTER TABLE PlaylistEntity ADD COLUMN playlist_creator INTEGER NOT NULL DEFAULT 0"
        )
    }
}

val MIGRATION_25_26 = object : Migration(25, 26) {
    override fun migrate(database: SupportSQLiteDatabase) {
        // Ownership of every MediaStore song id to a listener profile.
        database.execSQL(
            "CREATE TABLE IF NOT EXISTS `profile_song` (" +
                "`profile_id` INTEGER NOT NULL, " +
                "`song_id` INTEGER NOT NULL, " +
                "PRIMARY KEY(`profile_id`, `song_id`))"
        )
    }
}
