/*
 * Copyright (c) 2019 Hemanth Savarala.
 *
 * Licensed under the GNU General Public License v3
 *
 * This is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by
 *  the Free Software Foundation either version 3 of the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 */

package code.name.monkey.retromusic.repository

import android.content.Context
import code.name.monkey.retromusic.R
import code.name.monkey.retromusic.db.PlaylistWithSongs
import code.name.monkey.retromusic.fragments.search.Filter
import code.name.monkey.retromusic.model.Album
import code.name.monkey.retromusic.model.Artist
import code.name.monkey.retromusic.model.Genre
import code.name.monkey.retromusic.model.Song
import code.name.monkey.retromusic.util.SearchIndex

class RealSearchRepository(
    private val songRepository: SongRepository,
    private val albumRepository: AlbumRepository,
    private val artistRepository: ArtistRepository,
    private val roomRepository: RoomRepository,
    private val genreRepository: GenreRepository,
) {
    suspend fun searchAll(context: Context, query: String?, filter: Filter): MutableList<Any> {
        val results = mutableListOf<Any>()
        if (query.isNullOrEmpty()) return results
        query.let { searchString ->

            /** Songs **/
            val songs: List<Song> = if (filter == Filter.SONGS || filter == Filter.NO_FILTER) {
                // 多字段(标题/歌手/专辑)+拼音首字母+相关度排序；索引未就绪时退回 SQL 单字段
                if (SearchIndex.isEmpty()) songRepository.songs(searchString)
                else SearchIndex.search(searchString)
            } else {
                emptyList()
            }
            if (songs.isNotEmpty()) {
                results.add(sectionHeader(context, R.string.songs, songs.size))
                results.addAll(songs)
            }

            /** Artists **/
            val artists: List<Artist> =
                if (filter == Filter.ARTISTS || filter == Filter.NO_FILTER) {
                    artistRepository.artists(searchString)
                } else {
                    emptyList()
                }
            if (artists.isNotEmpty()) {
                results.add(sectionHeader(context, R.string.artists, artists.size))
                results.addAll(artists)
            }

            /** Albums **/
            val albums: List<Album> = if (filter == Filter.ALBUMS || filter == Filter.NO_FILTER) {
                albumRepository.albums(searchString)
            } else {
                emptyList()
            }
            if (albums.isNotEmpty()) {
                results.add(sectionHeader(context, R.string.albums, albums.size))
                results.addAll(albums)
            }

            /** Album-Artists **/
            val albumArtists: List<Artist> =
                if (filter == Filter.ALBUM_ARTISTS || filter == Filter.NO_FILTER) {
                    artistRepository.albumArtists(searchString)
                } else {
                    emptyList()
                }
            if (albumArtists.isNotEmpty()) {
                results.add(sectionHeader(context, R.string.album_artist, albumArtists.size))
                results.addAll(albumArtists)
            }

            /** Genres **/
            val genres: List<Genre> = if (filter == Filter.GENRES || filter == Filter.NO_FILTER) {
                genreRepository.genres(query)
            } else {
                emptyList()
            }
            if (genres.isNotEmpty()) {
                results.add(sectionHeader(context, R.string.genres, genres.size))
                results.addAll(genres)
            }

            /** Playlists **/
            val playlist: List<PlaylistWithSongs> =
                if (filter == Filter.PLAYLISTS || filter == Filter.NO_FILTER) {
                    roomRepository.playlistWithSongs().filter { playlist ->
                        playlist.playlistEntity.playlistName.lowercase().contains(searchString.lowercase())
                    }
                } else {
                    emptyList()
                }

            if (playlist.isNotEmpty()) {
                results.add(sectionHeader(context, R.string.playlists, playlist.size))
                results.addAll(playlist)
            }
        }
        return results
    }

    /** 分组标题 + 命中数量徽标，如 "歌曲 (12)" */
    private fun sectionHeader(context: Context, titleRes: Int, count: Int): String {
        return context.getString(titleRes) + " (${count})"
    }
}
