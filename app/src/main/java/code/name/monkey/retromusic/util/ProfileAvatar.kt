package code.name.monkey.retromusic.util

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.resource.bitmap.CircleCrop
import com.bumptech.glide.request.RequestOptions
import code.name.monkey.retromusic.model.UserProfile
import java.io.File

/**
 * Shared helpers for rendering a [UserProfile]'s avatar.
 *
 * A profile either has a real photo (stored locally under filesDir) or, when none
 * is set, falls back to a colored circle showing the first letter of the name.
 * The color is derived deterministically from the name so the same user always
 * gets the same placeholder.
 */
object ProfileAvatar {

    // A calm, mid-tone palette that keeps white initials legible on any theme.
    private val PALETTE = intArrayOf(
        0xFF5C6BC0.toInt(), // indigo
        0xFF26A69A.toInt(), // teal
        0xFF66BB6A.toInt(), // green
        0xFFEF5350.toInt(), // red
        0xFFAB47BC.toInt(), // purple
        0xFF42A5F5.toInt(), // blue
        0xFFFFA726.toInt(), // orange
        0xFFEC407A.toInt(), // pink
        0xFF7E57C2.toInt(), // deep purple
        0xFF00897B.toInt()  // dark teal
    )

    fun colorFor(name: String): Int {
        if (name.isEmpty()) return PALETTE[0]
        var hash = 0
        for (c in name) hash = (hash * 31 + c.code) and 0x7FFFFFFF
        return PALETTE[hash % PALETTE.size]
    }

    fun initialOf(name: String): String {
        val trimmed = name.trim()
        return if (trimmed.isEmpty()) "?" else trimmed.substring(0, 1).uppercase()
    }

    fun avatarFile(context: Context, profileId: Long): File =
        File(context.filesDir, "profile_avatar_$profileId.webp")

    /**
     * Binds a profile's avatar into the [image] + [initial] pair used by both the
     * list row and the edit dialog. Exactly one of the two ends up visible.
     */
    fun bind(profile: UserProfile, image: ImageView, initial: TextView) {
        val file = profile.avatarPath?.let(::File)
        if (file != null && file.exists()) {
            initial.visibility = View.GONE
            image.visibility = View.VISIBLE
            Glide.with(image)
                .load(file)
                .apply(RequestOptions.bitmapTransform(CircleCrop()))
                .diskCacheStrategy(DiskCacheStrategy.NONE)
                .placeholder(defaultDrawable(profile))
                .into(image)
        } else {
            Glide.with(image).clear(image)
            image.visibility = View.GONE
            initial.visibility = View.VISIBLE
            initial.text = initialOf(profile.name)
            initial.background = circleDrawable(colorFor(profile.name))
        }
    }

    fun defaultDrawable(profile: UserProfile): GradientDrawable = circleDrawable(colorFor(profile.name))

    private fun circleDrawable(color: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
    }

    /**
     * Decodes [uri] to a downscaled square-ish bitmap and persists it as the
     * profile's avatar file. Returns the saved absolute path, or null on failure.
     * Must be called off the main thread (uses Glide#submit blocking load).
     */
    fun saveAvatarFromUri(context: Context, uri: Uri, profileId: Long): String? {
        return try {
            val bitmap = Glide.with(context.applicationContext)
                .asBitmap()
                .load(uri)
                .override(512, 512)
                .centerCrop()
                .diskCacheStrategy(DiskCacheStrategy.NONE)
                .submit()
                .get()
            val file = avatarFile(context, profileId)
            file.outputStream().buffered().use { out ->
                bitmap.compress(android.graphics.Bitmap.CompressFormat.WEBP, 90, out)
            }
            file.absolutePath
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun deleteAvatar(context: Context, profile: UserProfile) {
        profile.avatarPath?.let { File(it) }?.takeIf { it.exists() }?.delete()
        avatarFile(context, profile.id).takeIf { it.exists() }?.delete()
    }
}
