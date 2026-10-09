/*
 * Copyright (c) 2020 Hemanth Savarla.
 *
 * Licensed under the GNU General Public License v3
 *
 * This is free software: you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 */
package code.name.monkey.retromusic.adapter

import android.annotation.SuppressLint
import android.text.SpannableString
import android.text.Spanned
import android.text.style.StyleSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.os.bundleOf
import androidx.core.view.isGone
import androidx.core.view.isInvisible
import androidx.core.view.isVisible
import androidx.fragment.app.FragmentActivity
import androidx.navigation.findNavController
import androidx.recyclerview.widget.RecyclerView
import code.name.monkey.appthemehelper.ThemeStore
import code.name.monkey.retromusic.*
import code.name.monkey.retromusic.adapter.base.MediaEntryViewHolder
import code.name.monkey.retromusic.db.PlaylistWithSongs
import code.name.monkey.retromusic.db.toSongs
import code.name.monkey.retromusic.glide.RetroGlideExtension
import code.name.monkey.retromusic.glide.RetroGlideExtension.albumCoverOptions
import code.name.monkey.retromusic.glide.RetroGlideExtension.artistImageOptions
import code.name.monkey.retromusic.glide.RetroGlideExtension.songCoverOptions
import code.name.monkey.retromusic.helper.MusicPlayerRemote
import code.name.monkey.retromusic.helper.menu.SongMenuHelper
import code.name.monkey.retromusic.model.Album
import code.name.monkey.retromusic.model.Artist
import code.name.monkey.retromusic.model.Genre
import code.name.monkey.retromusic.model.Song
import code.name.monkey.retromusic.repository.GenreRepository
import code.name.monkey.retromusic.util.MusicUtil
import com.bumptech.glide.Glide
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import java.util.*

class SearchAdapter(
    private val activity: FragmentActivity,
    private var dataSet: List<Any>
) : RecyclerView.Adapter<SearchAdapter.ViewHolder>(), KoinComponent {

    /** 当前搜索关键词，用于命中高亮 */
    var query: String? = null

    /** 把 [src] 中所有不区分大小写命中 [query] 的片段加粗高亮 */
    private fun highlight(src: String?): CharSequence {
        if (src.isNullOrEmpty()) return src ?: ""
        val q = query?.trim().orEmpty()
        if (q.isEmpty()) return src
        val lower = src.lowercase()
        val ql = q.lowercase()
        var idx = lower.indexOf(ql)
        if (idx < 0) return src
        val sp = SpannableString(src)
        while (idx >= 0) {
            sp.setSpan(
                StyleSpan(android.graphics.Typeface.BOLD),
                idx, idx + ql.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            idx = lower.indexOf(ql, idx + ql.length)
        }
        return sp
    }

    @SuppressLint("NotifyDataSetChanged")
    fun swapDataSet(dataSet: List<Any>) {
        this.dataSet = dataSet
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int): Int {
        if (dataSet[position] is Album) return ALBUM
        if (dataSet[position] is Artist) return if ((dataSet[position] as Artist).isAlbumArtist) ALBUM_ARTIST else ARTIST
        if (dataSet[position] is Genre) return GENRE
        if (dataSet[position] is PlaylistWithSongs) return PLAYLIST
        return if (dataSet[position] is Song) SONG else HEADER
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        return when (viewType) {
            HEADER -> ViewHolder(
                LayoutInflater.from(activity).inflate(
                    R.layout.sub_header,
                    parent,
                    false
                ), viewType
            )

            ALBUM, ARTIST, ALBUM_ARTIST -> ViewHolder(
                LayoutInflater.from(activity).inflate(
                    R.layout.item_list_big,
                    parent,
                    false
                ), viewType
            )

            else -> ViewHolder(
                LayoutInflater.from(activity).inflate(R.layout.item_list, parent, false),
                viewType
            )
        }
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        when (getItemViewType(position)) {
            ALBUM -> {
                holder.imageTextContainer?.isVisible = true
                val album = dataSet[position] as Album
                holder.title?.text = highlight(album.title)
                holder.text?.text = album.artistName
                Glide.with(activity).asDrawable().albumCoverOptions(album.safeGetFirstSong())
                    .load(RetroGlideExtension.getSongModel(album.safeGetFirstSong()))
                    .into(holder.image!!)
                holder.play?.isVisible = true
                holder.play?.setOnClickListener {
                    MusicPlayerRemote.openQueue(album.songs, 0, true)
                }
            }

            ARTIST -> {
                holder.imageTextContainer?.isVisible = true
                val artist = dataSet[position] as Artist
                holder.title?.text = highlight(artist.name)
                holder.text?.text = MusicUtil.getArtistInfoString(activity, artist)
                Glide.with(activity).asDrawable().artistImageOptions(artist).load(
                    RetroGlideExtension.getArtistModel(artist)
                ).into(holder.image!!)
                holder.play?.isVisible = true
                holder.play?.setOnClickListener {
                    MusicPlayerRemote.openQueue(artist.songs, 0, true)
                }
            }

            SONG -> {
                holder.imageTextContainer?.isVisible = true
                val song = dataSet[position] as Song
                holder.title?.text = highlight(song.title)
                holder.text?.text = highlight(song.albumName)
                Glide.with(activity).asDrawable().songCoverOptions(song)
                    .load(RetroGlideExtension.getSongModel(song)).into(holder.image!!)
            }

            GENRE -> {
                val genre = dataSet[position] as Genre
                holder.title?.text = genre.name
                holder.text?.text = String.format(
                    Locale.getDefault(),
                    "%d %s",
                    genre.songCount,
                    if (genre.songCount > 1) activity.getString(R.string.songs) else activity.getString(
                        R.string.song
                    )
                )
                holder.play?.isVisible = true
                holder.play?.setOnClickListener {
                    CoroutineScope(Dispatchers.Main).launch {
                        val songs = withContext(Dispatchers.IO) {
                            get<GenreRepository>().songs(genre.id)
                        }
                        MusicPlayerRemote.openQueue(songs, 0, true)
                    }
                }
            }

            PLAYLIST -> {
                val playlist = dataSet[position] as PlaylistWithSongs
                holder.title?.text = highlight(playlist.playlistEntity.playlistName)
                //holder.text?.text = MusicUtil.playlistInfoString(activity, playlist.songs)
                holder.play?.isVisible = true
                holder.play?.setOnClickListener {
                    MusicPlayerRemote.openQueue(playlist.songs.toSongs(), 0, true)
                }
            }

            ALBUM_ARTIST -> {
                holder.imageTextContainer?.isVisible = true
                val artist = dataSet[position] as Artist
                holder.title?.text = artist.name
                holder.text?.text = MusicUtil.getArtistInfoString(activity, artist)
                Glide.with(activity).asDrawable().artistImageOptions(artist).load(
                    RetroGlideExtension.getArtistModel(artist)
                ).into(holder.image!!)
                holder.play?.isVisible = true
                holder.play?.setOnClickListener {
                    MusicPlayerRemote.openQueue(artist.songs, 0, true)
                }
            }

            else -> {
                holder.title?.text = dataSet[position].toString()
                holder.title?.setTextColor(ThemeStore.accentColor(activity))
            }
        }
    }

    override fun getItemCount(): Int {
        return dataSet.size
    }

    inner class ViewHolder(itemView: View, itemViewType: Int) : MediaEntryViewHolder(itemView) {
        val play: View? = itemView.findViewById(R.id.play)

        init {
            itemView.setOnLongClickListener(null)
            imageTextContainer?.isInvisible = true
            if (itemViewType == SONG) {
                imageTextContainer?.isGone = true
                menu?.isVisible = true
                menu?.setOnClickListener(object : SongMenuHelper.OnClickSongMenu(activity) {
                    override val song: Song
                        get() = dataSet[layoutPosition] as Song
                })
            } else {
                menu?.isVisible = false
            }

            when (itemViewType) {
                ALBUM -> setImageTransitionName(activity.getString(R.string.transition_album_art))
                ARTIST -> setImageTransitionName(activity.getString(R.string.transition_artist_image))
                else -> {
                    val container = itemView.findViewById<View>(R.id.imageContainer)
                    container?.isVisible = false
                }
            }
        }

        override fun onClick(v: View?) {
            val item = dataSet[layoutPosition]
            when (itemViewType) {
                ALBUM -> {
                    activity.findNavController(R.id.fragment_container).navigate(
                        R.id.albumDetailsFragment,
                        bundleOf(EXTRA_ALBUM_ID to (item as Album).id)
                    )
                }

                ARTIST -> {
                    activity.findNavController(R.id.fragment_container).navigate(
                        R.id.artistDetailsFragment,
                        bundleOf(EXTRA_ARTIST_ID to (item as Artist).id)
                    )
                }

                ALBUM_ARTIST -> {
                    activity.findNavController(R.id.fragment_container).navigate(
                        R.id.albumArtistDetailsFragment,
                        bundleOf(EXTRA_ARTIST_NAME to (item as Artist).name)
                    )
                }

                GENRE -> {
                    activity.findNavController(R.id.fragment_container).navigate(
                        R.id.genreDetailsFragment,
                        bundleOf(EXTRA_GENRE to (item as Genre))
                    )
                }

                PLAYLIST -> {
                    activity.findNavController(R.id.fragment_container).navigate(
                        R.id.playlistDetailsFragment,
                        bundleOf(EXTRA_PLAYLIST_ID to (item as PlaylistWithSongs).playlistEntity.playListId)
                    )
                }

                SONG -> {
                    // 单击直接播：以本批搜索结果的全部歌曲为队列，从点的那首开始
                    val song = item as Song
                    val resultSongs = dataSet.filterIsInstance<Song>()
                    val index = resultSongs.indexOfFirst { it.id == song.id }
                        .coerceAtLeast(0)
                    MusicPlayerRemote.openQueue(resultSongs, index, true)
                }
            }
        }
    }

    companion object {
        private const val HEADER = 0
        private const val ALBUM = 1
        private const val ARTIST = 2
        private const val SONG = 3
        private const val GENRE = 4
        private const val PLAYLIST = 5
        private const val ALBUM_ARTIST = 6
    }
}
