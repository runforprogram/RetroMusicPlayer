/*
 * Copyright (c) 2024.
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
 */
package code.name.monkey.retromusic.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * A music listener profile. Each profile owns its own playlists and favorites,
 * so multiple people sharing one device/app keep their libraries separate.
 */
@Parcelize
data class UserProfile(
    val id: Long,
    val name: String,
    // Absolute path of a locally stored avatar image, or null to fall back to a
    // colored initial-letter placeholder derived from [name].
    val avatarPath: String? = null
) : Parcelable
