package code.name.monkey.retromusic.util

import android.app.Activity
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.View
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.resource.bitmap.CircleCrop
import com.bumptech.glide.request.RequestOptions
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import code.name.monkey.retromusic.R
import code.name.monkey.retromusic.extensions.showToast
import code.name.monkey.retromusic.fragments.LibraryViewModel
import code.name.monkey.retromusic.fragments.ReloadType
import code.name.monkey.retromusic.model.UserProfile
import com.google.android.material.button.MaterialButton
import java.io.File

/**
 * Single entry point for listener-profile switching.
 *
 * The toolbar button cycles straight to the next profile (one tap), while the
 * overflow "manage profiles" entry opens the full sheet for add/rename/delete.
 * Both paths funnel through [reloadLibrary] so the whole library (which is
 * filtered per profile) refreshes consistently.
 */
object ProfileSwitcher {

    private var chip: MaterialButton? = null

    /**
     * Binds the toolbar avatar chip (a [MaterialButton] living in the toolbar
     * layout): shows the current profile name so you can always tell who is
     * active, and cycles to the next profile on tap.
     */
    fun bindProfileButton(
        activity: Activity,
        libraryViewModel: LibraryViewModel,
        root: View?
    ) {
        val button = root?.findViewById<MaterialButton>(R.id.profileButton) ?: return
        chip = button
        val profile = PreferenceUtil.currentProfile()
        button.text = profile.name
        applyProfileIcon(button, profile)
        button.setOnClickListener { cycle(activity, libraryViewModel) }
    }

    /** Re-syncs the chip label + avatar after the current profile is edited. */
    fun refreshChip() {
        val button = chip ?: return
        val profile = PreferenceUtil.currentProfile()
        button.text = profile.name
        applyProfileIcon(button, profile)
    }

    /** Shows the profile photo when set, else a colored dot keyed to the name. */
    private fun applyProfileIcon(button: MaterialButton, profile: UserProfile) {
        val file = profile.avatarPath?.let(::File)
        if (file != null && file.exists()) {
            val px = (32 * button.resources.displayMetrics.density).toInt()
            Glide.with(button)
                .load(file)
                .apply(RequestOptions.bitmapTransform(CircleCrop()))
                .diskCacheStrategy(DiskCacheStrategy.NONE)
                .override(px, px)
                .into(object : CustomTarget<Drawable>() {
                    override fun onResourceReady(resource: Drawable, transition: Transition<in Drawable>?) {
                        button.icon = resource
                        button.iconTint = null
                    }

                    override fun onLoadCleared(placeholder: Drawable?) {
                        button.icon = null
                    }
                })
        } else {
            Glide.with(button).clear(button)
            val px = (32 * button.resources.displayMetrics.density).toInt()
            button.icon = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(ProfileAvatar.colorFor(profile.name))
                setSize(px, px)
            }
            button.iconTint = null
        }
    }

    fun reloadLibrary(libraryViewModel: LibraryViewModel) {
        ProfileSongStore.invalidate()
        libraryViewModel.forceReload(ReloadType.Playlists)
        libraryViewModel.forceReload(ReloadType.HomeSections)
        libraryViewModel.forceReload(ReloadType.Songs)
        libraryViewModel.forceReload(ReloadType.Albums)
        libraryViewModel.forceReload(ReloadType.Artists)
        libraryViewModel.forceReload(ReloadType.Genres)
        libraryViewModel.forceReload(ReloadType.Suggestions)
    }

    fun switchTo(activity: Activity, libraryViewModel: LibraryViewModel, profile: UserProfile) {
        PreferenceUtil.currentProfileId = profile.id
        chip?.text = profile.name
        chip?.let { applyProfileIcon(it, profile) }
        activity.invalidateOptionsMenu()
        reloadLibrary(libraryViewModel)
        activity.showToast(activity.getString(R.string.profile_switched, profile.name))
    }

    /** One-tap switch to the next profile; toasts a hint when there is only one. */
    fun cycle(activity: Activity, libraryViewModel: LibraryViewModel) {
        val profiles = PreferenceUtil.profiles
        if (profiles.size < 2) {
            activity.showToast(activity.getString(R.string.profile_only_one_hint))
            return
        }
        val idx = profiles.indexOfFirst { it.id == PreferenceUtil.currentProfileId }
        val next = profiles[(idx + 1) % profiles.size]
        switchTo(activity, libraryViewModel, next)
    }
}
